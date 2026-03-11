# Sprint 2 / Block 3 — gRPC Client в Transfer Service

## Контекст

Block 2 создал gRPC server в Pricing Service (:50051). Block 3 добавляет **gRPC client** в Transfer Service, который вызывает `ValidateQuote` при создании перевода.

**Текущий flow (Sprint 1):** POST /api/v1/transfers → сохранение с hardcoded данными (quote не валидируется).

**Новый flow (после Block 3):** POST /api/v1/transfers с `quote_id` → gRPC ValidateQuote → если valid, берём данные из quote → сохранение с реальными fee/rate/receiveAmount.

---

## Структура файлов

```
services/transfer-service/
├── build.gradle.kts                         ← обновить (protobuf, gRPC deps)
├── src/main/
│   ├── proto/pricing/v1/
│   │   └── pricing.proto                    ← COPY из pricing-service (shared contract)
│   ├── kotlin/com/transferhub/transfer/
│   │   ├── client/
│   │   │   └── PricingClient.kt             ← NEW: gRPC client wrapper
│   │   ├── config/
│   │   │   └── GrpcConfig.kt                ← NEW: gRPC channel bean
│   │   ├── service/
│   │   │   └── TransferService.kt           ← MODIFY: integrate quote validation
│   │   └── model/
│   │       └── CreateTransferCommand.kt     ← MODIFY: add quoteId field
```

---

## 1. Shared Proto — подход к контракту

В mono-repo два варианта:

**Вариант A (простой, MVP):** копируем pricing.proto в Transfer Service. Оба сервиса генерируют stubs из одного .proto файла. Минус — дублирование, нужно синхронизировать вручную.

**Вариант B (production):** отдельный Gradle-модуль `proto/` с .proto файлами, от которого зависят оба сервиса. Или публикация сгенерированных stubs как Maven-артефакта.

**Для Sprint 2 используем Вариант A** — скопируй файл `pricing.proto` из `services/pricing-service/src/main/proto/pricing/v1/` в `services/transfer-service/src/main/proto/pricing/v1/`. Одинаковый файл, одинаковый package, одинаковые сгенерированные классы.

В Sprint 3+ можно вынести в shared module (хороший кейс для "Эволюция решений").

---

## 2. build.gradle.kts — добавление Protobuf/gRPC зависимостей

Добавь к существующему build.gradle.kts Transfer Service:

```kotlin
// В plugins:
id("com.google.protobuf") version "0.9.4"

// Версии (добавить в блок переменных или version catalog):
val grpcVersion = "1.65.1"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "4.28.3"

// В dependencies:

// gRPC client
implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
implementation("io.grpc:grpc-protobuf:$grpcVersion")
implementation("io.grpc:grpc-stub:$grpcVersion")
implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")
implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")
compileOnly("org.apache.tomcat:annotations-api:6.0.53")

// Resilience4j — circuit breaker для gRPC вызовов
implementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")
implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.2.0")

// Testing
testImplementation("io.grpc:grpc-testing:$grpcVersion")

// Protobuf plugin config (аналогично Pricing Service):
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:$grpcKotlinVersion:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}
```

---

## 3. GrpcConfig — Spring Bean для gRPC Channel

### `src/main/kotlin/com/transferhub/transfer/config/GrpcConfig.kt`

```kotlin
package com.transferhub.transfer.config

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * gRPC channel configuration.
 *
 * ManagedChannel — переиспользуемое соединение к gRPC серверу.
 * Под капотом: HTTP/2 connection с multiplexing (один TCP-канал, много потоков).
 * НЕ создавать новый channel на каждый вызов — это как открывать новый DB connection на каждый запрос.
 *
 * В Spring: channel создаётся как Bean, инжектится в client.
 */
@Configuration
class GrpcConfig(
    @Value("\${grpc.pricing.host:localhost}") private val host: String,
    @Value("\${grpc.pricing.port:50051}") private val port: Int
) {
    private var channel: ManagedChannel? = null

    @Bean
    fun pricingChannel(): ManagedChannel {
        val ch = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()           // dev: без TLS. Production: TLS/mTLS.
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .build()

        channel = ch
        logger.info { "gRPC channel to Pricing Service: $host:$port" }
        return ch
    }

    @PreDestroy
    fun shutdown() {
        channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
        logger.info { "gRPC channel shut down" }
    }
}
```

