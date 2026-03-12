package com.swiftpay.transfer.sse

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.IntegrationTestBase
import com.swiftpay.transfer.domain.model.Transfer
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.repository.TransferRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class SseIntegrationTest : IntegrationTestBase() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var transferRepository: TransferRepository

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var webTestClient: WebTestClient

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        webTestClient = WebTestClient.bindToServer()
            .baseUrl("http://localhost:$port")
            .responseTimeout(Duration.ofSeconds(30))
            .build()
    }

    @Test
    fun `should receive initial status event on SSE connect`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)

        val events = webTestClient.get()
            .uri("/api/v1/transfers/${transfer.id}/events")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody
            .take(1)
            .collectList()
            .block(Duration.ofSeconds(10))

        assertNotNull(events)
        assertEquals(1, events!!.size)

        val node = objectMapper.readTree(events[0])
        assertEquals(transfer.id.toString(), node.get("transferId").asText())
        assertEquals("PAYMENT_PENDING", node.get("status").asText())
    }

    @Test
    fun `should receive live status update via Kafka event`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)

        // Take initial + at least one live update (PAYMENT_CAPTURED or PAYOUT_PENDING)
        val eventFlux = webTestClient.get()
            .uri("/api/v1/transfers/${transfer.id}/events")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody

        // Subscribe and collect events asynchronously
        val events = mutableListOf<String>()
        val disposable = eventFlux.subscribe { events.add(it) }

        try {
            // Wait for initial event
            awaitCondition { events.size >= 1 }

            // Publish Kafka payment captured event
            publishPaymentCaptured(transfer.id)

            // Wait for Kafka consumer to process → Redis pub/sub → SSE
            // We expect PAYMENT_CAPTURED and PAYOUT_PENDING (secondary transition)
            awaitCondition(timeoutMs = 15_000) { events.size >= 2 }

            // Verify initial event
            val initialNode = objectMapper.readTree(events[0])
            assertEquals("PAYMENT_PENDING", initialNode.get("status").asText())

            // Verify at least one live update arrived
            val liveUpdates = events.drop(1)
            assertTrue(liveUpdates.isNotEmpty(), "Expected at least one live status update")

            val statuses = liveUpdates.map { objectMapper.readTree(it).get("status").asText() }
            assertTrue(
                statuses.contains("PAYMENT_CAPTURED") || statuses.contains("PAYOUT_PENDING"),
                "Expected PAYMENT_CAPTURED or PAYOUT_PENDING in live updates, got: $statuses"
            )
        } finally {
            disposable.dispose()
        }
    }

    @Test
    fun `should receive multiple status updates in sequence`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)

        val events = mutableListOf<String>()
        val eventFlux = webTestClient.get()
            .uri("/api/v1/transfers/${transfer.id}/events")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk
            .returnResult(String::class.java)
            .responseBody

        val disposable = eventFlux.subscribe { events.add(it) }

        try {
            // Wait for initial event
            awaitCondition { events.size >= 1 }

            // Step 1: Payment captured → PAYOUT_PENDING
            publishPaymentCaptured(transfer.id)

            awaitCondition(timeoutMs = 15_000) {
                transferRepository.findTransferById(transfer.id)?.status == TransferStatus.PayoutPending
            }

            // Wait for SSE events from this transition
            awaitCondition(timeoutMs = 5_000) { events.size >= 3 }

            // Step 2: Payout completed → COMPLETED
            publishPayoutCompleted(transfer.id)

            awaitCondition(timeoutMs = 15_000) {
                transferRepository.findTransferById(transfer.id)?.status == TransferStatus.Completed
            }

            // Wait for SSE event from payout completed
            awaitCondition(timeoutMs = 5_000) { events.size >= 4 }

            // Verify the sequence of statuses
            val statuses = events.map { objectMapper.readTree(it).get("status").asText() }

            assertEquals("PAYMENT_PENDING", statuses[0], "First event should be initial status")
            // Subsequent events should contain the full transition chain
            val liveStatuses = statuses.drop(1)
            assertTrue(liveStatuses.contains("PAYOUT_PENDING"), "Should contain PAYOUT_PENDING, got: $statuses")
            assertTrue(liveStatuses.contains("COMPLETED"), "Should contain COMPLETED, got: $statuses")

            // COMPLETED should come after PAYOUT_PENDING
            val payoutIdx = liveStatuses.indexOf("PAYOUT_PENDING")
            val completedIdx = liveStatuses.indexOf("COMPLETED")
            assertTrue(completedIdx > payoutIdx, "COMPLETED should come after PAYOUT_PENDING")
        } finally {
            disposable.dispose()
        }
    }

    @Test
    fun `should return 404 for non-existent transfer`() {
        val randomId = UUID.randomUUID()

        webTestClient.get()
            .uri("/api/v1/transfers/$randomId/events")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isNotFound
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun createTransferWithStatus(status: TransferStatus): Transfer {
        val transfer = Transfer(
            idempotencyKey = UUID.randomUUID(),
            senderId = senderId,
            quoteId = UUID.randomUUID(),
            sendAmount = BigDecimal("200.00"),
            sendCurrency = "USD",
            receiveAmount = BigDecimal("11000.00"),
            receiveCurrency = "PHP",
            exchangeRate = BigDecimal("55.000000"),
            feeAmount = BigDecimal("5.00"),
            feeCurrency = "USD",
            sourceCountry = "US",
            destCountry = "PH",
            deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
            recipientId = recipientId,
            status = status
        )
        return transferRepository.save(transfer)
    }

    private fun publishPaymentCaptured(transferId: UUID) {
        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()
        kafkaTemplate.send("payments.payment.captured", transferId.toString(), event).get(5, TimeUnit.SECONDS)
    }

    private fun publishPayoutCompleted(transferId: UUID) {
        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYOUT_COMPLETED",
                "payout_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()
        kafkaTemplate.send("payouts.payout.completed", transferId.toString(), event).get(5, TimeUnit.SECONDS)
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
