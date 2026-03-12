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
import com.swiftpay.transfer.service.TransferMetrics
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
class PaymentEventConsumerTest {

    private val transferRepository: TransferRepository = mockk()
    private val transferCacheService: TransferCacheService = mockk(relaxed = true)
    private val consumedEventRepository: ConsumedEventRepository = mockk()
    private val outboxEventRepository: OutboxEventRepository = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val transferStatusPublisher: TransferStatusPublisher = mockk(relaxed = true)
    private val transferMetrics: TransferMetrics = mockk(relaxed = true)

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }

    private lateinit var consumer: PaymentEventConsumer

    private val transferId = UUID.randomUUID()
    private val eventId = "evt-${UUID.randomUUID()}"
    private val paymentId = UUID.randomUUID().toString()

    @BeforeEach
    fun setup() {
        consumer = PaymentEventConsumer(
            transferRepository = transferRepository,
            transferCacheService = transferCacheService,
            consumedEventRepository = consumedEventRepository,
            outboxEventRepository = outboxEventRepository,
            transactionTemplate = transactionTemplate,
            objectMapper = objectMapper,
            transferStatusPublisher = transferStatusPublisher,
            transferMetrics = transferMetrics,
            meterRegistry = SimpleMeterRegistry()
        )

        // Make TransactionTemplate actually execute the callback
        every { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) } answers {
            val callback = firstArg<TransactionCallback<Boolean>>()
            callback.doInTransaction(mockk())
        }
    }

    private fun createTransfer(status: TransferStatus = TransferStatus.PaymentPending): Transfer = Transfer(
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
        status = status
    )

    private fun eventJson(
        eventType: String,
        transferId: String = this.transferId.toString(),
        eventId: String = this.eventId,
        paymentId: String? = this.paymentId,
        reason: String? = null
    ): String = objectMapper.writeValueAsString(
        PaymentEvent(
            eventId = eventId,
            transferId = transferId,
            eventType = eventType,
            paymentId = paymentId,
            reason = reason,
            timestamp = "2026-03-04T10:00:00Z"
        )
    )

    @Nested
    inner class PaymentCaptured {

        @Test
        fun `should transition transfer to PAYOUT_PENDING and save outbox event`() {
            val transfer = createTransfer()
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYMENT_CAPTURED"), "payments.payment.captured")

            assertEquals(TransferStatus.PayoutPending, transfer.status)
            verify(exactly = 2) { transferRepository.save(transfer) }
            verify { consumedEventRepository.save(match { it.eventId == eventId }) }
            verify { outboxEventRepository.save(match {
                it.eventType == OutboxEventType.PAYOUT_REQUESTED &&
                    it.targetTopic == "transfers.payout.requested"
            }) }
            verify { transferCacheService.evict(transferId) }
        }

        @Test
        fun `should set paymentId on transfer when present`() {
            val transfer = createTransfer()
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYMENT_CAPTURED"), "payments.payment.captured")

            assertEquals(UUID.fromString(paymentId), transfer.paymentId)
        }
    }

    @Nested
    inner class PaymentFailed {

        @Test
        fun `should transition transfer to PAYMENT_FAILED with reason`() {
            val transfer = createTransfer()
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYMENT_FAILED", reason = "Insufficient funds"), "payments.payment.failed")

            assertEquals(TransferStatus.PaymentFailed, transfer.status)
            assertEquals("Insufficient funds", transfer.statusReason)
            verify { transferCacheService.evict(transferId) }
        }
    }

    @Nested
    inner class PaymentRefunded {

        @Test
        fun `should transition transfer to REFUNDED and evict cache`() {
            val transfer = createTransfer(status = TransferStatus.RefundPending)
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { consumedEventRepository.save(any()) } answers { firstArg() }

            consumer.consume(eventJson("PAYMENT_REFUNDED"), "payments.payment.refunded")

            assertEquals(TransferStatus.Refunded, transfer.status)
            verify { transferRepository.save(transfer) }
            verify { consumedEventRepository.save(match { it.eventId == eventId }) }
            verify { transferCacheService.evict(transferId) }
        }
    }

    @Nested
    inner class Idempotency {

        @Test
        fun `should skip processing when event already consumed`() {
            every { consumedEventRepository.existsByEventId(eventId) } returns true

            consumer.consume(eventJson("PAYMENT_CAPTURED"), "payments.payment.captured")

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
                consumer.consume(eventJson("PAYMENT_REVERSED"), "payments.payment.captured")
            }

            verify(exactly = 0) { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should throw NonRetriableConsumerException for invalid transferId format`() {
            assertThrows(NonRetriableConsumerException::class.java) {
                consumer.consume(eventJson("PAYMENT_CAPTURED", transferId = "not-a-uuid"), "payments.payment.captured")
            }

            verify(exactly = 0) { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should throw TransientConsumerException when transfer not found`() {
            every { consumedEventRepository.existsByEventId(eventId) } returns false
            every { transferRepository.findTransferById(transferId) } returns null

            assertThrows(TransientConsumerException::class.java) {
                consumer.consume(eventJson("PAYMENT_CAPTURED"), "payments.payment.captured")
            }

            verify(exactly = 0) { transferRepository.save(any()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should throw NonRetriableConsumerException on deserialization failure`() {
            assertThrows(NonRetriableConsumerException::class.java) {
                consumer.consume("not valid json {{{", "payments.payment.captured")
            }

            verify(exactly = 0) { transactionTemplate.execute(any<TransactionCallback<Boolean>>()) }
            verify(exactly = 0) { transferCacheService.evict(any()) }
        }

        @Test
        fun `should not evict cache when transaction returns false`() {
            every { consumedEventRepository.existsByEventId(eventId) } returns true

            consumer.consume(eventJson("PAYMENT_CAPTURED"), "payments.payment.captured")

            verify(exactly = 0) { transferCacheService.evict(any()) }
        }
    }
}