### application.yml — добавить gRPC конфигурацию

```yaml
grpc:
  pricing:
    host: ${PRICING_GRPC_HOST:localhost}
    port: ${PRICING_GRPC_PORT:50051}
    timeout-ms: 3000              # deadline для gRPC вызова
```

---

## 4. PricingClient — gRPC client с Circuit Breaker

### `src/main/kotlin/com/transferhub/transfer/client/PricingClient.kt`

```kotlin
package com.transferhub.transfer.client

import com.transferhub.pricing.grpc.generated.PricingServiceGrpcKt
import com.transferhub.pricing.grpc.generated.getQuoteRequest
import com.transferhub.pricing.grpc.generated.validateQuoteRequest
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.kotlin.circuitbreaker.executeSuspendFunction
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

private val logger = KotlinLogging.logger {}

/**
 * Результат валидации котировки.
 * Application-level DTO — не зависит от gRPC/Protobuf generated classes.
 * Это важно: бизнес-логика не должна зависеть от транспортного протокола.
 */
data class QuoteData(
    val quoteId: String,
    val sendAmount: BigDecimal,
    val receiveAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val feeAmount: BigDecimal,
    val sendCurrency: String,
    val receiveCurrency: String,
    val expiresAtEpochMs: Long
)

/**
 * gRPC client для Pricing Service.
 *
 * Обёрнут в Circuit Breaker (Resilience4j):
 * - Если Pricing Service недоступен → circuit opens после 5 failures за 30 сек
 * - В open state → сразу PricingUnavailableException (fail fast, не ждём timeout)
 * - Через 10 сек → half-open, пробуем один вызов
 * - Если успешен → circuit closes
 *
 * На собеседовании: "Мы используем circuit breaker для gRPC вызова к Pricing Service.
 * Если Pricing падает, circuit открывается через 5 ошибок, и Transfer Service
 * сразу возвращает 503 вместо того чтобы ждать timeout 3 сек на каждом запросе.
 * Это предотвращает каскадный отказ."
 */
@Component
class PricingClient(
    private val pricingChannel: ManagedChannel
) {
    // Coroutine stub — все вызовы suspend fun
    private val stub = PricingServiceGrpcKt.PricingServiceCoroutineStub(pricingChannel)

    // Circuit Breaker
    private val circuitBreaker = CircuitBreaker.of(
        "pricing-service",
        CircuitBreakerConfig.custom()
            .failureRateThreshold(50.0f)           // 50% failure rate → open
            .minimumNumberOfCalls(5)               // минимум 5 вызовов для оценки
            .slidingWindowSize(10)                 // окно: 10 последних вызовов
            .waitDurationInOpenState(Duration.ofSeconds(10))  // open → half-open через 10 сек
            .permittedNumberOfCallsInHalfOpenState(2)         // в half-open пробуем 2 вызова
            .build()
    )

    /**
     * Валидация котировки перед созданием перевода.
     *
     * @throws QuoteExpiredException если котировка не найдена или expired
     * @throws PricingUnavailableException если Pricing Service недоступен
     */
    suspend fun validateQuote(quoteId: String): QuoteData {
        logger.debug { "Validating quote: $quoteId" }

        return try {
            circuitBreaker.executeSuspendFunction {
                val request = validateQuoteRequest {
                    this.quoteId = quoteId
                }

                val response = stub.validateQuote(request)

                if (!response.isValid) {
                    throw QuoteExpiredException(quoteId, response.rejectionReason)
                }

                val quote = response.quote
                QuoteData(
                    quoteId = quote.quoteId,
                    sendAmount = BigDecimal(quote.sendAmount),
                    receiveAmount = BigDecimal(quote.receiveAmount),
                    exchangeRate = BigDecimal(quote.exchangeRate),
                    feeAmount = BigDecimal(quote.feeAmount),
                    sendCurrency = quote.sendCurrency,
                    receiveCurrency = quote.receiveCurrency,
                    expiresAtEpochMs = quote.expiresAtEpochMs
                )
            }
        } catch (e: QuoteExpiredException) {
            throw e  // бизнес-ошибка — пробрасываем as-is
        } catch (e: StatusRuntimeException) {
            when (e.status.code) {
                Status.Code.INVALID_ARGUMENT -> throw QuoteExpiredException(quoteId, e.status.description)
                Status.Code.UNAVAILABLE -> throw PricingUnavailableException("Pricing Service unavailable", e)
                else -> throw PricingUnavailableException("gRPC error: ${e.status}", e)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to validate quote $quoteId" }
            throw PricingUnavailableException("Failed to contact Pricing Service", e)
        }
    }
}

// --- Exceptions ---

class QuoteExpiredException(quoteId: String, reason: String?) :
    RuntimeException("Quote $quoteId is invalid: ${reason ?: "expired or not found"}")

class PricingUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
```

