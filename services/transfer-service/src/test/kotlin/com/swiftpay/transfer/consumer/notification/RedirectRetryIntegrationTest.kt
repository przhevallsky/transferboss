package com.swiftpay.transfer.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.IntegrationTestBase
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Import(RedirectRetryIntegrationTest.TestSenderConfig::class)
class RedirectRetryIntegrationTest : IntegrationTestBase() {

    @TestConfiguration
    class TestSenderConfig {
        @Bean
        @Primary
        fun orderTrackingNotificationSender(): NotificationSender = OrderTrackingNotificationSender()
    }

    class OrderTrackingNotificationSender : NotificationSender {
        val deliveredEvents = CopyOnWriteArrayList<NotificationEvent>()
        val shouldFail = AtomicBoolean(false)

        override fun send(event: NotificationEvent) {
            if (shouldFail.get()) throw RuntimeException("Simulated failure")
            deliveredEvents.add(event)
        }
    }

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var notificationSender: NotificationSender

    @Autowired
    private lateinit var deliveryConsumer: NotificationDeliveryConsumer

    @Autowired
    private lateinit var embeddedKafka: EmbeddedKafkaBroker

    private val sender get() = notificationSender as OrderTrackingNotificationSender

    @BeforeEach
    fun resetSender() {
        sender.deliveredEvents.clear()
        sender.shouldFail.set(false)
    }

    private fun notificationJson(transferId: String, eventType: String): String =
        objectMapper.writeValueAsString(
            NotificationEvent(
                transferId = transferId,
                senderId = "user-1",
                eventType = eventType,
                notificationText = "Test notification: $eventType",
                channels = listOf("push")
            )
        )

    @Test
    fun `should deliver notification directly on happy path`() {
        val transferId = "transfer_${UUID.randomUUID()}"
        val json = notificationJson(transferId, "TRANSFER_COMPLETED")

        kafkaTemplate.send("notification.delivery", transferId, json).get(5, TimeUnit.SECONDS)

        awaitCondition { sender.deliveredEvents.size >= 1 }

        assertEquals(1, sender.deliveredEvents.size)
        assertEquals(transferId, sender.deliveredEvents[0].transferId)
        assertEquals("TRANSFER_COMPLETED", sender.deliveredEvents[0].eventType)
        assertFalse(deliveryConsumer.isRedirected(transferId))
    }

    @Test
    fun `should preserve ordering when redirect and retry occur`() {
        val transferId = "ordering_${UUID.randomUUID()}"
        sender.shouldFail.set(true)

        // Event A fails → redirect set + retry topic
        val eventA = notificationJson(transferId, "PAYMENT_CAPTURED")
        kafkaTemplate.send("notification.delivery", transferId, eventA).get(5, TimeUnit.SECONDS)

        awaitCondition { deliveryConsumer.isRedirected(transferId) }

        // Event B for same transfer → redirected without calling sender
        val eventB = notificationJson(transferId, "COMPLETED")
        kafkaTemplate.send("notification.delivery", transferId, eventB).get(5, TimeUnit.SECONDS)

        // Wait for Event B to be redirected to retry topic
        Thread.sleep(2000)

        // Now allow delivery to succeed
        sender.shouldFail.set(false)

        // Wait for retry consumer to deliver both events in order
        awaitCondition(timeoutMs = 45_000) { sender.deliveredEvents.size >= 2 }

        // Verify ordering: Event A (PAYMENT_CAPTURED) before Event B (COMPLETED)
        assertEquals("PAYMENT_CAPTURED", sender.deliveredEvents[0].eventType,
            "Event A should be delivered first")
        assertEquals("COMPLETED", sender.deliveredEvents[1].eventType,
            "Event B should be delivered second")

        // Redirect should be cleared after successful retry
        awaitCondition { !deliveryConsumer.isRedirected(transferId) }
    }

    @Test
    fun `should not redirect events for different transfers`() {
        val transferId1 = "isolation_fail_${UUID.randomUUID()}"
        val transferId2 = "isolation_ok_${UUID.randomUUID()}"

        sender.shouldFail.set(true)

        // Event for transferId1 fails → redirect
        kafkaTemplate.send("notification.delivery", transferId1,
            notificationJson(transferId1, "PAYMENT_CAPTURED")).get(5, TimeUnit.SECONDS)

        awaitCondition { deliveryConsumer.isRedirected(transferId1) }

        // Verify transferId1 is in redirect set before we flip the flag
        assertTrue(deliveryConsumer.isRedirected(transferId1))

        // Now allow delivery to succeed for new events
        sender.shouldFail.set(false)

        // Event for transferId2 should be delivered directly (not redirected)
        kafkaTemplate.send("notification.delivery", transferId2,
            notificationJson(transferId2, "TRANSFER_COMPLETED")).get(5, TimeUnit.SECONDS)

        awaitCondition { sender.deliveredEvents.size >= 1 }

        // transferId2 was delivered directly
        val directlyDelivered = sender.deliveredEvents.find { it.transferId == transferId2 }
        assertNotNull(directlyDelivered, "transferId2 should be delivered directly")

        // transferId2 is NOT in redirect set
        assertFalse(deliveryConsumer.isRedirected(transferId2))
    }

    @Test
    fun `should send to DLT after max retries exhausted`() {
        val transferId = "transfer_${UUID.randomUUID()}"
        sender.shouldFail.set(true)

        val dltConsumer = createDltConsumer("notification.delivery.dlt")
        dltConsumer.subscribe(listOf("notification.delivery.dlt"))

        kafkaTemplate.send("notification.delivery", transferId,
            notificationJson(transferId, "PAYMENT_CAPTURED")).get(5, TimeUnit.SECONDS)

        // 5 retries × 5s cap = ~25s, plus processing time
        val records = pollUntilRecord(dltConsumer, timeoutMs = 60_000)
        assertTrue(records.count() > 0, "Expected message in notification DLT")

        val dltRecord = records.first()
        val dltEvent = objectMapper.readValue(dltRecord.value(), NotificationEvent::class.java)
        assertEquals(transferId, dltEvent.transferId)

        val failureReason = dltRecord.headers().lastHeader("failure-reason")
        assertNotNull(failureReason)
        assertEquals("max retries exhausted", String(failureReason!!.value()))

        // Redirect should be cleared after DLT (terminal)
        awaitCondition { !deliveryConsumer.isRedirected(transferId) }

        dltConsumer.close()
    }

    private fun createDltConsumer(topic: String): KafkaConsumer<String, String> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to embeddedKafka.brokersAsString,
            ConsumerConfig.GROUP_ID_CONFIG to "test-dlt-consumer-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name
        )
        return KafkaConsumer(props)
    }

    private fun pollUntilRecord(
        consumer: KafkaConsumer<String, String>,
        timeoutMs: Long = 15_000
    ): ConsumerRecords<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val records = consumer.poll(Duration.ofMillis(500))
            if (records.count() > 0) return records
        }
        throw AssertionError("No records received within ${timeoutMs}ms")
    }

    private fun awaitCondition(timeoutMs: Long = 10_000, intervalMs: Long = 200, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        fail<Unit>("Condition not met within ${timeoutMs}ms")
    }
}
