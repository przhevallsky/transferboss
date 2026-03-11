# Sprint 2 / Block 1 — Pricing Service (Ktor): REST + Redis

## Контекст

**TransferHub** — платформа международных денежных переводов. Sprint 0 создал скелет Pricing Service на Ktor (Gradle, Application.kt, /health endpoint). Sprint 2 превращает скелет в рабочий сервис котировок.

**Pricing Service** — latency-critical сервис (p99 < 150ms). Рассчитывает fee + exchange rate + receive_amount. Котировка (quote) сохраняется в Redis с TTL 30 сек (rate lock). Написан на Ktor (не Spring Boot) — обоснование в ADR-007: cold start 1.5 сек vs 8 сек, память 120MB vs 300MB, coroutines-native.

**Что делает этот блок:** полноценный REST endpoint `GET /api/v1/quotes`, pricing logic, Redis cache для quotes.

---

## Структура файлов

```
services/pricing-service/
├── build.gradle.kts              ← обновить (Redis, serialization)
├── src/main/
│   ├── kotlin/com/transferhub/pricing/
│   │   ├── Application.kt        ← обновить (plugins, routing)
│   │   ├── config/
│   │   │   └── RedisConfig.kt    ← NEW: Redis client setup
│   │   ├── model/
│   │   │   ├── Quote.kt          ← NEW: data classes
│   │   │   └── QuoteRequest.kt   ← NEW: request validation
│   │   ├── service/
│   │   │   ├── PricingService.kt ← NEW: fee/rate calculation
│   │   │   └── QuoteCacheService.kt ← NEW: Redis cache
│   │   └── routes/
│   │       └── QuoteRoutes.kt    ← NEW: routing DSL
│   └── resources/
│       └── application.conf      ← обновить (redis config)
```

---

## 1. build.gradle.kts — обновление зависимостей

Добавь к существующему скелету Sprint 0:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("io.ktor.plugin") version "2.3.12"
}

group = "com.transferhub"
version = "0.1.0"

