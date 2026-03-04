# Sprint 2 / Block 2 — Protobuf контракт + gRPC Server (Pricing Service)

## Контекст

Block 1 создал Pricing Service с REST endpoint. Block 2 добавляет **gRPC server** — высокопроизводительный inter-service API для Transfer Service.

**Зачем gRPC вместо REST между сервисами:** ~200 RPS синхронных вызовов при создании перевода. Protobuf бинарная сериализация + HTTP/2 multiplexing дают ~3x снижение latency vs REST/JSON (5ms vs 15ms per call). Для внешнего API (BFF → клиентское приложение) — REST, потому что браузеры нативно не поддерживают gRPC.

---

## Структура файлов

```
services/pricing-service/
├── build.gradle.kts                    ← обновить (protobuf plugin, gRPC deps)
├── src/main/
│   ├── proto/
│   │   └── pricing/v1/
│   │       └── pricing.proto           ← NEW: Protobuf contract
│   ├── kotlin/com/transferhub/pricing/
│   │   ├── Application.kt             ← обновить (запуск gRPC server)
│   │   └── grpc/
│   │       └── PricingGrpcService.kt   ← NEW: gRPC service implementation
```

---

## 1. build.gradle.kts — добавление Protobuf / gRPC

Добавь к существующему build.gradle.kts из Block 1:

```kotlin
import com.google.protobuf.gradle.*

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
    id("com.google.protobuf") version "0.9.4"      // ← NEW
}

// ... (group, version, application — оставить как было)

val grpcVersion = "1.65.1"
val grpcKotlinVersion = "1.4.1"
val protobufVersion = "4.28.3"

dependencies {
    // === Существующие из Block 1 ===
    // Ktor server (оставить все)
    // kotlinx.serialization (оставить)
    // Redis Lettuce (оставить)
    // Logging (оставить)

    // === NEW: gRPC + Protobuf ===

    // Protobuf runtime
    implementation("com.google.protobuf:protobuf-kotlin:$protobufVersion")

    // gRPC runtime
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    // grpc-kotlin: генерация корутинных stub'ов (suspend fun вместо StreamObserver)
    implementation("io.grpc:grpc-kotlin-stub:$grpcKotlinVersion")

    // Нужно для сгенерированных Java-классов (javax.annotation.Generated)
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // === Testing ===
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("io.grpc:grpc-testing:$grpcVersion")
}

// === Protobuf plugin configuration ===
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        // gRPC Java codegen
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
        // gRPC Kotlin codegen (suspend functions)
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

### Что генерируется из .proto

При `./gradlew generateProto` из pricing.proto создаются:

| Файл | Что содержит |
|------|-------------|
| `GetQuoteRequest.java` | Java message class (Protobuf runtime) |
| `GetQuoteRequestKt.kt` | Kotlin DSL builder |
| `PricingServiceGrpc.java` | Java gRPC stubs (не используем напрямую) |
| `PricingServiceGrpcKt.kt` | **Kotlin coroutine stubs** — наш основной интерфейс |

Мы наследуемся от `PricingServiceGrpcKt.PricingServiceCoroutineImplBase` — все методы становятся `suspend fun`.

---

## 2. pricing.proto — контракт

### `src/main/proto/pricing/v1/pricing.proto`

```protobuf
syntax = "proto3";

package com.transferhub.pricing.v1;

// Java/Kotlin кодогенерация
option java_multiple_files = true;
option java_package = "com.transferhub.pricing.grpc.generated";

/**
 * PricingService — межсервисный API для расчёта и валидации котировок.
 *
 * Потребитель: Transfer Service (при создании перевода).
 * Транспорт: gRPC (HTTP/2 + Protobuf).
 *
 * Два RPC-метода:
 * - GetQuote: рассчитать новую котировку (fee + rate + receive amount)
 * - ValidateQuote: проверить, жива ли существующая котировка (rate lock)
 */
service PricingService {
    // Рассчитать котировку
    rpc GetQuote (GetQuoteRequest) returns (QuoteResponse);

    // Проверить валидность существующей котировки
    rpc ValidateQuote (ValidateQuoteRequest) returns (ValidateQuoteResponse);
}

