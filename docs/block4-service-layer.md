# Block 4 — Service Layer (TransferService)

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 4.** Предыдущие блоки завершены:
- Block 1: Flyway-миграции (4 таблицы)
- Block 2: Domain model (Transfer, TransferStatus sealed class, OutboxEvent, Recipient, IdempotencyRecord, value objects)
- Block 3: Repositories (TransferRepository, OutboxEventRepository, RecipientRepository, IdempotencyKeyRepository)

## Задача

Создать сервисный слой с бизнес-логикой создания перевода. Ключевые паттерны:
1. **Transactional Outbox** — Transfer + OutboxEvent сохраняются в ОДНОЙ транзакции
2. **Idempotency** — повторный запрос с тем же ключом возвращает cached response
3. **Business validation** — проверка правил до создания

## Структура файлов

Создать в `services/transfer-service/src/main/kotlin/com/transferhub/transfer/`:

```
service/
  TransferService.kt            — основной сервис
  dto/
    CreateTransferCommand.kt     — input DTO для сервисного слоя
    TransferResult.kt            — sealed class для результатов (success / business error)
exception/
  BusinessException.kt           — базовый класс бизнес-исключений
  TransferNotFoundException.kt
  InvalidTransferStateException.kt
  QuoteExpiredException.kt
  RecipientNotFoundException.kt
  UnsupportedCorridorException.kt
```

---

## Что создать

### 1. Бизнес-исключения

#### exception/BusinessException.kt

```kotlin
package com.transferhub.transfer.exception

/**
 * Базовый класс для бизнес-ошибок.
 * Все бизнес-ошибки наследуются от него — это позволяет в @RestControllerAdvice (Block 6)
 * маппить их единообразно в RFC 9457 Problem Details.
 *
 * errorType — URI типа ошибки для Problem Details "type" field.
 */
abstract class BusinessException(
    val errorType: String,
    val title: String,
    val statusCode: Int,
    override val message: String
) : RuntimeException(message)
```

#### exception/TransferNotFoundException.kt

```kotlin
package com.transferhub.transfer.exception

import java.util.UUID

class TransferNotFoundException(transferId: UUID) : BusinessException(
    errorType = "https://api.transferhub.com/errors/transfer-not-found",
    title = "Transfer Not Found",
    statusCode = 404,
    message = "Transfer with id $transferId not found"
)
```

#### exception/RecipientNotFoundException.kt

```kotlin
package com.transferhub.transfer.exception

import java.util.UUID

class RecipientNotFoundException(recipientId: UUID) : BusinessException(
    errorType = "https://api.transferhub.com/errors/recipient-not-found",
    title = "Recipient Not Found",
    statusCode = 404,
    message = "Recipient with id $recipientId not found"
)
```

#### exception/InvalidTransferStateException.kt

```kotlin
package com.transferhub.transfer.exception

class InvalidTransferStateException(
    currentStatus: String,
    targetStatus: String
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/invalid-transfer-state",
    title = "Invalid Transfer State",
    statusCode = 409,
    message = "Cannot transition from $currentStatus to $targetStatus"
)
```

#### exception/UnsupportedCorridorException.kt

```kotlin
package com.transferhub.transfer.exception

class UnsupportedCorridorException(
    sourceCountry: String,
    destCountry: String
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/unsupported-corridor",
    title = "Unsupported Corridor",
    statusCode = 422,
    message = "Corridor ${sourceCountry}→${destCountry} is not supported"
)
```

#### exception/QuoteExpiredException.kt

```kotlin
package com.transferhub.transfer.exception

import java.util.UUID

class QuoteExpiredException(quoteId: UUID) : BusinessException(
    errorType = "https://api.transferhub.com/errors/quote-expired",
    title = "Quote Expired",
    statusCode = 422,
    message = "Quote $quoteId has expired. Please request a new quote."
)
```

Добавь другие исключения по мере необходимости (например, `DailyLimitExceededException`, `MinimumAmountException`).

---

### 2. Service DTO: CreateTransferCommand

Это input для сервисного слоя. НЕ HTTP request DTO (тот будет в Block 5). Сервис принимает Command, а не raw HTTP request — разделение ответственностей.

#### service/dto/CreateTransferCommand.kt

