package com.swiftpay.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.swiftpay.transfer.domain.model.*
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.exception.*
import com.swiftpay.transfer.lock.ConsulLockProperties
import com.swiftpay.transfer.lock.DistributedLockService
import com.swiftpay.transfer.repository.*
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
import org.junit.jupiter.api.Assertions.assertTrue

@ExtendWith(MockKExtension::class)
class TransferServiceTest {

    private val transferRepository: TransferRepository = mockk()
    private val outboxEventRepository: OutboxEventRepository = mockk()
    private val recipientRepository: RecipientRepository = mockk()
    private val idempotencyKeyRepository: IdempotencyKeyRepository = mockk()
    private val distributedLockService: DistributedLockService = mockk()
    private val consulLockProperties = ConsulLockProperties()

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }

    private lateinit var transferService: TransferService

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val idempotencyKey = UUID.randomUUID()
    private val quoteId = UUID.randomUUID()

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

        transferService = TransferService(
            transferRepository = transferRepository,
            outboxEventRepository = outboxEventRepository,
            recipientRepository = recipientRepository,
            idempotencyKeyRepository = idempotencyKeyRepository,
            objectMapper = objectMapper,
            distributedLockService = distributedLockService,
            consulLockProperties = consulLockProperties
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

            val (transfer, isNew) = transferService.createTransfer(validCommand())

            assertTrue(isNew)
            assertEquals(TransferStatus.Created, transfer.status)
            assertEquals(senderId, transfer.senderId)
            assertEquals(BigDecimal("200.00"), transfer.sendAmount)
            assertEquals("USD", transfer.sendCurrency)
            assertEquals("PH", transfer.destCountry)
            assertEquals(DeliveryMethod.BANK_DEPOSIT, transfer.deliveryMethod)

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

            val (transfer, _) = transferService.createTransfer(validCommand())

            assertEquals(TransferStatus.Created, transfer.status)
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
            assertNotNull(payload.get("event_id"))
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

            val (transfer, isNew) = transferService.createTransfer(validCommand())

            assertFalse(isNew)
            assertEquals(existingTransfer.id, transfer.id)

            verify(exactly = 0) { transferRepository.save(any()) }
            verify(exactly = 0) { outboxEventRepository.save(any()) }
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

            val result = transferService.getTransfer(transferId)
            assertEquals(transferId, result.id)
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

            val (result, nextCursor) = transferService.listTransfers(senderId, null, 20)

            assertEquals(5, result.size)
            assertEquals(null, nextCursor)
        }

        @Test
        fun `should return next cursor when more results exist`() {
            val transfers = (1..3).map { createDummyTransfer() }

            every {
                transferRepository.findBySenderIdFirstPage(senderId, any())
            } returns transfers

            val (result, nextCursor) = transferService.listTransfers(senderId, null, 2)

            assertEquals(2, result.size)
            assertNotNull(nextCursor)
        }

        @Test
        fun `should return empty list for unknown sender`() {
            every {
                transferRepository.findBySenderIdFirstPage(any(), any())
            } returns emptyList()

            val (result, nextCursor) = transferService.listTransfers(UUID.randomUUID(), null, 20)

            assertTrue(result.isEmpty())
            assertEquals(null, nextCursor)
        }

        @Test
        fun `should coerce size to valid range`() {
            every {
                transferRepository.findBySenderIdFirstPage(senderId, any())
            } returns emptyList()

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
}
