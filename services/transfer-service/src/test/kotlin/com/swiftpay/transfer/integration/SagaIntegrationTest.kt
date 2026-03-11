package com.swiftpay.transfer.integration

import com.ninjasquad.springmockk.MockkBean
import com.swiftpay.transfer.IntegrationTestBase
import com.swiftpay.transfer.client.IdentityClient
import com.swiftpay.transfer.client.KycStatus
import com.swiftpay.transfer.client.PricingClient
import com.swiftpay.transfer.client.QuoteData
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.domain.vo.OutboxEventType
import com.swiftpay.transfer.repository.ConsumedEventRepository
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.TransferRepository
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class SagaIntegrationTest : IntegrationTestBase() {

    @MockkBean
    private lateinit var pricingClient: PricingClient

    @MockkBean
    private lateinit var identityClient: IdentityClient

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var transferRepository: TransferRepository

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var consumedEventRepository: ConsumedEventRepository

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        every { identityClient.checkKyc(any()) } returns KycStatus(
            userId = senderId.toString(),
            status = "APPROVED",
            kycLevel = "FULL"
        )
        every { pricingClient.validateQuote(any()) } returns QuoteData(
            quoteId = "test-quote",
            sendAmount = BigDecimal("200.00"),
            receiveAmount = BigDecimal("11240.00"),
            exchangeRate = BigDecimal("56.20"),
            feeAmount = BigDecimal("5.99"),
            feeCurrency = "USD",
            sendCurrency = "USD",
            receiveCurrency = "PHP"
        )
    }

    @Test
    fun `happy path - full saga lifecycle from PAYMENT_PENDING to COMPLETED`() {
        val transferId = createTransferViaApi()

        // Verify initial status
        val initial = transferRepository.findTransferById(transferId)!!
        assertEquals(TransferStatus.PaymentPending, initial.status)

        // Step 1: Payment captured → PAYOUT_PENDING
        val paymentId = UUID.randomUUID()
        publishEvent("payments.payment.captured", transferId, """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "$paymentId",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent())

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.PayoutPending
        }

        // Verify outbox has PAYOUT_REQUESTED with correct topic
        val outboxAfterPayment = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transferId)
        val payoutOutbox = outboxAfterPayment.find { it.eventType == OutboxEventType.PAYOUT_REQUESTED }
        assertNotNull(payoutOutbox, "Expected PAYOUT_REQUESTED outbox event")
        assertEquals("transfers.payout.requested", payoutOutbox!!.targetTopic)

        // Step 2: Payout completed → COMPLETED
        publishEvent("payouts.payout.completed", transferId, """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYOUT_COMPLETED",
                "payout_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent())

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.Completed
        }

        val completed = transferRepository.findTransferById(transferId)!!
        assertEquals(TransferStatus.Completed, completed.status)
        assertNotNull(completed.completedAt, "completedAt should be set on COMPLETED")
    }

    @Test
    fun `payment failure - saga stops without payout`() {
        val transferId = createTransferViaApi()

        publishEvent("payments.payment.failed", transferId, """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYMENT_FAILED",
                "reason": "Card declined",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent())

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.PaymentFailed
        }

        val failed = transferRepository.findTransferById(transferId)!!
        assertEquals(TransferStatus.PaymentFailed, failed.status)
        assertEquals("Card declined", failed.statusReason)

        // No PAYOUT_REQUESTED should exist — saga was interrupted
        val outboxEvents = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transferId)
        val payoutOutbox = outboxEvents.find { it.eventType == OutboxEventType.PAYOUT_REQUESTED }
        assertNull(payoutOutbox, "No PAYOUT_REQUESTED event should exist when payment failed")
    }

    @Test
    fun `payout failure triggers refund compensation`() {
        val transferId = createTransferViaApi()

        // Step 1: Payment captured → PAYOUT_PENDING
        publishEvent("payments.payment.captured", transferId, """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent())

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.PayoutPending
        }

        // Step 2: Payout failed → REFUND_PENDING (via FAILED → REFUND_PENDING in one transaction)
        publishEvent("payouts.payout.failed", transferId, """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYOUT_FAILED",
                "reason": "Partner bank unavailable",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent())

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.RefundPending
        }

        // Verify outbox has REFUND_REQUESTED with correct topic
        val outboxEvents = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transferId)
        val refundOutbox = outboxEvents.find { it.eventType == OutboxEventType.REFUND_REQUESTED }
        assertNotNull(refundOutbox, "Expected REFUND_REQUESTED outbox event")
        assertEquals("transfers.payment.refund.requested", refundOutbox!!.targetTopic)

        // Step 3: Payment refunded → REFUNDED
        publishEvent("payments.payment.refunded", transferId, """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$transferId",
                "event_type": "PAYMENT_REFUNDED",
                "refund_id": "${UUID.randomUUID()}",
                "refunded_amount": "200.00",
                "refunded_at": "${Instant.now()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent())

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.Refunded
        }

        val refunded = transferRepository.findTransferById(transferId)!!
        assertEquals(TransferStatus.Refunded, refunded.status)
    }

    @Test
    fun `duplicate payment captured event is processed idempotently`() {
        val transferId = createTransferViaApi()

        val eventId = UUID.randomUUID().toString()
        val event = """
            {
                "event_id": "$eventId",
                "transfer_id": "$transferId",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        // Send same event twice
        publishEvent("payments.payment.captured", transferId, event)

        awaitCondition {
            transferRepository.findTransferById(transferId)?.status == TransferStatus.PayoutPending
        }

        publishEvent("payments.payment.captured", transferId, event)

        // Wait for the duplicate to be processed (or skipped)
        Thread.sleep(2000)

        // Status should still be PAYOUT_PENDING (not broken)
        val transfer = transferRepository.findTransferById(transferId)!!
        assertEquals(TransferStatus.PayoutPending, transfer.status)

        // Only ONE PAYOUT_REQUESTED outbox event
        val outboxEvents = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transferId)
        val payoutEvents = outboxEvents.filter { it.eventType == OutboxEventType.PAYOUT_REQUESTED }
        assertEquals(1, payoutEvents.size, "Expected exactly one PAYOUT_REQUESTED outbox event")

        // ConsumedEvent recorded once
        assertTrue(consumedEventRepository.existsByEventId(eventId))
        val consumed = consumedEventRepository.findById(eventId)
        assertTrue(consumed.isPresent)
    }

    // ================================================================
    // Helpers
    // ================================================================

    private fun createTransferViaApi(): UUID {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
            set("X-Sender-Id", senderId.toString())
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": 200.00,
                "send_currency": "USD",
                "receive_currency": "PHP",
                "source_country": "US",
                "dest_country": "PH"
            }
        """.trimIndent()

        val response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
        )

        assertEquals(HttpStatus.CREATED, response.statusCode, "Transfer creation should return 201")
        return UUID.fromString(response.body!!["id"] as String)
    }

    private fun publishEvent(topic: String, transferId: UUID, json: String) {
        kafkaTemplate.send(topic, transferId.toString(), json).get(5, TimeUnit.SECONDS)
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
