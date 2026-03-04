# Block 5 — REST Controller: POST /api/v1/transfers

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 5.** Предыдущие блоки завершены:
- Block 1: Flyway-миграции
- Block 2: Domain model (Transfer, TransferStatus, OutboxEvent, Recipient, etc.)
- Block 3: Repositories
- Block 4: TransferService (createTransfer, getTransfer, listTransfers)

## Задача

Создать REST контроллер, request/response DTO, маппинг. POST endpoint для создания перевода. GET endpoints будут частично здесь (заготовки), полноценно — в Block 7 и 8.

## Структура файлов

Создать в `services/transfer-service/src/main/kotlin/com/transferhub/transfer/`:

```
api/
  controller/
    TransferController.kt
  dto/
    request/
      CreateTransferRequest.kt
    response/
      TransferResponse.kt
      PaginatedResponse.kt
  mapper/
    TransferMapper.kt
```

---

## Что создать

### 1. Request DTO

#### api/dto/request/CreateTransferRequest.kt

```kotlin
package com.transferhub.transfer.api.dto.request

import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.util.UUID

/**
 * HTTP request body для POST /api/v1/transfers.
 *
 * Bean Validation аннотации проверяют формат данных.
 * Бизнес-валидация (коридор поддерживается, лимиты) — в TransferService.
 *
 * JSON naming: snake_case (соответствует API-контракту из api-contracts.md).
 * Jackson автоматически маппит если настроен PropertyNamingStrategies.SNAKE_CASE
 * или через @JsonProperty на каждом поле.
 */
data class CreateTransferRequest(

    @field:NotNull(message = "quote_id is required")
    val quoteId: UUID? = null,

    @field:NotNull(message = "recipient_id is required")
    val recipientId: UUID? = null,

    @field:NotBlank(message = "delivery_method is required")
    @field:Pattern(
        regexp = "BANK_DEPOSIT|CASH_PICKUP|MOBILE_WALLET",
        message = "delivery_method must be one of: BANK_DEPOSIT, CASH_PICKUP, MOBILE_WALLET"
    )
    val deliveryMethod: String? = null,

    // --- В MVP: send_amount и валюты передаются клиентом.
    // В production: берутся из quote. Но для MVP проще принимать явно. ---

    @field:NotNull(message = "send_amount is required")
    @field:Positive(message = "send_amount must be positive")
    @field:Digits(integer = 13, fraction = 2, message = "send_amount must have at most 2 decimal places")
    val sendAmount: BigDecimal? = null,

    @field:NotBlank(message = "send_currency is required")
    @field:Size(min = 3, max = 3, message = "send_currency must be ISO 4217 (3 chars)")
    val sendCurrency: String? = null,

    @field:NotBlank(message = "receive_currency is required")
    @field:Size(min = 3, max = 3, message = "receive_currency must be ISO 4217 (3 chars)")
    val receiveCurrency: String? = null,

    @field:NotBlank(message = "source_country is required")
    @field:Size(min = 2, max = 2, message = "source_country must be ISO 3166-1 alpha-2 (2 chars)")
    val sourceCountry: String? = null,

    @field:NotBlank(message = "dest_country is required")
    @field:Size(min = 2, max = 2, message = "dest_country must be ISO 3166-1 alpha-2 (2 chars)")
    val destCountry: String? = null,

    val purpose: String? = null,

    val referenceNote: String? = null
)
```

**Почему поля nullable с `= null`:** Kotlin data class + Bean Validation + Jackson: если клиент не отправит поле, Jackson присвоит null. `@NotNull` поймает это. Если поля `val quoteId: UUID` (non-null) — Jackson выбросит deserialization error ДО валидации, и клиент получит нечитаемую ошибку. С nullable полями + @NotNull — получаем красивый validation error.

---

### 2. Response DTO

#### api/dto/response/TransferResponse.kt

```kotlin
package com.transferhub.transfer.api.dto.response

import java.math.BigDecimal
import java.time.Instant

/**
 * HTTP response body — представление перевода для клиента.
 *
 * Отдельный от entity: не все поля entity нужны клиенту, naming может отличаться,
 * клиент не должен видеть internal fields (version, idempotency_key, outbox status).
 */
data class TransferResponse(
    val id: String,
    val status: String,
    val displayStatus: String,    // User-friendly статус (COMPLIANCE_CHECK → "PROCESSING")
    val sendAmount: String,       // String, не BigDecimal — клиенту нужен форматированный вывод
    val sendCurrency: String,
    val receiveAmount: String,
    val receiveCurrency: String,
    val exchangeRate: String,
    val feeAmount: String,
    val deliveryMethod: String,
    val sourceCountry: String,
    val destCountry: String,
    val recipient: RecipientBrief,
    val createdAt: Instant,
    val statusReason: String? = null
)

/** Краткая информация о получателе в ответе перевода */
data class RecipientBrief(
    val id: String,
    val firstName: String,
    val lastName: String
)
```

