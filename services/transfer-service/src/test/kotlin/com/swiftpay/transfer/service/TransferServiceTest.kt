package com.swiftpay.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.swiftpay.transfer.client.PricingClient
import com.swiftpay.transfer.client.QuoteData
import com.swiftpay.transfer.domain.model.*
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.exception.*
import com.swiftpay.transfer.lock.DistributedLockService
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.RecipientRepository
import com.swiftpay.transfer.repository.TransferRepository
import com.swiftpay.transfer.service.dto.CreateTransferCommand
import io.mockk.*
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

@ExtendWith(MockKExtension::class)
class TransferServiceTest {

    private val transferRepository: TransferRepository = mockk()
    private val outboxEventRepository: OutboxEventRepository = mockk()
    private val recipientRepository: RecipientRepository = mockk()
    private val distributedLockService: DistributedLockService = mockk()
    private val pricingClient: PricingClient = mockk()

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }

    private lateinit var transferService: TransferService

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val idempotencyKey = UUID.randomUUID()
    private val quoteId = UUID.randomUUID()

    private val defaultQuoteData = QuoteData(
        quoteId = quoteId.toString(),
        sendAmount = BigDecimal("200.00"),
        receiveAmount = BigDecimal("10907.72"),
        exchangeRate = BigDecimal("56.20"),
        feeAmount = BigDecimal("5.99"),
        feeCurrency = "USD",
        sendCurrency = "USD",
        receiveCurrency = "PHP",
    )

    private val testRecipient = Recipient(
        id = recipientId,
        senderId = senderId,
        firstName = "Maria",
        lastName = "Santos",
        country = "PH",
        deliveryDetails = """{"bank_name": "BDO", "account_number": "123"}"""
    )

    private fun validCommand(
        sendAmount: BigDecimal = BigDecimal("200.00"),
        sourceCountry: String = "US",
        destCountry: String = "PH",
        deliveryMethod: String = "BANK_DEPOSIT"
    ) = CreateTransferCommand(
        idempotencyKey = idempotencyKey,
        senderId = senderId,
        recipientId = recipientId,
        quoteId = quoteId,
        sendAmount = sendAmount,
        sendCurrency = "USD",
        receiveCurrency = "PHP",
        sourceCountry = sourceCountry,
        destCountry = destCountry,
        deliveryMethod = deliveryMethod
    )

    @BeforeEach
    fun setUp() {
        every { distributedLockService.executeWithLock(any(), any<() -> Any>()) } answers {
            val action = secondArg<() -> Any>()
            action()
        }

        every { pricingClient.validateQuote(any()) } returns defaultQuoteData

        transferService = TransferService(
            transferRepository = transferRepository,
            outboxEventRepository = outboxEventRepository,
            recipientRepository = recipientRepository,
            objectMapper = objectMapper,
            distributedLockService = distributedLockService,
            pricingClient = pricingClient
        )
    }

    @Nested
    inner class CreateTransferHappyPath {

        @Test
        fun `should create transfer and outbox event`() {
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { transferRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            val (result, isNew) = transferService.createTransfer(validCommand())

            assertTrue(isNew)
            assertEquals(TransferStatus.Created, result.transfer.status)
            assertEquals(senderId, result.transfer.senderId)
            assertEquals(BigDecimal("200.00"), result.transfer.sendAmount)
            assertEquals("USD", result.transfer.sendCurrency)
            assertEquals("PH", result.transfer.destCountry)
            assertEquals(DeliveryMethod.BANK_DEPOSIT, result.transfer.deliveryMethod)
            assertEquals(testRecipient, result.recipient)

            // Verify quote data from pricing service is used
            assertEquals(BigDecimal("10907.72"), result.transfer.receiveAmount)
            assertEquals(BigDecimal("56.20"), result.transfer.exchangeRate)
            assertEquals(BigDecimal("5.99"), result.transfer.feeAmount)
            assertEquals("USD", result.transfer.feeCurrency)

            verify(exactly = 1) { transferRepository.save(any()) }
            verify(exactly = 1) { outboxEventRepository.save(match { event ->
                event.entityType == "TRANSFER" &&
                event.status == OutboxEventStatus.PENDING
            }) }
        }

        @Test
        fun `should set transfer status to CREATED`() {
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { transferRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            val (result, _) = transferService.createTransfer(validCommand())

            assertEquals(TransferStatus.Created, result.transfer.status)
        }

        @Test
        fun `should acquire lock by senderId`() {
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { transferRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            transferService.createTransfer(validCommand())

            verify { distributedLockService.executeWithLock(eq("sender/$senderId/create"), any<() -> Any>()) }
        }

        @Test
        fun `should generate outbox event with correct payload`() {
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { transferRepository.save(any()) } answers { firstArg() }

            val capturedEvent = slot<OutboxEvent>()
            every { outboxEventRepository.save(capture(capturedEvent)) } answers { firstArg() }

            transferService.createTransfer(validCommand())

            val event = capturedEvent.captured
            assertNotNull(event.payload)

            val payload = objectMapper.readTree(event.payload)
            assertEquals("USD", payload.get("send_currency").asText())
            assertEquals("200.00", payload.get("send_amount").asText())
            assertEquals("10907.72", payload.get("receive_amount").asText())
            assertEquals("56.20", payload.get("exchange_rate").asText())
            assertEquals("5.99", payload.get("fee_amount").asText())
            assertNotNull(payload.get("event_id"))
        }

        @Test
        fun `should call pricingClient with quoteId`() {
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { transferRepository.save(any()) } answers { firstArg() }
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            transferService.createTransfer(validCommand())

            verify(exactly = 1) { pricingClient.validateQuote(quoteId.toString()) }
        }
    }

    @Nested
    inner class IdempotencyCheck {

        @Test
        fun `should return existing transfer when idempotency key already exists`() {
            val existingTransfer = Transfer(
                idempotencyKey = idempotencyKey,
                senderId = senderId,
                quoteId = quoteId,
                sendAmount = BigDecimal("200.00"),
                sendCurrency = "USD",
                receiveAmount = BigDecimal("11240.00"),
                receiveCurrency = "PHP",
                exchangeRate = BigDecimal("56.20"),
                feeAmount = BigDecimal("4.99"),
                feeCurrency = "USD",
                sourceCountry = "US",
                destCountry = "PH",
                deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
                recipientId = recipientId,
                status = TransferStatus.Created
            )
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns existingTransfer
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient

            val (result, isNew) = transferService.createTransfer(validCommand())

            assertFalse(isNew)
            assertEquals(existingTransfer.id, result.transfer.id)

            verify(exactly = 0) { transferRepository.save(any()) }
            verify(exactly = 0) { outboxEventRepository.save(any()) }
            verify(exactly = 0) { pricingClient.validateQuote(any()) }
        }
    }

    @Nested
    inner class BusinessValidation {

        @Test
        fun `should reject unsupported corridor`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null

            val command = validCommand(sourceCountry = "US", destCountry = "JP")

            val exception = assertThrows<UnsupportedCorridorException> {
                transferService.createTransfer(command)
            }
            assertTrue(exception.message!!.contains("US") && exception.message!!.contains("JP"))
        }

        @Test
        fun `should reject amount below minimum`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null

            val command = validCommand(sendAmount = BigDecimal("1.00"))

            assertThrows<BusinessException> {
                transferService.createTransfer(command)
            }
        }

        @Test
        fun `should reject invalid delivery method for corridor`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null

            val command = validCommand(
                sourceCountry = "US",
                destCountry = "MX",
                deliveryMethod = "MOBILE_WALLET"
            )

            assertThrows<BusinessException> {
                transferService.createTransfer(command)
            }
        }

        @Test
        fun `should reject when recipient not found`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns null

            assertThrows<RecipientNotFoundException> {
                transferService.createTransfer(validCommand())
            }
        }

        @Test
        fun `should reject when recipient belongs to different sender`() {
            val otherRecipient = Recipient(
                id = recipientId,
                senderId = UUID.randomUUID(),
                firstName = "Maria",
                lastName = "Santos",
                country = "PH",
                deliveryDetails = """{"bank_name": "BDO"}"""
            )

            every { transferRepository.findByIdempotencyKey(any()) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns otherRecipient

            assertThrows<RecipientNotFoundException> {
                transferService.createTransfer(validCommand())
            }
        }
    }

    @Nested
    inner class PricingIntegration {

        @Test
        fun `should throw QuoteExpiredException when quote invalid`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { pricingClient.validateQuote(any()) } throws QuoteExpiredException(quoteId.toString(), "expired")

            assertThrows<QuoteExpiredException> {
                transferService.createTransfer(validCommand())
            }
        }

        @Test
        fun `should throw PricingUnavailableException when pricing down`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { pricingClient.validateQuote(any()) } throws PricingUnavailableException("service down")

            assertThrows<PricingUnavailableException> {
                transferService.createTransfer(validCommand())
            }
        }

        @Test
        fun `should throw QuoteCorridorMismatchException on currency mismatch`() {
            val mismatchedQuote = defaultQuoteData.copy(
                sendCurrency = "GBP",
                receiveCurrency = "INR"
            )
            every { transferRepository.findByIdempotencyKey(any()) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { pricingClient.validateQuote(any()) } returns mismatchedQuote

            assertThrows<QuoteCorridorMismatchException> {
                transferService.createTransfer(validCommand())
            }
        }
    }

    @Nested
    inner class GetTransfer {

        @Test
        fun `should return transfer when found`() {
            val transferId = UUID.randomUUID()
            val transfer = Transfer(
                id = transferId,
                idempotencyKey = UUID.randomUUID(),
                senderId = senderId,
                quoteId = quoteId,
                sendAmount = BigDecimal("100.00"),
                sendCurrency = "USD",
                receiveAmount = BigDecimal("5620.00"),
                receiveCurrency = "PHP",
                exchangeRate = BigDecimal("56.20"),
                feeAmount = BigDecimal("4.99"),
                feeCurrency = "USD",
                sourceCountry = "US",
                destCountry = "PH",
                deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
                recipientId = recipientId,
                status = TransferStatus.Created
            )
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient

            val result = transferService.getTransfer(transferId)
            assertEquals(transferId, result.transfer.id)
            assertEquals(testRecipient, result.recipient)
        }

        @Test
        fun `should throw TransferNotFoundException when not found`() {
            val unknownId = UUID.randomUUID()
            every { transferRepository.findTransferById(unknownId) } returns null

            assertThrows<TransferNotFoundException> {
                transferService.getTransfer(unknownId)
            }
        }
    }

    @Nested
    inner class ListTransfers {

        @Test
        fun `should return first page without cursor`() {
            val transfers = (1..5).map { createDummyTransfer() }

            every {
                transferRepository.findBySenderIdFirstPage(senderId, any())
            } returns transfers
            every { recipientRepository.findAllById(any<List<UUID>>()) } returns listOf(testRecipient)

            val (results, nextCursor) = transferService.listTransfers(senderId, null, 20)

            assertEquals(5, results.size)
            assertEquals(null, nextCursor)
        }

        @Test
        fun `should return next cursor when more results exist`() {
            val transfers = (1..3).map { createDummyTransfer() }

            every {
                transferRepository.findBySenderIdFirstPage(senderId, any())
            } returns transfers
            every { recipientRepository.findAllById(any<List<UUID>>()) } returns listOf(testRecipient)

            val (results, nextCursor) = transferService.listTransfers(senderId, null, 2)

            assertEquals(2, results.size)
            assertNotNull(nextCursor)
        }

        @Test
        fun `should return empty list for unknown sender`() {
            every {
                transferRepository.findBySenderIdFirstPage(any(), any())
            } returns emptyList()
            every { recipientRepository.findAllById(any<List<UUID>>()) } returns emptyList()

            val (results, nextCursor) = transferService.listTransfers(UUID.randomUUID(), null, 20)

            assertTrue(results.isEmpty())
            assertEquals(null, nextCursor)
        }

        @Test
        fun `should coerce size to valid range`() {
            every {
                transferRepository.findBySenderIdFirstPage(senderId, any())
            } returns emptyList()
            every { recipientRepository.findAllById(any<List<UUID>>()) } returns emptyList()

            transferService.listTransfers(senderId, null, 500)

            verify {
                transferRepository.findBySenderIdFirstPage(
                    senderId,
                    match { it.pageSize == 101 }
                )
            }
        }

        private fun createDummyTransfer(): Transfer = Transfer(
            idempotencyKey = UUID.randomUUID(),
            senderId = senderId,
            quoteId = UUID.randomUUID(),
            sendAmount = BigDecimal("100.00"),
            sendCurrency = "USD",
            receiveAmount = BigDecimal("5620.00"),
            receiveCurrency = "PHP",
            exchangeRate = BigDecimal("56.20"),
            feeAmount = BigDecimal("4.99"),
            feeCurrency = "USD",
            sourceCountry = "US",
            destCountry = "PH",
            deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
            recipientId = recipientId,
            status = TransferStatus.Created,
            createdAt = Instant.now()
        )
    }

    @Nested
    inner class TransitionStatus {

        private val transferId = UUID.randomUUID()

        private fun createdTransfer() = Transfer(
            id = transferId,
            idempotencyKey = UUID.randomUUID(),
            senderId = senderId,
            quoteId = quoteId,
            sendAmount = BigDecimal("200.00"),
            sendCurrency = "USD",
            receiveAmount = BigDecimal("11240.00"),
            receiveCurrency = "PHP",
            exchangeRate = BigDecimal("56.20"),
            feeAmount = BigDecimal("4.99"),
            feeCurrency = "USD",
            sourceCountry = "US",
            destCountry = "PH",
            deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
            recipientId = recipientId,
            status = TransferStatus.Created
        )

        @Test
        fun `should transition status and return updated transfer`() {
            val transfer = createdTransfer()
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient

            val result = transferService.transitionStatus(transferId, TransferStatus.ComplianceCheck)

            assertEquals(TransferStatus.ComplianceCheck, result.transfer.status)
            assertSame(testRecipient, result.recipient)
            verify(exactly = 1) { transferRepository.save(any()) }
        }

        @Test
        fun `should acquire lock by transferId`() {
            val transfer = createdTransfer()
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient

            transferService.transitionStatus(transferId, TransferStatus.ComplianceCheck)

            verify { distributedLockService.executeWithLock(eq("transfer/$transferId/status"), any<() -> Any>()) }
        }

        @Test
        fun `should throw TransferNotFoundException when transfer does not exist`() {
            val unknownId = UUID.randomUUID()
            every { transferRepository.findTransferById(unknownId) } returns null

            assertThrows<TransferNotFoundException> {
                transferService.transitionStatus(unknownId, TransferStatus.ComplianceCheck)
            }
        }

        @Test
        fun `should throw on invalid state transition`() {
            val transfer = createdTransfer()
            every { transferRepository.findTransferById(transferId) } returns transfer

            assertThrows<IllegalStateException> {
                transferService.transitionStatus(transferId, TransferStatus.Completed)
            }
        }

        @Test
        fun `should set status reason when provided`() {
            val transfer = createdTransfer()
            every { transferRepository.findTransferById(transferId) } returns transfer
            every { transferRepository.save(any()) } answers { firstArg() }
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient

            val result = transferService.transitionStatus(
                transferId, TransferStatus.Cancelled, reason = "User requested cancellation"
            )

            assertEquals(TransferStatus.Cancelled, result.transfer.status)
            assertEquals("User requested cancellation", result.transfer.statusReason)
        }
    }
}
