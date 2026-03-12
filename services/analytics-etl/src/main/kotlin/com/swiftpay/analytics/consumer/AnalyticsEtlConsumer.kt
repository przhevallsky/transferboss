package com.swiftpay.analytics.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.swiftpay.analytics.client.ClickHouseClient
import com.swiftpay.analytics.model.TransferAnalyticsRecord
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@Component
class AnalyticsEtlConsumer(
    private val clickHouseClient: ClickHouseClient,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    @Value("\${etl.batch-size:100}") private val batchSize: Int
) {

    private val logger = LoggerFactory.getLogger(AnalyticsEtlConsumer::class.java)
    private val buffer = CopyOnWriteArrayList<TransferAnalyticsRecord>()

    @KafkaListener(topics = ["transfer.events"], groupId = "\${spring.kafka.consumer.group-id}")
    fun consume(message: String) {
        try {
            val record = parseEvent(message)
            buffer.add(record)
            meterRegistry.counter("etl.events.consumed").increment()

            logger.debug("Buffered event for transfer {}, buffer size: {}", record.transferId, buffer.size)

            if (buffer.size >= batchSize) {
                flush()
            }
        } catch (e: Exception) {
            logger.error("Failed to process Kafka event: {}", e.message, e)
            meterRegistry.counter("etl.events.errors").increment()
        }
    }

    @Scheduled(fixedRateString = "\${etl.flush-interval-seconds:10}000")
    fun scheduledFlush() {
        if (buffer.isNotEmpty()) {
            logger.info("Scheduled flush triggered, buffer size: {}", buffer.size)
            flush()
        }
    }

    @Synchronized
    fun flush() {
        if (buffer.isEmpty()) return

        val snapshot = ArrayList(buffer)
        buffer.clear()

        try {
            clickHouseClient.batchInsert(snapshot)
            meterRegistry.counter("etl.flushes.success").increment()
            logger.info("Flushed {} records to ClickHouse", snapshot.size)
        } catch (e: Exception) {
            logger.error("Failed to flush {} records to ClickHouse, re-buffering", snapshot.size, e)
            buffer.addAll(snapshot)
            meterRegistry.counter("etl.flushes.errors").increment()
        }
    }

    private fun parseEvent(message: String): TransferAnalyticsRecord {
        val node = objectMapper.readValue<Map<String, Any?>>(message)

        return TransferAnalyticsRecord(
            transferId = UUID.fromString(node["transferId"] as String),
            senderId = UUID.fromString(node["senderId"] as String),
            sourceCountry = node["sourceCountry"] as String,
            destCountry = node["destCountry"] as String,
            corridor = node["corridor"] as String,
            sendAmount = BigDecimal(node["sendAmount"].toString()),
            sendCurrency = node["sendCurrency"] as String,
            receiveAmount = BigDecimal(node["receiveAmount"].toString()),
            receiveCurrency = node["receiveCurrency"] as String,
            exchangeRate = BigDecimal(node["exchangeRate"].toString()),
            feeAmount = BigDecimal(node["feeAmount"].toString()),
            feeCurrency = node["feeCurrency"] as String,
            deliveryMethod = node["deliveryMethod"] as String,
            status = node["status"] as String,
            createdAt = Instant.parse(node["createdAt"] as String),
            updatedAt = Instant.parse(node["updatedAt"] as String)
        )
    }
}
