# Block 9 — Unit Tests (MockK + JUnit 5)

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 9.** Blocks 1–8 завершены. Вся бизнес-логика работает. Нужно покрыть тестами.

## Задача

Unit-тесты для:
1. `TransferService` — бизнес-логика (основной фокус)
2. `TransferStatus` — state machine переходы
3. Cursor encoding/decoding

## Инструменты

- **MockK** (не Mockito) — Kotlin-native mocking. Поддерживает корутины, extension functions, companion objects. Идиоматичнее Mockito для Kotlin.
- **JUnit 5** — test runner
- **kotlin.test** или **AssertJ** — assertions

## Зависимости в Gradle

Убедись что в `build.gradle.kts` есть:

```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")  // исключаем Mockito, используем MockK
    exclude(module = "mockito-junit-jupiter")
}
testImplementation("io.mockk:mockk:1.13.13")
testImplementation("io.mockk:mockk-jvm:1.13.13")
testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
```

Версию MockK проверь на актуальность — `1.13.x` для Kotlin 2.x.

## Структура файлов

Создать в `services/transfer-service/src/test/kotlin/com/transferhub/transfer/`:

```
service/
  TransferServiceTest.kt          — основной тест-класс
domain/
  TransferStatusTest.kt           — тесты state machine
```

Пакетная структура тестов зеркалит main.

---

## 1. TransferServiceTest.kt