// ─── Requests ─────────────────────────────────────────────

message GetQuoteRequest {
    string source_country = 1;    // ISO 3166-1 alpha-2: "US"
    string dest_country = 2;      // "PH"
    string send_currency = 3;     // ISO 4217: "USD"
    string receive_currency = 4;  // "PHP"
    string send_amount = 5;       // Decimal as string: "100.00"
    string delivery_method = 6;   // "BANK_DEPOSIT"
    string sender_id = 7;         // UUID: для проверки лимитов и промо
}

message ValidateQuoteRequest {
    string quote_id = 1;          // UUID котировки
}

// ─── Responses ────────────────────────────────────────────

message QuoteResponse {
    string quote_id = 1;
    string send_amount = 2;       // "100.00"
    string receive_amount = 3;    // "5290.86"
    string exchange_rate = 4;     // "56.20"
    string fee_amount = 5;        // "5.99"
    string send_currency = 6;     // "USD"
    string receive_currency = 7;  // "PHP"
    int64 expires_at_epoch_ms = 8;
    DeliveryEstimate delivery_estimate = 9;
}

message ValidateQuoteResponse {
    bool is_valid = 1;
    QuoteResponse quote = 2;       // Populated if is_valid = true
    string rejection_reason = 3;   // Populated if is_valid = false
}

message DeliveryEstimate {
    int32 min_minutes = 1;
    int32 max_minutes = 2;
}
```

### Почему decimal как string, а не double/float

Финтех-правило: **никогда не передавай деньги как float/double**. IEEE 754 floating-point не может точно представить `0.1` — `0.1 + 0.2 = 0.30000000000000004`. В финансовых расчётах это ведёт к ошибкам округления. `string` → `BigDecimal` на стороне приложения = точное представление.

Protobuf не имеет нативного типа Decimal. Альтернатива — кастомный message Decimal с mantissa + exponent, но string проще и стандартнее для финтеха.

---

## 3. PricingGrpcService — gRPC implementation

### `src/main/kotlin/com/transferhub/pricing/grpc/PricingGrpcService.kt`

```kotlin
package com.transferhub.pricing.grpc

import com.transferhub.pricing.grpc.generated.*
import com.transferhub.pricing.model.QuoteRequest
import com.transferhub.pricing.service.CorridorNotSupportedException
import com.transferhub.pricing.service.DeliveryMethodNotAvailableException
import com.transferhub.pricing.service.InvalidAmountException
import com.transferhub.pricing.service.PricingService
import io.grpc.Status
import io.grpc.StatusRuntimeException
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * gRPC service implementation для Pricing.
 *
 * Наследуется от PricingServiceGrpcKt.PricingServiceCoroutineImplBase —
 * сгенерированного grpc-kotlin stub'а. Все методы — suspend fun.
 *
 * gRPC error handling через Status codes:
 * - INVALID_ARGUMENT (3) → ошибка валидации (аналог HTTP 400)
 * - NOT_FOUND (5) → ресурс не найден (аналог HTTP 404)
 * - INTERNAL (13) → внутренняя ошибка (аналог HTTP 500)
 *
 * Нет 1:1 маппинга gRPC Status ↔ HTTP Status.
 * gRPC codes спроектированы для inter-service коммуникации.
 */
