package com.swiftpay.transfer.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class NotificationRetryConsumer(
    private val notificationSender: NotificationSender,
    private val mainConsumer: NotificationDeliveryConsumer,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(NotificationRetryConsumer::class.java)

    private val dltCounter = meterRegistry.counter("kafka_dlt_messages_total", "topic", "notification.delivery")

    companion object {
        const val MAX_RETRIES = 5
        val RETRY_DELAYS: List<Duration> = listOf(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(1)
        )
    }

    @KafkaListener(
        topics = ["notification.delivery.retry"],
        groupId = "notification-retry-consumer",
        properties = ["max.poll.records=1"]
    )
    fun consumeRetry(record: ConsumerRecord<String, String>) {
        val event = try {
            objectMapper.readValue(record.value(), NotificationEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize retry event, sending to DLT: {}", record.value(), e)
            sendToDlt(record, 0, "deserialization failure")
            return
        }

        val retryCount = record.headers()
            .lastHeader("retry-count")
            ?.let { String(it.value()).toInt() } ?: 0

        if (retryCount > 0 && retryCount < RETRY_DELAYS.size) {
            val delay = RETRY_DELAYS[retryCount - 1]
            log.info("Retry #{} for transfer: {}, waiting {}s", retryCount, event.transferId, delay.seconds)
            Thread.sleep(delay.toMillis().coerceAtMost(5000))
        }

        try {
            notificationSender.send(event)
            log.info("Retry successful for transfer: {}", event.transferId)
            mainConsumer.clearRedirect(event.transferId)
        } catch (e: Exception) {
            val newRetryCount = retryCount + 1
            if (newRetryCount >= MAX_RETRIES) {
                log.error("Max retries exhausted for transfer: {}, sending to DLT", event.transferId, e)
                sendToDlt(record, newRetryCount, "max retries exhausted")
                mainConsumer.clearRedirect(event.transferId)
            } else {
                log.warn("Retry #{} failed for transfer: {}, re-queuing", newRetryCount, event.transferId, e)
                requeue(record, newRetryCount)
            }
        }
    }

    private fun requeue(record: ConsumerRecord<String, String>, retryCount: Int) {
        val retryRecord = ProducerRecord(
            "notification.delivery.retry",
            record.key(),
            record.value()
        )
        retryRecord.headers().add("retry-count", retryCount.toString().toByteArray())
        kafkaTemplate.send(retryRecord)
    }

    private fun sendToDlt(record: ConsumerRecord<String, String>, retryCount: Int, reason: String) {
        val dltRecord = ProducerRecord(
            "notification.delivery.dlt",
            record.key(),
            record.value()
        )
        dltRecord.headers()
            .add("retry-count", retryCount.toString().toByteArray())
            .add("failure-reason", reason.toByteArray())
        kafkaTemplate.send(dltRecord)
        dltCounter.increment()
    }
}