```kotlin
package com.transferhub.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.transferhub.transfer.domain.model.*
import com.transferhub.transfer.domain.vo.DeliveryMethod
import com.transferhub.transfer.domain.vo.OutboxEventStatus
import com.transferhub.transfer.exception.*
import com.transferhub.transfer.repository.*
import com.transferhub.transfer.service.dto.CreateTransferCommand
import io.mockk.*
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit тесты для TransferService.
 *
 * Все зависимости замокированы через MockK.
 * Тесты проверяют бизнес-логику в изоляции от БД, Redis, Kafka.
 */
@ExtendWith(MockKExtension::class)
class TransferServiceTest {

    @MockK
    private lateinit var transferRepository: TransferRepository

    @MockK
    private lateinit var outboxEventRepository: OutboxEventRepository

    @MockK
    private lateinit var recipientRepository: RecipientRepository

    @MockK
    private lateinit var idempotencyKeyRepository: IdempotencyKeyRepository

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
        registerModule(JavaTimeModule())
    }

    // НЕ используем @InjectMockKs — создаём вручную, чтобы передать реальный ObjectMapper
    private lateinit var transferService: TransferService

    // --- Test fixtures ---
    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val idempotencyKey = UUID.randomUUID()
    private val quoteId = UUID.randomUUID()

    private val testRecipient = Recipient(
        id = recipientId,
        senderId = senderId,
        firstName = "Maria",
        lastName = "Santos",
        country = "PH"
        // добавь остальные обязательные поля Recipient entity из Block 2
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
        transferService = TransferService(
            transferRepository = transferRepository,
            outboxEventRepository = outboxEventRepository,
            recipientRepository = recipientRepository,
            idempotencyKeyRepository = idempotencyKeyRepository,
            objectMapper = objectMapper
        )
    }

    // ================================================================
    // createTransfer — Happy Path
    // ================================================================

    @Nested
    inner class CreateTransferHappyPath {

        @Test
        fun `should create transfer and outbox event in single call`() {
            // Given
            every { transferRepository.findByIdempotencyKey(idempotencyKey) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns testRecipient
            every { transferRepository.save(any()) } answers { firstArg() }  // return the saved entity
            every { outboxEventRepository.save(any()) } answers { firstArg() }

            // When
            val (transfer, isNew) = transferService.createTransfer(validCommand())

            // Then
            assertTrue(isNew, "Should be a new transfer")
            assertEquals(TransferStatus.Created, transfer.status)
            assertEquals(senderId, transfer.senderId)
            assertEquals(BigDecimal("200.00"), transfer.sendAmount)
            assertEquals("USD", transfer.sendCurrency)
            assertEquals("PH", transfer.destCountry)
            assertEquals(DeliveryMethod.BANK_DEPOSIT, transfer.deliveryMethod)

            // Verify: both transfer AND outbox event saved
            verify(exactly = 1) { transferRepository.save(any()) }
            verify(exactly = 1) { outboxEventRepository.save(match { event ->
                event.aggregateType == "Transfer" &&
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

            // Parse payload JSON
            val payload = objectMapper.readTree(event.payload)
            assertEquals("USD", payload.get("send_currency").asText())
            assertEquals("200.00", payload.get("send_amount").asText())
            assertNotNull(payload.get("event_id"))
        }
    }

    // ================================================================
    // createTransfer — Idempotency
    // ================================================================

    @Nested
    inner class IdempotencyCheck {

        @Test
        fun `should return existing transfer when idempotency key already exists`() {
            // Given: idempotency key already processed
            val existingTransfer = Transfer(
                id = UUID.randomUUID(),
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

            // When
            val (transfer, isNew) = transferService.createTransfer(validCommand())

            // Then
            assertFalse(isNew, "Should NOT be a new transfer")
            assertEquals(existingTransfer.id, transfer.id)

            // Verify: NO new save calls
            verify(exactly = 0) { transferRepository.save(any()) }
            verify(exactly = 0) { outboxEventRepository.save(any()) }
        }
    }

    // ================================================================
    // createTransfer — Validation Errors
    // ================================================================

    @Nested
    inner class BusinessValidation {

        @Test
        fun `should reject unsupported corridor`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null

            val command = validCommand(sourceCountry = "US", destCountry = "JP")

            val exception = assertThrows<UnsupportedCorridorException> {
                transferService.createTransfer(command)
            }
            assertTrue(exception.message!!.contains("US→JP"))
        }

        @Test
        fun `should reject amount below minimum`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null

            val command = validCommand(sendAmount = BigDecimal("1.00"))  // min is 10.00 for US_PH

            assertThrows<BusinessException> {
                transferService.createTransfer(command)
            }
        }

        @Test
        fun `should reject invalid delivery method for corridor`() {
            every { transferRepository.findByIdempotencyKey(any()) } returns null

            // US_MX doesn't support MOBILE_WALLET (only BANK_DEPOSIT, CASH_PICKUP)
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
            val otherSenderRecipient = testRecipient.copy(
                senderId = UUID.randomUUID()  // другой отправитель
            )
            // Примечание: если Recipient НЕ data class, создай новый объект вместо copy()

            every { transferRepository.findByIdempotencyKey(any()) } returns null
            every { recipientRepository.findRecipientById(recipientId) } returns otherSenderRecipient

            assertThrows<RecipientNotFoundException> {
                transferService.createTransfer(validCommand())
            }
        }
    }

    // ================================================================
    // getTransfer
    // ================================================================

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

    // ================================================================
    // listTransfers — Pagination
    // ================================================================

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
            // 5 < 20+1=21, so no more pages
            assertEquals(null, nextCursor)
        }

        @Test
        fun `should return next cursor when more results exist`() {
            // Запросили size=2, вернулось 3 (size+1 trick) → есть следующая страница
            val transfers = (1..3).map { createDummyTransfer() }

            every {
                transferRepository.findBySenderIdFirstPage(senderId, any())
            } returns transfers

            val (result, nextCursor) = transferService.listTransfers(senderId, null, 2)

            assertEquals(2, result.size)  // возвращаем только size, не size+1
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

            // size > 100 → coerced to 100
            transferService.listTransfers(senderId, null, 500)

            verify {
                transferRepository.findBySenderIdFirstPage(
                    senderId,
                    match { it.pageSize == 101 }  // 100 + 1 (size+1 trick)
                )
            }
        }

        private fun createDummyTransfer(): Transfer = Transfer(
            id = UUID.randomUUID(),
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
```

---

## 2. TransferStatusTest.kt