class PricingGrpcService(
    private val pricingService: PricingService
) : PricingServiceGrpcKt.PricingServiceCoroutineImplBase() {

    /**
     * GetQuote — рассчитать новую котировку.
     */
    override suspend fun getQuote(request: GetQuoteRequest): QuoteResponse {
        logger.debug { "gRPC GetQuote: ${request.sourceCountry}→${request.destCountry}, amount=${request.sendAmount}" }

        // Validate required fields
        if (request.sourceCountry.isBlank() || request.destCountry.isBlank()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("source_country and dest_country are required")
                .asRuntimeException()
        }
        if (request.sendAmount.isBlank()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("send_amount is required")
                .asRuntimeException()
        }

        try {
            val quoteRequest = QuoteRequest(
                sourceCountry = request.sourceCountry,
                destCountry = request.destCountry,
                sendCurrency = request.sendCurrency,
                receiveCurrency = request.receiveCurrency,
                sendAmount = request.sendAmount,
                deliveryMethod = request.deliveryMethod,
                senderId = request.senderId
            )

            val quote = pricingService.calculateQuote(quoteRequest)

            return quoteResponse {
                quoteId = quote.quoteId
                sendAmount = quote.sendAmount
                receiveAmount = quote.receiveAmount
                exchangeRate = quote.exchangeRate
                feeAmount = quote.feeAmount
                sendCurrency = quote.sendCurrency
                receiveCurrency = quote.receiveCurrency
                expiresAtEpochMs = quote.expiresAtEpochMs
                deliveryEstimate = deliveryEstimate {
                    minMinutes = quote.deliveryEstimate.minMinutes
                    maxMinutes = quote.deliveryEstimate.maxMinutes
                }
            }
        } catch (e: CorridorNotSupportedException) {
            throw Status.INVALID_ARGUMENT
                .withDescription(e.message)
                .asRuntimeException()
        } catch (e: DeliveryMethodNotAvailableException) {
            throw Status.INVALID_ARGUMENT
                .withDescription(e.message)
                .asRuntimeException()
        } catch (e: InvalidAmountException) {
            throw Status.INVALID_ARGUMENT
                .withDescription(e.message)
                .asRuntimeException()
        } catch (e: Exception) {
            logger.error(e) { "Unexpected error in GetQuote" }
            throw Status.INTERNAL
                .withDescription("Internal error")
                .asRuntimeException()
        }
    }

    /**
     * ValidateQuote — проверить, жива ли котировка.
     *
     * Вызывается Transfer Service при создании перевода:
     * 1. Клиент запрашивает quote (GetQuote / REST)
     * 2. Клиент создаёт transfer с quote_id
     * 3. Transfer Service вызывает ValidateQuote(quote_id)
     * 4. Если valid → используем зафиксированные данные из quote
     * 5. Если expired → возвращаем ошибку клиенту ("quote expired, request new one")
     */
    override suspend fun validateQuote(request: ValidateQuoteRequest): ValidateQuoteResponse {
        logger.debug { "gRPC ValidateQuote: quoteId=${request.quoteId}" }

        if (request.quoteId.isBlank()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("quote_id is required")
                .asRuntimeException()
        }

        val result = pricingService.validateQuote(request.quoteId)

        return validateQuoteResponse {
            isValid = result.isValid
            if (result.quote != null) {
                quote = quoteResponse {
                    quoteId = result.quote.quoteId
                    sendAmount = result.quote.sendAmount
                    receiveAmount = result.quote.receiveAmount
                    exchangeRate = result.quote.exchangeRate
                    feeAmount = result.quote.feeAmount
                    sendCurrency = result.quote.sendCurrency
                    receiveCurrency = result.quote.receiveCurrency
                    expiresAtEpochMs = result.quote.expiresAtEpochMs
                    deliveryEstimate = deliveryEstimate {
                        minMinutes = result.quote.deliveryEstimate.minMinutes
                        maxMinutes = result.quote.deliveryEstimate.maxMinutes
                    }
                }
            }
            if (result.reason != null) {
                rejectionReason = result.reason
            }
        }
    }
}
```

### Kotlin DSL builders

`quoteResponse { ... }` и `validateQuoteResponse { ... }` — это Kotlin DSL, сгенерированный protobuf-kotlin. Вместо Java builder-pattern:

```java
// Java style (verbose)
QuoteResponse.newBuilder()
    .setQuoteId(quote.quoteId)
    .setSendAmount(quote.sendAmount)
    .build()

// Kotlin DSL (idiomatic)
quoteResponse {
    quoteId = quote.quoteId
    sendAmount = quote.sendAmount
}
```

---

## 4. Application.kt — запуск gRPC server

Обнови Application.kt из Block 1. Добавь запуск gRPC на отдельном порту:

```kotlin
// В начало файла — добавить импорты:
import com.transferhub.pricing.grpc.PricingGrpcService
import io.grpc.ServerBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// В конец функции module(), после routing { ... }:

    // --- gRPC Server (порт 50051) ---
    val grpcPort = 50051
    val grpcService = PricingGrpcService(pricingService)

    val grpcServer = ServerBuilder
        .forPort(grpcPort)
        .addService(grpcService)
        .build()

    // Запускаем gRPC server в отдельной корутине
    // (Ktor HTTP server и gRPC server работают параллельно)
    launch(Dispatchers.IO) {
        grpcServer.start()
        logger.info { "gRPC server started on port $grpcPort" }
        grpcServer.awaitTermination()
    }

    // Graceful shutdown для gRPC
    monitor.subscribe(ApplicationStopped) {
        logger.info { "Shutting down gRPC server..." }
        grpcServer.shutdown()
    }