application {
    mainClass.set("com.transferhub.pricing.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("io.ktor:ktor-server-default-headers-jvm")

    // Kotlinx serialization (Ktor-native JSON, не Jackson)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Redis (Lettuce — async/coroutine-compatible)
    implementation("io.lettuce:lettuce-core:6.4.1.RELEASE")

    // Kotlinx coroutines (уже транзитивно через Ktor, но явно для kotlinx-coroutines-jdk8)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

    // Testing
    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}

// Ktor fat JAR
ktor {
    fatJar {
        archiveFileName.set("pricing-service.jar")
    }
}
```

### Почему kotlinx.serialization, а не Jackson

В Ktor-проекте предпочтительнее `kotlinx.serialization`:
- Compile-time кодогенерация (не рефлексия как Jackson) → быстрее, меньше памяти
- Kotlin-native: data classes с @Serializable, sealed classes, null safety
- Нативная интеграция с Ktor ContentNegotiation
- Jackson — стандарт в Spring-мире, но в Ktor это лишняя зависимость

### Почему Lettuce, а не Jedis

- Lettuce — non-blocking, thread-safe, работает с coroutines через `kotlinx-coroutines-jdk8` (CompletableFuture → suspend)
- Jedis — blocking, нужен connection pool. Не вписывается в coroutine-based Ktor
- В Spring мире оба варианта, но Ktor = coroutines = Lettuce

---

## 2. application.conf (HOCON — стандарт Ktor)

Ktor использует HOCON (через Typesafe Config), не YAML. Файл `src/main/resources/application.conf`:

```hocon
ktor {
    deployment {
        port = 8081
        port = ${?PORT}
    }
    application {
        modules = [ com.transferhub.pricing.ApplicationKt.module ]
    }
}

redis {
    host = "localhost"
    host = ${?REDIS_HOST}
    port = 6379
    port = ${?REDIS_PORT}
    quote-ttl-seconds = 30
}

pricing {
    # Hardcoded corridors for MVP. Sprint 3+: MongoDB config
    corridors {
        # format: "SOURCE_DEST"
        US_PH {
            fee = "5.99"
            rate = "56.20"
            delivery-methods = ["BANK_DEPOSIT", "CASH_PICKUP", "MOBILE_WALLET"]
            min-send = "10.00"
            max-send = "2999.00"
            delivery-estimate-min-minutes = 30
            delivery-estimate-max-minutes = 1440
        }
        US_MX {
            fee = "4.99"
            rate = "17.15"
            delivery-methods = ["BANK_DEPOSIT", "CASH_PICKUP"]
            min-send = "10.00"
            max-send = "2999.00"
            delivery-estimate-min-minutes = 15
            delivery-estimate-max-minutes = 720
        }
        GB_IN {
            fee = "3.99"
            rate = "105.25"
            delivery-methods = ["BANK_DEPOSIT", "MOBILE_WALLET"]
            min-send = "10.00"
            max-send = "5000.00"
            delivery-estimate-min-minutes = 30
            delivery-estimate-max-minutes = 1440
        }
        US_IN {
            fee = "5.49"
            rate = "83.12"
            delivery-methods = ["BANK_DEPOSIT"]
            min-send = "10.00"
            max-send = "2999.00"
            delivery-estimate-min-minutes = 60
            delivery-estimate-max-minutes = 2880
        }
    }
}
```

Порт `8081` — чтобы не конфликтовать с Transfer Service (8080) при локальной разработке.

---

## 3. Model: data classes

### `src/main/kotlin/com/transferhub/pricing/model/Quote.kt`

```kotlin
package com.transferhub.pricing.model

import kotlinx.serialization.Serializable

/**
 * Котировка (Quote) — результат расчёта стоимости перевода.
 * Сохраняется в Redis с TTL 30 сек (rate lock).
 */
@Serializable
data class Quote(
    val quoteId: String,           // UUID
    val sourceCountry: String,     // "US"
    val destCountry: String,       // "PH"
    val sendCurrency: String,      // "USD"
    val receiveCurrency: String,   // "PHP"
    val sendAmount: String,        // Decimal as string: "100.00"
    val receiveAmount: String,     // Decimal as string: "5290.86"
    val exchangeRate: String,      // "56.20"
    val feeAmount: String,         // "5.99"
    val deliveryMethod: String,    // "BANK_DEPOSIT"
    val senderId: String,          // UUID
    val expiresAtEpochMs: Long,    // Unix timestamp millis
    val deliveryEstimate: DeliveryEstimate
)

@Serializable
data class DeliveryEstimate(
    val minMinutes: Int,
    val maxMinutes: Int
)
```

### `src/main/kotlin/com/transferhub/pricing/model/QuoteRequest.kt`

```kotlin
package com.transferhub.pricing.model

/**
 * Параметры запроса котировки (из query parameters).
 * Не @Serializable — это не JSON body, а результат парсинга query params.
 */
data class QuoteRequest(
    val sourceCountry: String,
    val destCountry: String,
    val sendCurrency: String,
    val receiveCurrency: String,
    val sendAmount: String,
    val deliveryMethod: String,
    val senderId: String
)
```

### `src/main/kotlin/com/transferhub/pricing/model/ErrorResponse.kt`

```kotlin
package com.transferhub.pricing.model

import kotlinx.serialization.Serializable

/**
 * Формат ошибки (упрощённый Problem Details для Ktor).
 * Ktor не имеет встроенного ProblemDetail как Spring Boot,
 * поэтому создаём свой формат, совместимый с RFC 9457.
 */
@Serializable
data class ErrorResponse(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String,
    val instance: String? = null
)
```

---

## 4. RedisConfig — Lettuce client

### `src/main/kotlin/com/transferhub/pricing/config/RedisConfig.kt`

```kotlin
package com.transferhub.pricing.config

import io.ktor.server.application.*
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Lettuce Redis client — thread-safe, non-blocking.
 * Одно StatefulRedisConnection переиспользуется всеми корутинами.
 * Lettuce мультиплексирует команды через одно соединение (не нужен пул).
 */
class RedisConfig(
    private val host: String,
    private val port: Int
) {
    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>

    val asyncCommands: RedisAsyncCommands<String, String>
        get() = connection.async()

    fun connect() {
        val uri = RedisURI.builder()
            .withHost(host)
            .withPort(port)
            .build()
        client = RedisClient.create(uri)
        connection = client.connect()
        logger.info { "Connected to Redis at $host:$port" }
    }

    fun close() {
        connection.close()
        client.shutdown()
        logger.info { "Redis connection closed" }
    }
}

/**
 * Extension для чтения Redis конфигурации из application.conf
 */
fun Application.redisConfig(): RedisConfig {
    val host = environment.config.property("redis.host").getString()
    val port = environment.config.property("redis.port").getString().toInt()
    return RedisConfig(host, port)
}
```

---

## 5. QuoteCacheService — Redis cache для котировок

### `src/main/kotlin/com/transferhub/pricing/service/QuoteCacheService.kt`

```kotlin
package com.transferhub.pricing.service

import com.transferhub.pricing.config.RedisConfig
import com.transferhub.pricing.model.Quote
import kotlinx.coroutines.future.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Кэширование котировок в Redis.
 *
 * Key pattern: "quote:{quoteId}"
 * Value: JSON-сериализованный Quote
 * TTL: 30 секунд (rate lock — клиенту гарантируется курс на это время)
 *
 * Lettuce async commands возвращают CompletionStage.
 * kotlinx.coroutines.future.await() превращает их в suspend calls —
 * корутина не блокирует поток, пока ждёт ответ от Redis.
 */
class QuoteCacheService(
    private val redis: RedisConfig,
    private val ttlSeconds: Long,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    /**
     * Сохранить котировку в Redis с TTL.
     * SETEX — атомарно SET + EXPIRE.
     */
    suspend fun save(quote: Quote) {
        val key = "quote:${quote.quoteId}"
        val value = json.encodeToString(quote)
        redis.asyncCommands.setex(key, ttlSeconds, value).await()
        logger.debug { "Quote cached: ${quote.quoteId}, TTL=${ttlSeconds}s" }
    }

    /**
     * Получить котировку из Redis.
     * Возвращает null если:
     * - quote не найдена (никогда не существовала)
     * - TTL истёк (rate lock expired)
     */
    suspend fun get(quoteId: String): Quote? {
        val key = "quote:$quoteId"
        val value = redis.asyncCommands.get(key).await() ?: return null
        return try {
            json.decodeFromString<Quote>(value)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to deserialize quote $quoteId from Redis" }
            null
        }
    }
}
```

---

## 6. PricingService — бизнес-логика расчёта

### `src/main/kotlin/com/transferhub/pricing/service/PricingService.kt`

```kotlin
package com.transferhub.pricing.service

import com.transferhub.pricing.model.DeliveryEstimate
import com.transferhub.pricing.model.Quote
import com.transferhub.pricing.model.QuoteRequest
import io.ktor.server.application.*
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Pricing business logic.
 *
 * MVP: hardcoded fee и exchange rate по коридорам из application.conf.
 * Production: fee из MongoDB (corridor config), rate из Exchange Rate Provider
 * (обновляется каждые 30 сек, кэшируется в Redis).
 */
class PricingService(
    private val corridors: Map<String, CorridorConfig>,
    private val quoteCacheService: QuoteCacheService,
    private val quoteTtlSeconds: Long
) {

    /**
     * Рассчитать котировку.
     *
     * Формула: receiveAmount = (sendAmount - fee) * exchangeRate
     *
     * Пример US→PH:
     *   sendAmount = 100.00 USD
     *   fee = 5.99 USD
     *   rate = 56.20
     *   receiveAmount = (100.00 - 5.99) * 56.20 = 5287.44 PHP
     */
    suspend fun calculateQuote(request: QuoteRequest): Quote {
        val corridorKey = "${request.sourceCountry}_${request.destCountry}"
        val corridor = corridors[corridorKey]
            ?: throw CorridorNotSupportedException(request.sourceCountry, request.destCountry)

        // Validate delivery method
        if (request.deliveryMethod !in corridor.deliveryMethods) {
            throw DeliveryMethodNotAvailableException(
                request.deliveryMethod, corridorKey, corridor.deliveryMethods
            )
        }

        val sendAmount = request.sendAmount.toBigDecimalOrNull()
            ?: throw InvalidAmountException("send_amount must be a valid decimal")

        // Validate amount range
        if (sendAmount < corridor.minSend) {
            throw InvalidAmountException(
                "Minimum send amount for $corridorKey is ${corridor.minSend} ${request.sendCurrency}"
            )
        }
        if (sendAmount > corridor.maxSend) {
            throw InvalidAmountException(
                "Maximum send amount for $corridorKey is ${corridor.maxSend} ${request.sendCurrency}"
            )
        }

        val fee = corridor.fee
        val rate = corridor.rate

        // receiveAmount = (sendAmount - fee) * rate
        val netAmount = sendAmount.subtract(fee)
        if (netAmount <= BigDecimal.ZERO) {
            throw InvalidAmountException(
                "Send amount must be greater than fee ($fee ${request.sendCurrency})"
            )
        }
        val receiveAmount = netAmount.multiply(rate).setScale(2, RoundingMode.HALF_UP)

        val quoteId = UUID.randomUUID().toString()
        val expiresAt = System.currentTimeMillis() + (quoteTtlSeconds * 1000)

        val quote = Quote(
            quoteId = quoteId,
            sourceCountry = request.sourceCountry,
            destCountry = request.destCountry,
            sendCurrency = request.sendCurrency,
            receiveCurrency = request.receiveCurrency,
            sendAmount = sendAmount.toPlainString(),
            receiveAmount = receiveAmount.toPlainString(),
            exchangeRate = rate.toPlainString(),
            feeAmount = fee.toPlainString(),
            deliveryMethod = request.deliveryMethod,
            senderId = request.senderId,
            expiresAtEpochMs = expiresAt,
            deliveryEstimate = DeliveryEstimate(
                minMinutes = corridor.deliveryEstimateMinMinutes,
                maxMinutes = corridor.deliveryEstimateMaxMinutes
            )
        )

        // Save to Redis (rate lock — quote is valid for TTL seconds)
        quoteCacheService.save(quote)

        logger.info {
            "Quote created: $quoteId, $corridorKey, " +
            "send=${sendAmount} ${request.sendCurrency}, " +
            "receive=${receiveAmount} ${request.receiveCurrency}, " +
            "fee=${fee}, rate=${rate}"
        }

        return quote
    }

    /**
     * Валидация котировки по quote_id.
     * Используется Transfer Service (через gRPC в Block 2) при создании перевода.
     */
    suspend fun validateQuote(quoteId: String): QuoteValidationResult {
        val quote = quoteCacheService.get(quoteId)
            ?: return QuoteValidationResult(isValid = false, quote = null, reason = "Quote not found or expired")

        val now = System.currentTimeMillis()
        if (now > quote.expiresAtEpochMs) {
            return QuoteValidationResult(isValid = false, quote = null, reason = "Quote expired")
        }

        return QuoteValidationResult(isValid = true, quote = quote, reason = null)
    }
}

// --- Supporting types ---

data class CorridorConfig(
    val fee: BigDecimal,
    val rate: BigDecimal,
    val deliveryMethods: List<String>,
    val minSend: BigDecimal,
    val maxSend: BigDecimal,
    val deliveryEstimateMinMinutes: Int,
    val deliveryEstimateMaxMinutes: Int
)

data class QuoteValidationResult(
    val isValid: Boolean,
    val quote: Quote?,
    val reason: String?
)

// --- Exceptions ---

class CorridorNotSupportedException(source: String, dest: String) :
    RuntimeException("Corridor $source→$dest is not supported")

class DeliveryMethodNotAvailableException(method: String, corridor: String, available: List<String>) :
    RuntimeException("Delivery method $method is not available for $corridor. Available: $available")

class InvalidAmountException(message: String) : RuntimeException(message)
```

---

## 7. QuoteRoutes — Ktor routing DSL

### `src/main/kotlin/com/transferhub/pricing/routes/QuoteRoutes.kt`

```kotlin
package com.transferhub.pricing.routes

import com.transferhub.pricing.model.ErrorResponse
import com.transferhub.pricing.model.QuoteRequest
import com.transferhub.pricing.service.PricingService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Ktor routing DSL — декларативное описание routes.
 *
 * Отличие от Spring:
 * - Spring: @GetMapping("/api/v1/quotes") fun getQuote(@RequestParam ...)
 * - Ktor: route("/api/v1/quotes") { get { ... } }
 *
 * Ktor routing — это функция-расширение на Route, а не аннотированный класс.
 * Нет рефлексии, нет проксирования, нет classpath scanning.
 */
fun Route.quoteRoutes(pricingService: PricingService) {

    route("/api/v1/quotes") {

        /**
         * GET /api/v1/quotes?source_country=US&dest_country=PH&send_currency=USD
         *     &receive_currency=PHP&send_amount=100&delivery_method=BANK_DEPOSIT
         *     &sender_id=uuid
         *
         * Возвращает котировку с зафиксированным курсом (rate lock 30 сек).
         */
        get {
            // Извлечение query parameters
            val sourceCountry = call.request.queryParameters["source_country"]
            val destCountry = call.request.queryParameters["dest_country"]
            val sendCurrency = call.request.queryParameters["send_currency"]
            val receiveCurrency = call.request.queryParameters["receive_currency"]
            val sendAmount = call.request.queryParameters["send_amount"]
            val deliveryMethod = call.request.queryParameters["delivery_method"]
            val senderId = call.request.queryParameters["sender_id"]

            // Validation: all parameters required
            val missing = mutableListOf<String>()
            if (sourceCountry.isNullOrBlank()) missing.add("source_country")
            if (destCountry.isNullOrBlank()) missing.add("dest_country")
            if (sendCurrency.isNullOrBlank()) missing.add("send_currency")
            if (receiveCurrency.isNullOrBlank()) missing.add("receive_currency")
            if (sendAmount.isNullOrBlank()) missing.add("send_amount")
            if (deliveryMethod.isNullOrBlank()) missing.add("delivery_method")
            if (senderId.isNullOrBlank()) missing.add("sender_id")

            if (missing.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        title = "Missing Parameters",
                        status = 400,
                        detail = "Required query parameters missing: ${missing.joinToString()}"
                    )
                )
                return@get
            }

            val request = QuoteRequest(
                sourceCountry = sourceCountry!!,
                destCountry = destCountry!!,
                sendCurrency = sendCurrency!!,
                receiveCurrency = receiveCurrency!!,
                sendAmount = sendAmount!!,
                deliveryMethod = deliveryMethod!!,
                senderId = senderId!!
            )

            val quote = pricingService.calculateQuote(request)
            call.respond(HttpStatusCode.OK, quote)
        }
    }

    route("/api/v1/quotes/{quoteId}/validate") {

        /**
         * GET /api/v1/quotes/{quoteId}/validate
         *
         * Проверяет валидность котировки. Используется Transfer Service
         * при создании перевода (в Block 2 заменяется gRPC вызовом).
         */
        get {
            val quoteId = call.parameters["quoteId"]
            if (quoteId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(title = "Invalid Request", status = 400, detail = "quoteId is required")
                )
                return@get
            }

            val result = pricingService.validateQuote(quoteId)

            if (result.isValid) {
                call.respond(HttpStatusCode.OK, result.quote!!)
            } else {
                call.respond(
                    HttpStatusCode.Gone,      // 410 Gone — quote expired
                    ErrorResponse(
                        title = "Quote Invalid",
                        status = 410,
                        detail = result.reason ?: "Quote not found or expired"
                    )
                )
            }
        }
    }
}
```

---

## 8. Application.kt — собираем всё вместе

### `src/main/kotlin/com/transferhub/pricing/Application.kt`

```kotlin
package com.transferhub.pricing

