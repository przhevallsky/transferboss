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

class PayoutEventConsumerIntegrationTest : IntegrationTestBase() {

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
    fun `should update transfer status to COMPLETED on payout completed event`() {
        val transfer = createTransferWithStatus(TransferStatus.PayoutPending)
        val cacheKey = "transfer:status:${transfer.id}"
        redisTemplate.opsForValue().set(cacheKey, "cached-data")

        val payoutId = UUID.randomUUID()
        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYOUT_COMPLETED",
                "payout_id": "$payoutId",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payouts.payout.completed", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.Completed
        }

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.Completed, updated.status)
        assertNotNull(updated.payoutId)
        assertNotNull(updated.completedAt)

        assertNull(redisTemplate.opsForValue().get(cacheKey))
    }

    @Test
    fun `should transition to REFUND_PENDING and save refund outbox on payout failed event`() {
        val transfer = createTransferWithStatus(TransferStatus.PayoutPending)
        // Set paymentId as it would be after PAYMENT_CAPTURED
        transfer.paymentId = UUID.randomUUID()
        transferRepository.save(transfer)

        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYOUT_FAILED",
                "reason": "PARTNER_UNAVAILABLE",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payouts.payout.failed", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.RefundPending
        }

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.RefundPending, updated.status)

        // Verify outbox event was written
        val outboxEvents = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transfer.id)
        val refundOutbox = outboxEvents.find { it.eventType == OutboxEventType.REFUND_REQUESTED }
        assertNotNull(refundOutbox)
        assertEquals("transfers.payment.refund.requested", refundOutbox!!.targetTopic)
    }

    @Test
    fun `should skip duplicate payout event`() {
        val transfer = createTransferWithStatus(TransferStatus.PayoutPending)
        val eventId = UUID.randomUUID().toString()
        val payoutId = UUID.randomUUID()

        val event = """
            {
                "event_id": "$eventId",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYOUT_COMPLETED",
                "payout_id": "$payoutId",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        // Send the same event twice
        kafkaTemplate.send("payouts.payout.completed", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.Completed
        }

        kafkaTemplate.send("payouts.payout.completed", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        // Wait a bit for the second event to be processed (or skipped)
        Thread.sleep(2000)

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.Completed, updated.status)

        // Verify consumed_events has exactly one record for this event
        assertTrue(consumedEventRepository.existsByEventId(eventId))
        val consumedEvent = consumedEventRepository.findById(eventId)
        assertTrue(consumedEvent.isPresent)
        assertEquals("transfer-service", consumedEvent.get().consumerGroup)
        assertEquals("payouts.payout.completed", consumedEvent.get().topic)
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