---

## 5. Изменения в TransferService — интеграция quote

### Изменения в `CreateTransferCommand` / request DTO:

Добавь `quoteId` в CreateTransferRequest (если ещё нет):

```kotlin
// В CreateTransferRequest (или CreateTransferCommand):
data class CreateTransferRequest(
    // ... существующие поля ...
    val quoteId: String     // NEW: UUID котировки из Pricing Service
)
```

### Изменения в TransferService.createTransfer():

```kotlin
// В TransferService — инжектируем PricingClient:

@Service
class TransferService(
    private val transferRepository: TransferRepository,
    private val outboxRepository: OutboxRepository,
    private val recipientRepository: RecipientRepository,
    private val pricingClient: PricingClient      // ← NEW
) {

    @Transactional
    suspend fun createTransfer(command: CreateTransferCommand, idempotencyKey: UUID): TransferResult {
        // 1. Idempotency check (без изменений)
        val existing = transferRepository.findByIdempotencyKey(idempotencyKey)
        if (existing != null) {
            return TransferResult(existing.toResponse(), isNew = false)
        }

        // 2. Validate recipient (без изменений)
        val recipient = recipientRepository.findById(command.recipientId)
            ?: throw RecipientNotFoundException(command.recipientId)
        // ... sender ownership check ...

        // 3. NEW: Validate quote через gRPC → Pricing Service
        val quoteData = pricingClient.validateQuote(command.quoteId)

        // 4. Validate corridor consistency: quote corridor == request corridor
        // (защита от подмены — клиент не может использовать quote для другого коридора)
        if (quoteData.sendCurrency != command.sendCurrency ||
            quoteData.receiveCurrency != command.receiveCurrency) {
            throw QuoteCorridorMismatchException(
                quoteId = command.quoteId,
                quoteCurrency = "${quoteData.sendCurrency}→${quoteData.receiveCurrency}",
                requestCurrency = "${command.sendCurrency}→${command.receiveCurrency}"
            )
        }

        // 5. Create transfer with data from validated quote
        val transfer = Transfer(
            // ... id, senderId, recipientId ...
            sendAmount = quoteData.sendAmount,
            sendCurrency = quoteData.sendCurrency,
            receiveAmount = quoteData.receiveAmount,   // ← from quote (was hardcoded)
            receiveCurrency = quoteData.receiveCurrency,
            exchangeRate = quoteData.exchangeRate,       // ← from quote
            fee = quoteData.feeAmount,                   // ← from quote
            quoteId = command.quoteId,                   // ← save for audit
            status = TransferStatus.CREATED,
            // ...
        )

        // 6. Save transfer + outbox event (без изменений в логике)
        val saved = transferRepository.save(transfer)

        val outboxEvent = OutboxEvent(
            entityType = "TRANSFER",
            entityId = saved.id,
            eventType = "transfer.created",
            payload = /* ... */,
            kafkaTopic = "transfer.events"
        )
        outboxRepository.save(outboxEvent)

        return TransferResult(saved.toResponse(), isNew = true)
    }
}
```