import com.transferhub.pricing.config.RedisConfig
import com.transferhub.pricing.config.redisConfig
import com.transferhub.pricing.model.ErrorResponse
import com.transferhub.pricing.routes.quoteRoutes
import com.transferhub.pricing.service.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.defaultheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.math.BigDecimal

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) = EngineMain.main(args)

/**
 * Ktor Application module.
 *
 * Ktor не использует DI-контейнер (нет Spring IoC).
 * Зависимости собираются вручную — это осознанный trade-off:
 * проще, прозрачнее, быстрее startup, но нет auto-wiring.
 *
 * Для крупных Ktor-проектов можно подключить Koin (lightweight DI),
 * но для Pricing Service с 3-4 классами это overkill.
 */
fun Application.module() {

    // --- Plugins (аналог Spring Auto-Configuration, но явный) ---

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(DefaultHeaders) {
        header("X-Service", "pricing-service")
    }

    install(CallLogging)

    // StatusPages — аналог @RestControllerAdvice в Spring
    install(StatusPages) {
        exception<CorridorNotSupportedException> { call, cause ->
            logger.warn { "Corridor not supported: ${cause.message}" }
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(
                    title = "Corridor Not Supported",
                    status = 422,
                    detail = cause.message ?: "Unsupported corridor"
                )
            )
        }
        exception<DeliveryMethodNotAvailableException> { call, cause ->
            logger.warn { "Delivery method not available: ${cause.message}" }
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorResponse(
                    title = "Delivery Method Not Available",
                    status = 422,
                    detail = cause.message ?: "Delivery method not available"
                )
            )
        }
        exception<InvalidAmountException> { call, cause ->
            logger.warn { "Invalid amount: ${cause.message}" }
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    title = "Invalid Amount",
                    status = 400,
                    detail = cause.message ?: "Invalid amount"
                )
            )
        }
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    title = "Internal Server Error",
                    status = 500,
                    detail = "An unexpected error occurred"
                )
            )
        }
    }

    // --- Dependencies (manual wiring) ---

    val redisConfig = redisConfig()
    redisConfig.connect()

    // Graceful shutdown: close Redis when app stops
    monitor.subscribe(ApplicationStopped) {
        redisConfig.close()
    }

    val quoteTtlSeconds = environment.config
        .property("redis.quote-ttl-seconds").getString().toLong()

    val quoteCacheService = QuoteCacheService(redisConfig, quoteTtlSeconds)

    val corridors = loadCorridorConfigs()

    val pricingService = PricingService(corridors, quoteCacheService, quoteTtlSeconds)

    // --- Routing ---

    routing {
        // Health check
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "UP"))
        }

        // Quote routes
        quoteRoutes(pricingService)
    }

    logger.info { "Pricing Service started on port ${environment.config.port}" }
    logger.info { "Loaded ${corridors.size} corridors: ${corridors.keys}" }
}

