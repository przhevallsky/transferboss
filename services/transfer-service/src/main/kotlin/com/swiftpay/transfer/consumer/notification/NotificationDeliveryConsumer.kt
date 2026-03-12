package com.swiftpay.transfer.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Component
class NotificationDeliveryConsumer(
    private val notificationSender: NotificationSender,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(NotificationDeliveryConsumer::class.java)

    private val redirectSet = ConcurrentHashMap<String, Boolean>()

    private val redirectCounter = meterRegistry.counter("notification.delivery.redirect.total")

    @KafkaListener(
        topics = ["notification.delivery"],
        groupId = "notification-delivery-consumer"
    )
    fun consume(record: ConsumerRecord<String, String>) {
        val event = try {
            objectMapper.readValue(record.value(), NotificationEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize notification event, skipping: {}", record.value(), e)
            return
        }

        val transferId = event.transferId

        if (redirectSet.containsKey(transferId)) {
            log.info("Transfer {} is in redirect set, sending to retry topic", transferId)
            redirectCounter.increment()
            sendToRetryTopic(record, 0)
            return
        }

        try {
            notificationSender.send(event)
            log.info("Notification delivered for transfer: {}, event: {}", transferId, event.eventType)
        } catch (e: Exception) {
            log.warn("Delivery failed for transfer: {}, redirecting", transferId, e)
            redirectSet[transferId] = true
            redirectCounter.increment()
            sendToRetryTopic(record, 0)
        }
    }

    private fun sendToRetryTopic(record: ConsumerRecord<String, String>, retryCount: Int) {
        val retryRecord = ProducerRecord(
            "notification.delivery.retry",
            record.key(),
            record.value()
        )
        retryRecord.headers()
            .add("retry-count", retryCount.toString().toByteArray())
            .add("original-timestamp", Instant.now().toString().toByteArray())
        kafkaTemplate.send(retryRecord)
    }

    fun clearRedirect(transferId: String) {
        redirectSet.remove(transferId)
        log.info("Cleared redirect for transfer: {}", transferId)
    }

    fun isRedirected(transferId: String): Boolean = redirectSet.containsKey(transferId)
}