```kotlin
package com.transferhub.transfer.service.dto

import java.math.BigDecimal
import java.util.UUID

/**
 * Команда на создание перевода. Поступает из контроллера после маппинга HTTP request → command.
 * Все поля уже провалидированы на уровне контроллера (формат, non-null).
 * Сервис выполняет бизнес-валидацию (коридор поддерживается, сумма в пределах лимитов и т.д.).
 */
data class CreateTransferCommand(
    val idempotencyKey: UUID,
    val senderId: UUID,
    val recipientId: UUID,
    val quoteId: UUID,
    val sendAmount: BigDecimal,
    val sendCurrency: String,
    val receiveCurrency: String,
    val sourceCountry: String,
    val destCountry: String,
    val deliveryMethod: String,
    val purpose: String? = null,
    val referenceNote: String? = null
)
```

---

### 3. TransferService — основной сервис

#### service/TransferService.kt

```kotlin
package com.transferhub.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.transferhub.transfer.domain.model.*
import com.transferhub.transfer.domain.vo.DeliveryMethod
import com.transferhub.transfer.domain.vo.OutboxEventStatus
import com.transferhub.transfer.domain.vo.OutboxEventType
import com.transferhub.transfer.exception.*
import com.transferhub.transfer.repository.*
import com.transferhub.transfer.service.dto.CreateTransferCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Service
class TransferService(
    private val transferRepository: TransferRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val recipientRepository: RecipientRepository,
    private val idempotencyKeyRepository: IdempotencyKeyRepository,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(TransferService::class.java)

    // --- Поддерживаемые коридоры (MVP: hardcoded, в будущем — из MongoDB/config) ---
    private val supportedCorridors: Map<String, Set<DeliveryMethod>> = mapOf(
        "US_PH" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.CASH_PICKUP, DeliveryMethod.MOBILE_WALLET),
        "US_MX" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.CASH_PICKUP),
        "GB_IN" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.MOBILE_WALLET),
        "US_IN" to setOf(DeliveryMethod.BANK_DEPOSIT, DeliveryMethod.MOBILE_WALLET),
    )

    // --- Минимальные суммы по коридору ---
    private val minimumAmounts: Map<String, BigDecimal> = mapOf(
        "US_PH" to BigDecimal("10.00"),
        "US_MX" to BigDecimal("10.00"),
        "GB_IN" to BigDecimal("5.00"),
        "US_IN" to BigDecimal("10.00"),
    )

    /**
     * Создание перевода.
     *
     * КРИТИЧЕСКИ ВАЖНО: Transfer + OutboxEvent сохраняются в ОДНОЙ транзакции.
     * Если transaction commit прошёл — оба записаны. Если rollback — ни один.
     * Это гарантия Outbox Pattern: событие будет опубликовано в Kafka тогда и только тогда,
     * когда бизнес-данные записаны в БД.
     *
     * @return Pair<Transfer, Boolean> — (перевод, isNew). isNew=false если idempotency hit.
     */
    @Transactional
    fun createTransfer(command: CreateTransferCommand): Pair<Transfer, Boolean> {

        // 1. IDEMPOTENCY CHECK: если ключ уже обработан — вернуть существующий перевод
        val existingTransfer = transferRepository.findByIdempotencyKey(command.idempotencyKey)
        if (existingTransfer != null) {
            log.info("Idempotency hit: key=${command.idempotencyKey}, transferId=${existingTransfer.id}")
            return Pair(existingTransfer, false)
        }

        // 2. BUSINESS VALIDATION
        validateTransfer(command)

        // 3. LOOKUP RECIPIENT (проверяем существование и принадлежность отправителю)
        val recipient = recipientRepository.findRecipientById(command.recipientId)
            ?: throw RecipientNotFoundException(command.recipientId)

        if (recipient.senderId != command.senderId) {
            throw RecipientNotFoundException(command.recipientId) // не раскрываем чужие данные
        }

        // 4. RESOLVE DELIVERY METHOD
        val deliveryMethod = DeliveryMethod.fromString(command.deliveryMethod)

        // 5. CREATE TRANSFER ENTITY
        // В MVP: receive_amount, exchange_rate, fee — берём из quote (заглушка).
        // В Sprint 2: gRPC вызов к Pricing Service для валидации quote и получения актуальных данных.
        val transfer = Transfer(
            idempotencyKey = command.idempotencyKey,
            senderId = command.senderId,
            quoteId = command.quoteId,
            sendAmount = command.sendAmount,
            sendCurrency = command.sendCurrency,
            receiveAmount = command.sendAmount, // TODO Sprint 2: из Pricing quote
            receiveCurrency = command.receiveCurrency,
            exchangeRate = BigDecimal.ONE,      // TODO Sprint 2: из Pricing quote
            feeAmount = BigDecimal.ZERO,        // TODO Sprint 2: из Pricing quote
            feeCurrency = command.sendCurrency,
            sourceCountry = command.sourceCountry,
            destCountry = command.destCountry,
            deliveryMethod = deliveryMethod,
            recipientId = command.recipientId,
            status = TransferStatus.Created
        )

        // 6. CREATE OUTBOX EVENT (в той же транзакции!)
        val outboxPayload = buildTransferCreatedPayload(transfer, recipient)
        val outboxEvent = OutboxEvent(
            aggregateId = transfer.id,
            aggregateType = "Transfer",
            eventType = OutboxEventType.TRANSFER_CREATED,
            payload = outboxPayload,
            status = OutboxEventStatus.PENDING
        )

        // 7. SAVE BOTH в одной транзакции (@Transactional на методе)
        val savedTransfer = transferRepository.save(transfer)
        outboxEventRepository.save(outboxEvent)

        log.info(
            "Transfer created: id={}, sender={}, corridor={}→{}, amount={} {}, idempotencyKey={}",
            savedTransfer.id, savedTransfer.senderId,
            savedTransfer.sourceCountry, savedTransfer.destCountry,
            savedTransfer.sendAmount, savedTransfer.sendCurrency,
            savedTransfer.idempotencyKey
        )

        return Pair(savedTransfer, true)
    }

    /**
     * Получить перевод по ID.
     * Redis cache (Cache-Aside) будет добавлен в Block 7.
     */
    @Transactional(readOnly = true)
    fun getTransfer(transferId: UUID): Transfer {
        return transferRepository.findTransferById(transferId)
            ?: throw TransferNotFoundException(transferId)
    }

    /**
     * Cursor-based pagination списка переводов.
     *
     * @param senderId фильтр по отправителю
     * @param cursor opaque cursor (Base64 encoded "createdAt_id"), null для первой страницы
     * @param size размер страницы (default 20, max 100)
     * @return Pair<List<Transfer>, String?> — (результаты, nextCursor или null если больше нет)
     */
    @Transactional(readOnly = true)
    fun listTransfers(
        senderId: UUID,
        cursor: String?,
        size: Int
    ): Pair<List<Transfer>, String?> {

        val effectiveSize = size.coerceIn(1, 100)

        val transfers = if (cursor == null) {
            // Первая страница
            transferRepository.findBySenderIdFirstPage(
                senderId = senderId,
                limit = org.springframework.data.domain.PageRequest.of(0, effectiveSize + 1)
            )
        } else {
            // Декодируем cursor
            val (cursorCreatedAt, cursorId) = decodeCursor(cursor)
            transferRepository.findBySenderIdAfterCursor(
                senderId = senderId,
                cursorCreatedAt = cursorCreatedAt,
                cursorId = cursorId,
                limit = org.springframework.data.domain.PageRequest.of(0, effectiveSize + 1)
            )
        }

        // +1 trick: запросили size+1, если вернулось больше size — есть следующая страница
        val hasMore = transfers.size > effectiveSize
        val page = if (hasMore) transfers.take(effectiveSize) else transfers

        val nextCursor = if (hasMore && page.isNotEmpty()) {
            val lastItem = page.last()
            encodeCursor(lastItem.createdAt, lastItem.id)
        } else {
            null
        }

        return Pair(page, nextCursor)
    }

    // ---- Private helpers ----

    private fun validateTransfer(command: CreateTransferCommand) {
        val corridorId = "${command.sourceCountry}_${command.destCountry}"
        val allowedMethods = supportedCorridors[corridorId]
            ?: throw UnsupportedCorridorException(command.sourceCountry, command.destCountry)

        val deliveryMethod = DeliveryMethod.fromString(command.deliveryMethod)
        if (deliveryMethod !in allowedMethods) {
            throw BusinessException(
                errorType = "https://api.transferhub.com/errors/unsupported-delivery-method",
                title = "Unsupported Delivery Method",
                statusCode = 422,
                message = "$deliveryMethod is not available for corridor $corridorId. " +
                    "Available methods: ${allowedMethods.map { it.name }}"
            ) {}  // anonymous subclass — или создай отдельный exception class
        }

        val minAmount = minimumAmounts[corridorId] ?: BigDecimal("1.00")
        if (command.sendAmount < minAmount) {
            throw BusinessException(
                errorType = "https://api.transferhub.com/errors/minimum-amount",
                title = "Below Minimum Amount",
                statusCode = 422,
                message = "Minimum send amount for $corridorId is $minAmount ${command.sendCurrency}. " +
                    "Requested: ${command.sendAmount}"
            ) {}
        }
    }

    /**
     * Формирование JSON payload для outbox event.
     * Этот JSON будет отправлен в Kafka как тело события transfer.created.
     */
    private fun buildTransferCreatedPayload(transfer: Transfer, recipient: Recipient): String {
        val payload = mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "transfer_id" to transfer.id.toString(),
            "sender_id" to transfer.senderId.toString(),
            "send_amount" to transfer.sendAmount.toPlainString(),
            "send_currency" to transfer.sendCurrency,
            "receive_amount" to transfer.receiveAmount.toPlainString(),
            "receive_currency" to transfer.receiveCurrency,
            "exchange_rate" to transfer.exchangeRate.toPlainString(),
            "fee_amount" to transfer.feeAmount.toPlainString(),
            "delivery_method" to transfer.deliveryMethod.name,
            "source_country" to transfer.sourceCountry,
            "dest_country" to transfer.destCountry,
            "recipient_id" to transfer.recipientId.toString(),
            "created_at" to transfer.createdAt.toString()
        )
        return objectMapper.writeValueAsString(payload)
    }

    // --- Cursor encoding/decoding ---

    /**
     * Cursor = Base64(JSON{"c":"2025-01-15T14:30:00Z","i":"uuid"})
     * Opaque для клиента — internal implementation.
     */
    private fun encodeCursor(createdAt: Instant, id: UUID): String {
        val json = objectMapper.writeValueAsString(mapOf("c" to createdAt.toString(), "i" to id.toString()))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    }

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> {
        return try {
            val json = String(java.util.Base64.getUrlDecoder().decode(cursor))
            val node = objectMapper.readTree(json)
            val createdAt = Instant.parse(node.get("c").asText())
            val id = UUID.fromString(node.get("i").asText())
            Pair(createdAt, id)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid cursor format: $cursor", e)
        }
    }
}
```