/**
 * Загрузка конфигурации коридоров из application.conf.
 *
 * MVP: hardcoded в HOCON. Production: MongoDB + Redis cache.
 */
private fun Application.loadCorridorConfigs(): Map<String, CorridorConfig> {
    val corridorConfigs = mutableMapOf<String, CorridorConfig>()
    val config = environment.config

    // List known corridors
    val corridorKeys = listOf("US_PH", "US_MX", "GB_IN", "US_IN")

    for (key in corridorKeys) {
        try {
            val prefix = "pricing.corridors.$key"
            corridorConfigs[key] = CorridorConfig(
                fee = BigDecimal(config.property("$prefix.fee").getString()),
                rate = BigDecimal(config.property("$prefix.rate").getString()),
                deliveryMethods = config.property("$prefix.delivery-methods").getList(),
                minSend = BigDecimal(config.property("$prefix.min-send").getString()),
                maxSend = BigDecimal(config.property("$prefix.max-send").getString()),
                deliveryEstimateMinMinutes = config.property("$prefix.delivery-estimate-min-minutes").getString().toInt(),
                deliveryEstimateMaxMinutes = config.property("$prefix.delivery-estimate-max-minutes").getString().toInt()
            )
        } catch (e: Exception) {
            logger.warn { "Failed to load corridor config for $key: ${e.message}" }
        }
    }

    return corridorConfigs
}
```

---

## Проверка

### Запуск:

```bash
cd services/pricing-service
./gradlew run
```

### Тесты:

```bash
# Health
curl http://localhost:8081/health

