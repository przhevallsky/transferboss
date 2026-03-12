package com.swiftpay.transfer.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.CompletableFuture

@ExtendWith(MockKExtension::class)
class NotificationDeliveryConsumerTest {

    private val notificationSender: NotificationSender = mockk()
    private val kafkaTemplate: KafkaTemplate<String, String> = mockk()
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val meterRegistry = SimpleMeterRegistry()

    private lateinit var consumer: NotificationDeliveryConsumer

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
        consumer = NotificationDeliveryConsumer(
            notificationSender = notificationSender,
            kafkaTemplate = kafkaTemplate,
            objectMapper = objectMapper,
            meterRegistry = meterRegistry
        )
        every { kafkaTemplate.send(any<ProducerRecord<String, String>>()) } returns CompletableFuture()
    }

    private fun record(key: String = transferId, value: String = eventPayload) =
        ConsumerRecord("notification.delivery", 0, 0L, key, value)

    @Test
    fun `should deliver notification on happy path`() {
        every { notificationSender.send(any()) } just Runs

        consumer.consume(record())

        verify(exactly = 1) { notificationSender.send(match { it.transferId == transferId }) }
        verify(exactly = 0) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
        assertFalse(consumer.isRedirected(transferId))
    }

    @Test
    fun `should add to redirect set and send to retry topic on failure`() {
        every { notificationSender.send(any()) } throws RuntimeException("Gateway timeout")

        consumer.consume(record())

        assertTrue(consumer.isRedirected(transferId))
        verify(exactly = 1) {
            kafkaTemplate.send(match<ProducerRecord<String, String>> {
                it.topic() == "notification.delivery.retry"
            })
        }
    }

    @Test
    fun `should redirect subsequent events for same transfer_id`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        // First event fails → adds to redirect set
        consumer.consume(record())

        assertTrue(consumer.isRedirected(transferId))

        // Second event for same transfer → redirected without calling sender
        consumer.consume(record())

        // sender called only once (first attempt), not on the redirect
        verify(exactly = 1) { notificationSender.send(any()) }
        // retry topic called twice: once on failure, once on redirect
        verify(exactly = 2) {
            kafkaTemplate.send(match<ProducerRecord<String, String>> {
                it.topic() == "notification.delivery.retry"
            })
        }
    }

    @Test
    fun `should NOT redirect events for different transfer_id`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail") andThen Unit

        // Fail for transferId
        consumer.consume(record())

        val otherPayload = objectMapper.writeValueAsString(
            NotificationEvent(
                transferId = "txn-other",
                senderId = "user-2",
                eventType = "TRANSFER_CREATED",
                notificationText = "Transfer created",
                channels = listOf("push")
            )
        )

        clearMocks(notificationSender, answers = false)
        every { notificationSender.send(any()) } just Runs

        consumer.consume(record(key = "txn-other", value = otherPayload))

        verify(exactly = 1) { notificationSender.send(match { it.transferId == "txn-other" }) }
        assertFalse(consumer.isRedirected("txn-other"))
        assertTrue(consumer.isRedirected(transferId))
    }

    @Test
    fun `should clear redirect set when clearRedirect called`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")
        consumer.consume(record())

        assertTrue(consumer.isRedirected(transferId))

        consumer.clearRedirect(transferId)

        assertFalse(consumer.isRedirected(transferId))
    }

    @Test
    fun `should include retry-count header in retry message`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        val capturedRecord = slot<ProducerRecord<String, String>>()
        every { kafkaTemplate.send(capture(capturedRecord)) } returns CompletableFuture()

        consumer.consume(record())

        val headers = capturedRecord.captured.headers()
        val retryCount = headers.lastHeader("retry-count")
        assertNotNull(retryCount)
        assertEquals("0", String(retryCount!!.value()))

        val timestamp = headers.lastHeader("original-timestamp")
        assertNotNull(timestamp)
    }

    @Test
    fun `should skip malformed event without throwing`() {
        consumer.consume(record(value = "not-valid-json"))

        verify(exactly = 0) { notificationSender.send(any()) }
        verify(exactly = 0) { kafkaTemplate.send(any<ProducerRecord<String, String>>()) }
    }

    @Test
    fun `should increment redirect counter on failure`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        consumer.consume(record())

        val counter = meterRegistry.find("notification.delivery.redirect.total").counter()
        assertNotNull(counter)
        assertEquals(1.0, counter!!.count())
    }

    @Test
    fun `should increment redirect counter on redirect`() {
        every { notificationSender.send(any()) } throws RuntimeException("fail")

        consumer.consume(record())
        consumer.consume(record()) // redirected

        val counter = meterRegistry.find("notification.delivery.redirect.total").counter()
        assertEquals(2.0, counter!!.count())
    }
}