```

### Два порта — два протокола

Pricing Service слушает на **двух портах**:
- `:8081` — HTTP/REST (Ktor Netty) — для внешнего API (BFF → клиентское приложение)
- `:50051` — gRPC (gRPC Netty) — для межсервисного API (Transfer Service)

Это стандартный подход: REST для external consumers, gRPC для internal. В Kubernetes оба порта прописываются в Service и Deployment.

---

## 5. Генерация кода и проверка

### Build:

```bash
cd services/pricing-service

# Генерация Protobuf/gRPC классов
./gradlew generateProto

# Проверить сгенерированные файлы
ls build/generated/source/proto/main/grpckt/com/transferhub/pricing/grpc/generated/
# → PricingServiceGrpcKt.kt (coroutine stubs)

ls build/generated/source/proto/main/kotlin/com/transferhub/pricing/grpc/generated/
# → GetQuoteRequestKt.kt, QuoteResponseKt.kt, ...

# Полная сборка
./gradlew build
```

### Запуск:

```bash
./gradlew run
# → Pricing Service started on port 8081
# → gRPC server started on port 50051
```

### Тест gRPC через grpcurl:

```bash
# Установить grpcurl: https://github.com/fullstorydev/grpcurl
# brew install grpcurl (macOS)

# List services (reflection должен быть включён — см. ниже)
grpcurl -plaintext localhost:50051 list

# GetQuote
grpcurl -plaintext -d '{
  "source_country": "US",
  "dest_country": "PH",
  "send_currency": "USD",
  "receive_currency": "PHP",
  "send_amount": "100.00",
  "delivery_method": "BANK_DEPOSIT",
  "sender_id": "550e8400-e29b-41d4-a716-446655440000"
}' localhost:50051 com.transferhub.pricing.v1.PricingService/GetQuote

# Ожидаемый ответ:
# {
#   "quoteId": "...",
#   "sendAmount": "100.00",
#   "receiveAmount": "5290.86",
#   "exchangeRate": "56.20",
#   "feeAmount": "5.99",
#   ...
# }

# ValidateQuote (подставь quoteId)
grpcurl -plaintext -d '{"quote_id": "QUOTE_ID_HERE"}' \
  localhost:50051 com.transferhub.pricing.v1.PricingService/ValidateQuote
```

### gRPC Server Reflection (для grpcurl / dev tools)

Чтобы grpcurl мог обнаруживать сервисы без .proto файла, добавь reflection:

```kotlin
// build.gradle.kts
implementation("io.grpc:grpc-services:$grpcVersion")

// Application.kt — при создании grpcServer
import io.grpc.protobuf.services.ProtoReflectionService

val grpcServer = ServerBuilder
    .forPort(grpcPort)
    .addService(grpcService)
    .addService(ProtoReflectionService.newInstance())  // ← reflection
    .build()
```

Reflection включается только в dev/staging. В production можно отключить.

---

## 6. Примечание: launch в module()

Для вызова `launch` в `Application.module()` нужен CoroutineScope. Ktor Application уже является CoroutineScope. Если компилятор ругается — оберни в:

```kotlin
environment.monitor.subscribe(ApplicationStarted) {
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    scope.launch {
        grpcServer.start()
        logger.info { "gRPC server started on port $grpcPort" }
    }
}
```

---

## Чего НЕ делать в этом блоке

- Не делай gRPC client в Transfer Service — Block 3
- Не оптимизируй gRPC (interceptors, deadline, metadata) — Sprint 3+
- Не добавляй TLS/mTLS на gRPC — Sprint 5 (security)
- Не добавляй gRPC health check — Block 10 (Docker)