# Котировка US→PH
curl "http://localhost:8081/api/v1/quotes?\
source_country=US&dest_country=PH&\
send_currency=USD&receive_currency=PHP&\
send_amount=100.00&delivery_method=BANK_DEPOSIT&\
sender_id=550e8400-e29b-41d4-a716-446655440000"

# Ожидаемый ответ:
# {
#   "quoteId": "...",
#   "sendAmount": "100.00",
#   "receiveAmount": "5290.86",   ← (100 - 5.99) * 56.20
#   "exchangeRate": "56.20",
#   "feeAmount": "5.99",
#   ...
#   "expiresAtEpochMs": 1234567890123
# }

# Validate quote (подставь quoteId из ответа выше)
curl http://localhost:8081/api/v1/quotes/{quoteId}/validate
# → 200 OK (если в течение 30 сек)
# → 410 Gone (если после 30 сек)

# Ошибка: неподдерживаемый коридор
curl "http://localhost:8081/api/v1/quotes?\
source_country=JP&dest_country=BR&\
send_currency=JPY&receive_currency=BRL&\
send_amount=1000&delivery_method=BANK_DEPOSIT&\
sender_id=550e8400-e29b-41d4-a716-446655440000"
# → 422 Corridor Not Supported

# Ошибка: отсутствует параметр
curl "http://localhost:8081/api/v1/quotes?source_country=US"
# → 400 Missing Parameters
```

### Проверка Redis:

```bash
# После создания котировки
docker exec -it redis redis-cli
> KEYS quote:*
# → quote:{uuid}
> TTL quote:{uuid}
# → число секунд (≤ 30)
> GET quote:{uuid}
# → JSON с данными котировки
```

---

## Чего НЕ делать в этом блоке

- Не делай gRPC — Block 2
- Не делай Dockerfile — Block 10
- Не подключай Micrometer/метрики — Block 8 (structured logging)
- Не делай MongoDB для corridor configs — Sprint 3+
- Не делай реальный Exchange Rate Provider — hardcoded rates достаточно для MVP