### Новый exception + handler

```kotlin
// Добавь exception:
class QuoteCorridorMismatchException(quoteId: String, quoteCurrency: String, requestCurrency: String) :
    RuntimeException("Quote $quoteId currency ($quoteCurrency) doesn't match request ($requestCurrency)")

// В GlobalExceptionHandler (@RestControllerAdvice) добавь:
@ExceptionHandler(QuoteExpiredException::class)
fun handleQuoteExpired(ex: QuoteExpiredException): ProblemDetail {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,   // 409: quote expired, request new one
        ex.message ?: "Quote expired"
    ).also {
        it.title = "Quote Expired"
        it.setProperty("type", "https://api.transferhub.com/errors/quote-expired")
    }
}

@ExceptionHandler(PricingUnavailableException::class)
fun handlePricingUnavailable(ex: PricingUnavailableException): ProblemDetail {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE,   // 503: dependency down
        "Pricing service is temporarily unavailable. Please try again."
    ).also {
        it.title = "Service Unavailable"
    }
}

@ExceptionHandler(QuoteCorridorMismatchException::class)
fun handleQuoteCorridorMismatch(ex: QuoteCorridorMismatchException): ProblemDetail {
    return ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        ex.message ?: "Quote corridor mismatch"
    ).also {
        it.title = "Quote Corridor Mismatch"
    }
}
```

---

## 6. Transfer entity — добавление полей

Если ещё нет — добавь в Transfer entity и миграцию:

### Flyway миграция `V004__add_quote_fields.sql`:

```sql
-- Добавляем поля из quote, которые раньше были hardcoded/null
-- exchange_rate и fee уже могут существовать — проверь текущую схему

-- Если quote_id ещё нет:
ALTER TABLE transfers ADD COLUMN IF NOT EXISTS quote_id VARCHAR(36);

-- Индекс для audit: найти все переводы по quote
CREATE INDEX IF NOT EXISTS idx_transfers_quote_id ON transfers (quote_id);
```

Адаптируй миграцию под текущую схему — если `exchange_rate`, `fee`, `receive_amount` уже есть как колонки, нужен только `quote_id`.

---

## 7. Проверка end-to-end

```bash
# 1. Запустить Pricing Service (Block 1+2)
cd services/pricing-service && ./gradlew run
# → HTTP :8081, gRPC :50051

# 2. Запустить Transfer Service
cd services/transfer-service && ./gradlew bootRun

# 3. Получить quote
curl "http://localhost:8081/api/v1/quotes?\
source_country=US&dest_country=PH&\
send_currency=USD&receive_currency=PHP&\
send_amount=200.00&delivery_method=BANK_DEPOSIT&\
sender_id=SENDER_UUID"
# → {"quoteId": "abc-123", "receiveAmount": "10907.72", ...}

# 4. Создать перевод с quote_id (в течение 30 сек!)
curl -X POST http://localhost:8080/api/v1/transfers \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: $(uuidgen)" \
  -H "X-Sender-Id: SENDER_UUID" \
  -d '{
    "quoteId": "abc-123",
    "recipientId": "RECIPIENT_UUID",
    "sourceCountry": "US",
    "destCountry": "PH",
    "sendCurrency": "USD",
    "receiveCurrency": "PHP",
    "sendAmount": "200.00",
    "deliveryMethod": "BANK_DEPOSIT"
  }'
# → 201 Created, receiveAmount = 10907.72 (from quote!)

# 5. Попробовать с expired quote (подождать 30 сек)
# → 409 Conflict: "Quote expired"

# 6. Попробовать без Pricing Service (остановить)
# → 503 Service Unavailable (circuit breaker)
```

---

## Чего НЕ делать

- Не настраивай TLS/mTLS — Sprint 5
- Не делай gRPC deadline/timeout через gRPC interceptors — достаточно circuit breaker
- Не вынoси proto в shared module — Вариант A (копирование) достаточен для Sprint 2
- Не делай gRPC streaming — unary calls достаточны для ValidateQuote
