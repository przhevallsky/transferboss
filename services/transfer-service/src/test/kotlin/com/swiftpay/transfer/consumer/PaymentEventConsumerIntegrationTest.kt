package com.swiftpay.transfer.consumer

import com.swiftpay.transfer.IntegrationTestBase
import com.swiftpay.transfer.domain.model.Transfer
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.domain.vo.OutboxEventType
import com.swiftpay.transfer.repository.ConsumedEventRepository
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.TransferRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class PaymentEventConsumerIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var transferRepository: TransferRepository

    @Autowired
    private lateinit var redisTemplate: RedisTemplate<String, String>

    @Autowired
    private lateinit var consumedEventRepository: ConsumedEventRepository

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    // Use sender/recipient from V006 seed data
    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `should transition transfer to PAYOUT_PENDING and write outbox event on PAYMENT_CAPTURED`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)
        val cacheKey = "transfer:status:${transfer.id}"
        redisTemplate.opsForValue().set(cacheKey, "cached-data")

        val paymentId = UUID.randomUUID()
        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "$paymentId",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payments.payment.captured", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.PayoutPending
        }

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.PayoutPending, updated.status)
        assertNotNull(updated.paymentId)

        // Verify outbox event was written
        val outboxEvents = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transfer.id)
        val payoutOutbox = outboxEvents.find { it.eventType == OutboxEventType.PAYOUT_REQUESTED }
        assertNotNull(payoutOutbox)
        assertEquals("transfers.payout.requested", payoutOutbox!!.targetTopic)

        assertNull(redisTemplate.opsForValue().get(cacheKey))
    }

    @Test
    fun `should update transfer status to PAYMENT_FAILED on failed payment event`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)

        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYMENT_FAILED",
                "reason": "Insufficient funds",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payments.payment.failed", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.PaymentFailed
        }

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.PaymentFailed, updated.status)
        assertEquals("Insufficient funds", updated.statusReason)
    }

    @Test
    fun `should transition transfer to REFUNDED on payment refunded event`() {
        val transfer = createTransferWithStatus(TransferStatus.RefundPending)

        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYMENT_REFUNDED",
                "refund_id": "${UUID.randomUUID()}",
                "refunded_amount": "200.00",
                "refunded_at": "${Instant.now()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payments.payment.refunded", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.Refunded
        }

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.Refunded, updated.status)
    }

    @Test
    fun `should skip duplicate event and not update transfer twice`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)
        val eventId = UUID.randomUUID().toString()
        val paymentId = UUID.randomUUID()

        val event = """
            {
                "event_id": "$eventId",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "$paymentId",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        // Send the same event twice
        kafkaTemplate.send("payments.payment.captured", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.PayoutPending
        }

        kafkaTemplate.send("payments.payment.captured", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        // Wait a bit for the second event to be processed (or skipped)
        Thread.sleep(2000)

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.PayoutPending, updated.status)

        // Verify consumed_events has exactly one record for this event
        assertTrue(consumedEventRepository.existsByEventId(eventId))
        val consumedEvent = consumedEventRepository.findById(eventId)
        assertTrue(consumedEvent.isPresent)
        assertEquals("transfer-service", consumedEvent.get().consumerGroup)
        assertEquals("payments.payment.captured", consumedEvent.get().topic)
    }

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

    private fun awaitCondition(timeoutMs: Long = 10_000, intervalMs: Long = 200, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        fail<Unit>("Condition not met within ${timeoutMs}ms")
    }
}