#### api/dto/response/PaginatedResponse.kt

```kotlin
package com.transferhub.transfer.api.dto.response

/**
 * Обёртка для cursor-based pagination.
 * Используется для GET /api/v1/transfers.
 */
data class PaginatedResponse<T>(
    val items: List<T>,
    val pagination: PaginationMeta
)

data class PaginationMeta(
    val nextCursor: String?,
    val hasMore: Boolean
)
```

---

### 3. Mapper

#### api/mapper/TransferMapper.kt

```kotlin
package com.transferhub.transfer.api.mapper

import com.transferhub.transfer.api.dto.request.CreateTransferRequest
import com.transferhub.transfer.api.dto.response.RecipientBrief
import com.transferhub.transfer.api.dto.response.TransferResponse
import com.transferhub.transfer.domain.model.Recipient
import com.transferhub.transfer.domain.model.Transfer
import com.transferhub.transfer.service.dto.CreateTransferCommand
import java.util.UUID

/**
 * Маппинг между слоями: HTTP ↔ Service ↔ Domain.
 *
 * Реализован как object (singleton) с extension functions — идиоматический Kotlin подход.
 * Не используем MapStruct (избыточно для нашего масштаба) и не используем отдельный
 * Spring bean (нет зависимостей, нет state).
 */
object TransferMapper {

    /**
     * HTTP Request → Service Command.
     * senderId приходит из JWT (контроллер извлекает), не из request body.
     */
    fun CreateTransferRequest.toCommand(
        senderId: UUID,
        idempotencyKey: UUID
    ): CreateTransferCommand = CreateTransferCommand(
        idempotencyKey = idempotencyKey,
        senderId = senderId,
        recipientId = recipientId!!,   // validated as @NotNull
        quoteId = quoteId!!,
        sendAmount = sendAmount!!,
        sendCurrency = sendCurrency!!,
        receiveCurrency = receiveCurrency!!,
        sourceCountry = sourceCountry!!,
        destCountry = destCountry!!,
        deliveryMethod = deliveryMethod!!,
        purpose = purpose,
        referenceNote = referenceNote
    )

    /**
     * Domain Entity → HTTP Response.
     * recipient подгружается отдельно — в MVP можно передать null и показать минимум.
     */
    fun Transfer.toResponse(recipient: Recipient? = null): TransferResponse = TransferResponse(
        id = id.toString(),
        status = status.value,
        displayStatus = status.displayStatus(),
        sendAmount = sendAmount.toPlainString(),
        sendCurrency = sendCurrency,
        receiveAmount = receiveAmount.toPlainString(),
        receiveCurrency = receiveCurrency,
        exchangeRate = exchangeRate.toPlainString(),
        feeAmount = feeAmount.toPlainString(),
        deliveryMethod = deliveryMethod.name,
        sourceCountry = sourceCountry,
        destCountry = destCountry,
        recipient = recipient?.toBrief() ?: RecipientBrief(
            id = recipientId.toString(),
            firstName = "—",
            lastName = "—"
        ),
        createdAt = createdAt,
        statusReason = statusReason
    )

    fun Recipient.toBrief(): RecipientBrief = RecipientBrief(
        id = id.toString(),
        firstName = firstName,
        lastName = lastName
    )
}
```

---

### 4. Controller

#### api/controller/TransferController.kt