---

## Важные архитектурные решения

### 1. Outbox Pattern — атомарность
`@Transactional` на `createTransfer()` означает: `transferRepository.save()` и `outboxEventRepository.save()` — одна PostgreSQL-транзакция. Если любой save упадёт — rollback обоих. Никогда не будет ситуации «перевод создан, но событие не записано».

### 2. Idempotency — PostgreSQL как source of truth
Проверка `findByIdempotencyKey()` — в PostgreSQL, не в Redis. Redis может быть недоступен, данные могут быть evicted. PostgreSQL — надёжный source of truth. Redis-кэш для fast-path проверки будет добавлен позже (оптимизация).

### 3. TODO заглушки для Sprint 2
`receiveAmount`, `exchangeRate`, `feeAmount` пока заглушены. В Sprint 2 добавим gRPC вызов к Pricing Service для валидации quote и получения актуальных данных. Заглушки помечены TODO для видимости.

### 4. BusinessException как abstract class
Паттерн: `throw UnsupportedCorridorException(...)` вместо `throw ResponseStatusException(422, ...)`. Исключение несёт семантику (тип ошибки, HTTP code, human-readable message), а маппинг в HTTP response — ответственность @RestControllerAdvice (Block 6). Разделение ответственностей: сервис бросает бизнес-ошибки, контроллер их транслирует.

**Примечание:** анонимные подклассы `BusinessException(...) {}` в validateTransfer — это временное решение. Если нужно больше типов — создай отдельные exception-классы по образцу.

---

## Проверка зависимостей

Jackson (`ObjectMapper`) должен быть доступен — Spring Boot автоматически создаёт bean. Если нет:

```kotlin
implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
```

Это должно уже быть в проекте (Spring Boot + Kotlin default).

---

## Проверка результата

1. Компилируется: `./gradlew :services:transfer-service:compileKotlin`
2. Убедись, что `BusinessException` — **не** abstract если ты используешь анонимные подклассы `BusinessException(...) {}`. Если abstract — создай конкретные подклассы для каждого типа ошибки (лучший подход).
3. Spring Boot запускается без ошибок — `TransferService` bean создан, все зависимости injected.

## Чего НЕ делать

- Не создавай REST контроллер — Block 5
- Не создавай error handling — Block 6
- Не подключай Redis — Block 7
- Не пиши тесты — Block 9, 10
