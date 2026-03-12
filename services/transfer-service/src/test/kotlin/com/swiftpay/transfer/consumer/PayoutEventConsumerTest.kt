package com.swiftpay.transfer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.swiftpay.transfer.domain.model.ConsumedEvent
import com.swiftpay.transfer.domain.model.OutboxEvent
import com.swiftpay.transfer.domain.model.Transfer
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.domain.vo.OutboxEventType
import com.swiftpay.transfer.repository.ConsumedEventRepository
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.TransferRepository
import com.swiftpay.transfer.service.TransferCacheService
import com.swiftpay.transfer.sse.TransferStatusPublisher
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import com.swiftpay.transfer.consumer.exception.NonRetriableConsumerException
import com.swiftpay.transfer.consumer.exception.TransientConsumerException

@ExtendWith(MockKExtension::class)
class PayoutEventConsumerTest {

    private val transferRepository: TransferRepository = mockk()
    private val transferCacheService: TransferCacheService = mockk(relaxed = true)
    private val consumedEventRepository: ConsumedEventRepository = mockk()
    private val outboxEventRepository: OutboxEventRepository = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val transferStatusPublisher: TransferStatusPublisher = mockk(relaxed = true)

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }

    private lateinit var consumer: PayoutEventConsumer

    private val transferId = UUID.randomUUID()
    private val eventId = "evt-${UUID.randomUUID()}"
    private val payoutId = UUID.randomUUID().toString()

    @BeforeEach
    fun setup() {
        consumer = PayoutEventConsumer(
            transferRepository = transferRepository,
            transferCacheService = transferCacheService,
            consumedEventRepository = consumedEventRepository,
            outboxEventRepository = outboxEventRepository,
            transactionTemplate = transactionTemplate,
            objectMapper = objectMapper,
            transferStatusPublisher = transferStatusPublisher,
            meterRegistry = SimpleMeterRegistry()
        )

        // Make TransactionTemplate actually execute the callback
        every { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) } answers {
            val callback = firstArg<TransactionCallback<Boolean>>()
            callback.doInTransaction(mockk())
        }
    }

    private fun createTransfer(
        status: TransferStatus = TransferStatus.PayoutPending,
        paymentId: UUID? = UUID.randomUUID()
    ): Transfer = Transfer(
        id = transferId,
        idempotencyKey = UUID.randomUUID(),
        senderId = UUID.randomUUID(),
        quoteId = UUID.randomUUID(),
        sendAmount = BigDecimal("100.00"),
        sendCurrency = "USD",
        receiveAmount = BigDecimal("5620.00"),
        receiveCurrency = "PHP",
        exchangeRate = BigDecimal("56.20"),
        feeAmount = BigDecimal("5.99"),
        feeCurrency = "USD",
        sourceCountry = "US",
        destCountry = "PH",
        deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
        recipientId = UUID.randomUUID(),
        status = status,
        paymentId = paymentId
    )

    private fun eventJson(
        eventType: String,
        transferId: String = this.transferId.toString(),
        eventId: String = this.eventId,
        payoutId: String? = this.payoutId,
        reason: String? = null
    ): String = objectMapper.writeValueAsString(
        PayoutEvent(
            eventId = eventId,
            transferId = transferId,
            eventType = eventType,
            payoutId = payoutId,
            reason = reason,
            timestamp = "2026-03-04T10:00:00Z"
        )
    )

    @Nested
    inner class PayoutCompleted {

        @Test
        fun `should transition transfer to COMPLETED and evict cache`() {
            val transfer = createTransfer()
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYOUT_COMPLETED"), "payouts.payout.completed")

            assertEquals(TransferStatus.Completed, transfer.status)
            verify { transferRepository.save(transfer) }
            verify { consumedEventRepository.save(match { it.eventId == eventId }) }
            verify { transferCacheService.evict(transferId) }
        }

        @Test
        fun `should set payoutId on transfer when present`() {
            val transfer = createTransfer()
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYOUT_COMPLETED"), "payouts.payout.completed")

            assertEquals(UUID.fromString(payoutId), transfer.payoutId)
        }
    }

    @Nested
    inner class PayoutFailed {

        @Test
        fun `should transition transfer to REFUND_PENDING and save refund outbox event`() {
            val transfer = createTransfer()
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYOUT_FAILED", reason = "PARTNER_UNAVAILABLE"), "payouts.payout.failed")

            assertEquals(TransferStatus.RefundPending, transfer.status)
            // statusReason is cleared by transitionTo(RefundPending)
            verify(exactly = 2) { transferRepository.save(transfer) }
            verify { outboxEventRepository.save(match {
                it.eventType == OutboxEventType.REFUND_REQUESTED &&
                    it.targetTopic == "transfers.payment.refund.requested"
            }) }
            verify { transferCacheService.evict(transferId) }
        }
    }

    @Nested
    inner class Idempotency {

        @Test
        fun `should skip processing when event already consumed`() {
            every { consumedEventRepository.existsByEventId(eventId) } returns true

            consumer.consume(eventJson("PAYOUT_COMPLETED"), "payouts.payout.completed")

            verify(exactly = 0) { transferRepository.findTransferById(any()) }
            verify(exactly = 0) { transferRepository.save(any()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `should throw NonRetriableConsumerException for unknown event type`() {
            assertThrows(NonRetriableConsumerException::class.java) {
                consumer.consume(eventJson("PAYOUT_REVERSED"), "payouts.payout.completed")
            }

            verify(exactly = 0) { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should throw NonRetriableConsumerException for invalid transferId format`() {
            assertThrows(NonRetriableConsumerException::class.java) {
                consumer.consume(eventJson("PAYOUT_COMPLETED", transferId = "not-a-uuid"), "payouts.payout.completed")
            }

            verify(exactly = 0) { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should throw TransientConsumerException when transfer not found`() {
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns null

            assertThrows(TransientConsumerException::class.java) {
                consumer.consume(eventJson("PAYOUT_COMPLETED"), "payouts.payout.completed")
            }

            verify(exactly = 0) { transferRepository.save(any()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should throw NonRetriableConsumerException on deserialization failure`() {
            assertThrows(NonRetriableConsumerException::class.java) {
                consumer.consume("not valid json {{{", "payouts.payout.completed")
            }

            verify(exactly = 0) { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should not evict cache when transaction returns false`() {
            every { consumedEventRepository.existsByEventId(eventId) } returns true

            consumer.consume(eventJson("PAYOUT_COMPLETED"), "payouts.payout.completed")

            verify(exactly = 0) { transferCacheService.evict(any()) }
        }
    }
}
