package com.swiftpay.outbox.publisher

import com.swiftpay.outbox.config.OutboxProperties
import com.swiftpay.outbox.domain.OutboxEvent
import com.swiftpay.outbox.repository.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class OutboxPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val repository: OutboxEventRepository,
    private val properties: OutboxProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun publish(events: List<OutboxEvent>) {
        val grouped = events.groupBy { it.entityId }

        grouped.forEach { (entityId, entityEvents) ->
            entityEvents.forEach { event ->
                try {
                    val topic = event.targetTopic ?: properties.targetTopic
                    val result = kafkaTemplate.send(
                        topic,
                        entityId.toString(),
                        event.payload
                    ).get()

                    val metadata = result.recordMetadata
                    repository.markSent(
                        id = event.id,
                        processedAt = Instant.now(),
                        topic = metadata.topic(),
                        offset = metadata.offset()
                    )
                    logger.debug("Published event {} to {}@{}", event.id, metadata.topic(), metadata.offset())
                } catch (ex: Exception) {
                    repository.markFailed(event.id, Instant.now())
                    logger.error("Failed to publish event {}: {}", event.id, ex.message, ex)
                }
            }
        }
    }
}