```kotlin
package com.transferhub.transfer.api.controller

import com.transferhub.transfer.api.dto.request.CreateTransferRequest
import com.transferhub.transfer.api.dto.response.PaginatedResponse
import com.transferhub.transfer.api.dto.response.PaginationMeta
import com.transferhub.transfer.api.dto.response.TransferResponse
import com.transferhub.transfer.api.mapper.TransferMapper.toCommand
import com.transferhub.transfer.api.mapper.TransferMapper.toResponse
import com.transferhub.transfer.repository.RecipientRepository
import com.transferhub.transfer.service.TransferService
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val transferService: TransferService,
    private val recipientRepository: RecipientRepository
) {
    private val log = LoggerFactory.getLogger(TransferController::class.java)

    /**
     * POST /api/v1/transfers — создание перевода.
     *
     * Headers:
     *   X-Idempotency-Key: UUID (required) — защита от дублирования
     *
     * Returns:
     *   201 Created + Location header — перевод создан
     *   200 OK — idempotency hit (тот же ключ, возвращаем cached result)
     *   400 — validation error
     *   422 — business rule violation
     */
    @PostMapping
    fun createTransfer(
        @Valid @RequestBody request: CreateTransferRequest,
        @RequestHeader("X-Idempotency-Key") idempotencyKey: UUID,
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?
        // TODO Sprint 5: заменить X-Sender-Id на извлечение из JWT token
    ): ResponseEntity<TransferResponse> {

        // В MVP: senderId из header. В production: из JWT после Spring Security.
        val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")

        val command = request.toCommand(senderId = senderId, idempotencyKey = idempotencyKey)
        val (transfer, isNew) = transferService.createTransfer(command)

        // Подгрузить recipient для response
        val recipient = recipientRepository.findRecipientById(transfer.recipientId)
        val response = transfer.toResponse(recipient)

        return if (isNew) {
            log.info("Transfer created: id={}", transfer.id)
            ResponseEntity
                .created(URI.create("/api/v1/transfers/${transfer.id}"))
                .body(response)
        } else {
            log.info("Idempotency hit: key={}, transferId={}", idempotencyKey, transfer.id)
            ResponseEntity.ok(response)
        }
    }

    /**
     * GET /api/v1/transfers/{id} — получить перевод по ID.
     * Redis cache добавится в Block 7.
     */
    @GetMapping("/{id}")
    fun getTransfer(
        @PathVariable id: UUID
    ): ResponseEntity<TransferResponse> {
        val transfer = transferService.getTransfer(id)
        val recipient = recipientRepository.findRecipientById(transfer.recipientId)
        return ResponseEntity.ok(transfer.toResponse(recipient))
    }

    /**
     * GET /api/v1/transfers — список переводов с cursor-based pagination.
     * Полная реализация в Block 8.
     */
    @GetMapping
    fun listTransfers(
        @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<PaginatedResponse<TransferResponse>> {

        val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")
        val (transfers, nextCursor) = transferService.listTransfers(senderId, cursor, limit)

        val items = transfers.map { transfer ->
            val recipient = recipientRepository.findRecipientById(transfer.recipientId)
            transfer.toResponse(recipient)
        }

        return ResponseEntity.ok(
            PaginatedResponse(
                items = items,
                pagination = PaginationMeta(
                    nextCursor = nextCursor,
                    hasMore = nextCursor != null
                )
            )
        )
    }
}
```

---

## Настройка Jackson для snake_case

API-контракт использует `snake_case` в JSON (send_amount, quote_id). Kotlin properties — camelCase.

В `application.yml` добавь (если нет):

```yaml
spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
    serialization:
      write-dates-as-timestamps: false    # ISO 8601 для дат
    deserialization:
      fail-on-unknown-properties: false   # не падать на неизвестных полях
```

**Или** в configuration class:

```kotlin
@Configuration
class JacksonConfig {
    @Bean
    fun jacksonCustomizer() = Jackson2ObjectMapperBuilderCustomizer { builder ->
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
    }
}
```

Выбери один подход. `application.yml` — проще.

---

## Проверка результата

1. Компилируется без ошибок.
2. Приложение запускается (Docker Compose up для PostgreSQL + Redis).
3. Тест через curl (нужен существующий recipient в БД — можно вставить вручную через SQL или создать seed data):

```bash
# Создать тестового получателя (выполнить в PostgreSQL)
# INSERT INTO recipients (id, sender_id, first_name, last_name, country, created_at, updated_at)
# VALUES ('11111111-1111-1111-1111-111111111111', '00000000-0000-0000-0000-000000000001',
#         'Maria', 'Santos', 'PH', now(), now());

# Создать перевод
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "X-Sender-Id: 00000000-0000-0000-0000-000000000001" \
  -d '{
    "quote_id": "22222222-2222-2222-2222-222222222222",
    "recipient_id": "11111111-1111-1111-1111-111111111111",
    "delivery_method": "BANK_DEPOSIT",
    "send_amount": 200.00,
    "send_currency": "USD",
    "receive_currency": "PHP",
    "source_country": "US",
    "dest_country": "PH"
  }'

# Ожидаемый ответ: 201 Created с телом TransferResponse
```

4. Повторный запрос с тем же X-Idempotency-Key → 200 OK с тем же transfer.

5. Запрос без обязательного поля → 400 (пока без красивого Problem Details — это Block 6).

## Чего НЕ делать

- Не реализуй @RestControllerAdvice — Block 6
- Не подключай Redis cache — Block 7
- Не углубляйся в pagination — Block 8 доработает
- Не пиши тесты — Block 9, 10
