package com.swiftpay.transfer.consumer.notification

import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

@Component
class NotificationDltConsumer(
    meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(NotificationDltConsumer::class.java)

    private val dltReceivedCounter = meterRegistry.counter("notification.delivery.dlt.received.total")

    @KafkaListener(
        topics = ["notification.delivery.dlt"],
        groupId = "notification-dlt-consumer"
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val retryCount = record.headers()
            .lastHeader("retry-count")
            ?.let { String(it.value()) } ?: "unknown"
        val failureReason = record.headers()
            .lastHeader("failure-reason")
            ?.let { String(it.value()) } ?: "unknown"

        log.error(
            "DLT notification event: key={}, retryCount={}, reason={}, value={}",
            record.key(), retryCount, failureReason, record.value()
        )
        dltReceivedCounter.increment()
    }
}
