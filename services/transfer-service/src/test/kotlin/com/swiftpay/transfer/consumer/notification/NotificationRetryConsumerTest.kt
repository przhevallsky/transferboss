package com.swiftpay.transfer.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.CompletableFuture

@ExtendWith(MockKExtension::class)
class NotificationRetryConsumerTest {

    private val notificationSender: NotificationSender = mockk()
    private val mainConsumer: NotificationDeliveryConsumer = mockk(relaxed = true)
    private val kafkaTemplate: KafkaTemplate<String, String> = mockk()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var consumer: NotificationRetryConsumer

    private val transferId = "txn-001"
    private val eventPayload = objectMapper.writeValueAsString(
        NotificationEvent(
            transferId = transferId,
            senderId = "user-1",
            eventType = "TRANSFER_COMPLETED",
            notificationText = "Your transfer is complete",
            channels = listOf("push", "email")
        )
    )

    @BeforeEach
    fun setup() {
        consumer = NotificationRetryConsumer(
            notificationSender = notificationSender,
            mainConsumer = mainConsumer,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry
        )
        every { kafkaTemplate.send(any<ProducerRecord<String, String>>()) } returns CompletableFuture()
    }

    private fun record(retryCount: Int = 0, value: String = eventPayload): ConsumerRecord<String, String> {
        val record = ConsumerRecord("notification.delivery.retry", 0, 0L, transferId, value)
        record.headers().add("retry-count", retryCount.toString().toByteArray())
        return record
    }

    @Test
    fun `should deliver and clear redirect on successful retry`() {
        every { notificationSender.send(any()) } just Runs

        consumer.consumeRetry(record(retryCount = 0))

        verify(exactly = 1) { notificationSender.send(match { it.transferId == transferId }) }
        verify(exactly = 1) { mainConsumer.clearRedirect(transferId) }
        verify(exactly = 0) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
    }

    @Test
    fun `should requeue with incremented retry count on failure`() {
        every { notificationSender.send(any()) } throws RuntimeException("still failing")

        val captured = slot<ProducerRecord<String, String>>()
        every { kafkaTemplate.send(capture(captured)) } returns CompletableFuture()

        consumer.consumeRetry(record(retryCount = 1))

        assertEquals("notification.delivery.retry", captured.captured.topic())
        val retryHeader = captured.captured.headers().lastHeader("retry-count")
        assertNotNull(retryHeader)
        assertEquals("2", String(retryHeader!!.value()))
        verify(exactly = 0) { mainConsumer.clearRedirect(any()) }
    }

    @Test
    fun `should send to DLT when max retries exhausted`() {
        every { notificationSender.send(any()) } throws RuntimeException("permanent failure")

        val captured = slot<ProducerRecord<String, String>>()
        every { kafkaTemplate.send(capture(captured)) } returns CompletableFuture()

        consumer.consumeRetry(record(retryCount = 4)) // 4 → newRetryCount=5 >= MAX_RETRIES

        assertEquals("notification.delivery.dlt", captured.captured.topic())
        val reasonHeader = captured.captured.headers().lastHeader("failure-reason")
        assertNotNull(reasonHeader)
        assertEquals("max retries exhausted", String(reasonHeader!!.value()))
        verify(exactly = 1) { mainConsumer.clearRedirect(transferId) }
    }

    @Test
    fun `should clear redirect after DLT`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        consumer.consumeRetry(record(retryCount = 4))

        verify(exactly = 1) { mainConsumer.clearRedirect(transferId) }
    }

    @Test
    fun `should increment DLT counter when sending to DLT`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        consumer.consumeRetry(record(retryCount = 4))

        val counter = meterRegistry.find("kafka_dlt_messages_total").counter()
        assertNotNull(counter)
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `should send malformed event to DLT`() {
        val captured = slot<ProducerRecord<String, String>>()
        every { kafkaTemplate.send(capture(captured)) } returns CompletableFuture()

        consumer.consumeRetry(record(value = "invalid-json"))

        assertEquals("notification.delivery.dlt", captured.captured.topic())
        verify(exactly = 0) { notificationSender.send(any()) }
    }

    @Test
    fun `should handle missing retry-count header`() {
        every { notificationSender.send(any()) } just Runs

        val record = ConsumerRecord("notification.delivery.retry", 0, 0L, transferId, eventPayload)
        // No retry-count header

        consumer.consumeRetry(record)

        verify(exactly = 1) { notificationSender.send(any()) }
        verify(exactly = 1) { mainConsumer.clearRedirect(transferId) }
    }

    @Test
    fun `should not requeue when retry count is just below max`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        val captured = slot<ProducerRecord<String, String>>()
        every { kafkaTemplate.send(capture(captured)) } returns CompletableFuture()

        consumer.consumeRetry(record(retryCount = 3)) // 3 → newRetryCount=4 < MAX_RETRIES

        assertEquals("notification.delivery.retry", captured.captured.topic())
        assertEquals("4", String(captured.captured.headers().lastHeader("retry-count")!!.value()))
        verify(exactly = 0) { mainConsumer.clearRedirect(any()) }
    }
}