```kotlin
package com.transferhub.transfer.domain

import com.transferhub.transfer.domain.model.TransferStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Тесты sealed class TransferStatus — state machine переходы.
 */
class TransferStatusTest {

    // ============ Allowed transitions ============

    @Test
    fun `CREATED can transition to COMPLIANCE_CHECK`() {
        assertTrue(TransferStatus.Created.canTransitionTo(TransferStatus.ComplianceCheck))
    }

    @Test
    fun `CREATED can transition to CANCELLED`() {
        assertTrue(TransferStatus.Created.canTransitionTo(TransferStatus.Cancelled))
    }

    @Test
    fun `COMPLIANCE_CHECK can transition to PAYMENT_CAPTURING`() {
        assertTrue(TransferStatus.ComplianceCheck.canTransitionTo(TransferStatus.PaymentCapturing))
    }

    @Test
    fun `IN_TRANSIT can transition to COMPLETED`() {
        assertTrue(TransferStatus.InTransit.canTransitionTo(TransferStatus.Completed))
    }

    // ============ Forbidden transitions ============

    @Test
    fun `COMPLETED cannot transition to any status`() {
        TransferStatus::class.sealedSubclasses.forEach { subclass ->
            val status = subclass.objectInstance ?: return@forEach
            assertFalse(
                TransferStatus.Completed.canTransitionTo(status),
                "COMPLETED should not transition to ${status.value}"
            )
        }
    }

    @Test
    fun `CANCELLED cannot transition to any status`() {
        TransferStatus::class.sealedSubclasses.forEach { subclass ->
            val status = subclass.objectInstance ?: return@forEach
            assertFalse(
                TransferStatus.Cancelled.canTransitionTo(status),
                "CANCELLED should not transition to ${status.value}"
            )
        }
    }

    @Test
    fun `CREATED cannot transition to COMPLETED directly`() {
        assertFalse(TransferStatus.Created.canTransitionTo(TransferStatus.Completed))
    }

    // ============ Terminal states ============

    @Test
    fun `COMPLETED is terminal`() {
        assertTrue(TransferStatus.Completed.isTerminal())
    }

    @Test
    fun `CANCELLED is terminal`() {
        assertTrue(TransferStatus.Cancelled.isTerminal())
    }

    @Test
    fun `REFUNDED is terminal`() {
        assertTrue(TransferStatus.Refunded.isTerminal())
    }

    @Test
    fun `CREATED is not terminal`() {
        assertFalse(TransferStatus.Created.isTerminal())
    }

    @Test
    fun `PROCESSING is not terminal`() {
        assertFalse(TransferStatus.Processing.isTerminal())
    }

    // ============ Display status ============

    @Test
    fun `display status maps internal states to user-friendly names`() {
        // Internal status COMPLIANCE_CHECK should display as "PROCESSING" to user
        assertEquals("PROCESSING", TransferStatus.ComplianceCheck.displayStatus())
        assertEquals("PROCESSING", TransferStatus.PaymentCapturing.displayStatus())
        assertEquals("COMPLETED", TransferStatus.Completed.displayStatus())
        assertEquals("CREATED", TransferStatus.Created.displayStatus())
    }

    // ============ from string ============

    @Test
    fun `should parse status from string value`() {
        // Адаптируй под реальный companion object fromString() если он есть
        // assertEquals(TransferStatus.Created, TransferStatus.fromString("CREATED"))
        // assertEquals(TransferStatus.Completed, TransferStatus.fromString("COMPLETED"))
    }
}
```

**Примечание:** адаптируй тесты под реальные имена статусов и методов из Block 2. Если `TransferStatus` использует другие имена (например, `Created` vs `CREATED`) — поправь.

---

## Запуск тестов

```bash
./gradlew :services:transfer-service:test

# Только unit тесты (если интеграционные в отдельном source set):
./gradlew :services:transfer-service:test --tests "com.transferhub.transfer.service.*"
./gradlew :services:transfer-service:test --tests "com.transferhub.transfer.domain.*"
```

## Проверка результата

1. Все тесты зелёные
2. `./gradlew :services:transfer-service:test` — exit code 0
3. 15+ тестов суммарно
4. Все MockK verify — пройдены
5. Нет flaky tests (запусти 2-3 раза)

## Частые ошибки

- **`Recipient` не data class** → `copy()` не работает. Создавай новый объект вручную.
- **`Transfer` конструктор** → если entity не data class, убедись что конструктор принимает все нужные поля. Или используй builder/factory.
- **MockK `every` без `returns`** → тест упадёт с "no answer found". Мокай ВСЕ вызываемые методы.
- **`@ExtendWith(MockKExtension::class)`** → без этого `@MockK` аннотации не инициализируются.

## Чего НЕ делать

- Не поднимай Spring Context — это unit тесты, не integration
- Не подключай Testcontainers — Block 10
- Не тестируй контроллер — Block 10 (через WebTestClient)
