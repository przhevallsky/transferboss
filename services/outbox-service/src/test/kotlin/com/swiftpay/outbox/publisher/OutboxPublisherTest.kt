package com.swiftpay.outbox.publisher

import com.swiftpay.outbox.config.OutboxProperties
import com.swiftpay.outbox.domain.OutboxEvent
import com.swiftpay.outbox.repository.OutboxEventRepository
import io.kotest.matchers.shouldBe
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.UUID
import java.util.concurrent.CompletableFuture

@ExtendWith(MockKExtension::class)
class OutboxPublisherTest {

    private val kafkaTemplate: KafkaTemplate<String, String> = mockk()
    private val repository: OutboxEventRepository = mockk(relaxed = true)
    private val properties = OutboxProperties(targetTopic = "transfer.events")

    private lateinit var publisher: OutboxPublisher

    @BeforeEach
    fun setup() {
        publisher = OutboxPublisher(kafkaTemplate, repository, properties)
    }

    private fun outboxEvent(
        entityId: UUID = UUID.randomUUID(),
        payload: String = """{"transfer_id":"${UUID.randomUUID()}"}"""
    ) = OutboxEvent(
        id = UUID.randomUUID(),
        entityType = "TRANSFER",
        entityId = entityId,
        eventType = "TRANSFER_CREATED",
        payload = payload
    )

    private fun mockSendSuccess(topic: String = "transfer.events", offset: Long = 42): CompletableFuture<SendResult<String, String>> {
        val metadata = RecordMetadata(TopicPartition(topic, 0), offset, 0, 0L, 0, 0)
        val sendResult = mockk<SendResult<String, String>>()
        every { sendResult.recordMetadata } returns metadata
        return CompletableFuture.completedFuture(sendResult)
    }

    private fun mockSendFailure(ex: Exception = RuntimeException("Kafka unavailable")): CompletableFuture<SendResult<String, String>> {
        val future = CompletableFuture<SendResult<String, String>>()
        future.completeExceptionally(ex)
        return future
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `should send event to Kafka and mark as sent`() {
            val event = outboxEvent()
            every { kafkaTemplate.send("transfer.events", event.entityId.toString(), event.payload) } returns mockSendSuccess(offset = 100)

            publisher.publish(listOf(event))

            verify { kafkaTemplate.send("transfer.events", event.entityId.toString(), event.payload) }
            verify { repository.markSent(event.id, any(), "transfer.events", 100) }
        }

        @Test
        fun `should publish multiple events`() {
            val event1 = outboxEvent()
            val event2 = outboxEvent()
            every { kafkaTemplate.send("transfer.events", event1.entityId.toString(), event1.payload) } returns mockSendSuccess(offset = 10)
            every { kafkaTemplate.send("transfer.events", event2.entityId.toString(), event2.payload) } returns mockSendSuccess(offset = 11)

            publisher.publish(listOf(event1, event2))

            verify { repository.markSent(event1.id, any(), "transfer.events", 10) }
            verify { repository.markSent(event2.id, any(), "transfer.events", 11) }
        }
    }

    @Nested
    inner class KafkaFailure {

        @Test
        fun `should mark event as failed when Kafka send throws`() {
            val event = outboxEvent()
            every { kafkaTemplate.send("transfer.events", event.entityId.toString(), event.payload) } returns mockSendFailure()

            publisher.publish(listOf(event))

            verify { repository.markFailed(event.id, any()) }
            verify(exactly = 0) { repository.markSent(any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class Grouping {

        @Test
        fun `should group events by entityId and process in order`() {
            val entityId = UUID.randomUUID()
            val event1 = outboxEvent(entityId = entityId)
            val event2 = outboxEvent(entityId = entityId)

            every { kafkaTemplate.send("transfer.events", entityId.toString(), event1.payload) } returns mockSendSuccess(offset = 50)
            every { kafkaTemplate.send("transfer.events", entityId.toString(), event2.payload) } returns mockSendSuccess(offset = 51)

            publisher.publish(listOf(event1, event2))

            verifyOrder {
                kafkaTemplate.send("transfer.events", entityId.toString(), event1.payload)
                kafkaTemplate.send("transfer.events", entityId.toString(), event2.payload)
            }
            verify { repository.markSent(event1.id, any(), "transfer.events", 50) }
            verify { repository.markSent(event2.id, any(), "transfer.events", 51) }
        }
    }

    @Nested
    inner class MixedResults {

        @Test
        fun `should mark sent and failed independently for each event`() {
            val event1 = outboxEvent()
            val event2 = outboxEvent()
            every { kafkaTemplate.send("transfer.events", event1.entityId.toString(), event1.payload) } returns mockSendSuccess(offset = 20)
            every { kafkaTemplate.send("transfer.events", event2.entityId.toString(), event2.payload) } returns mockSendFailure()

            publisher.publish(listOf(event1, event2))

            verify { repository.markSent(event1.id, any(), "transfer.events", 20) }
            verify { repository.markFailed(event2.id, any()) }
        }
    }
}
