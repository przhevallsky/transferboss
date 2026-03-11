# gRPC — Полное руководство по реализации (на примере pricing-service)

## Оглавление

1. [Общая картина: что такое gRPC и зачем он нам](#1-общая-картина)
2. [Архитектура слоёв](#2-архитектура-слоёв)
3. [Шаг 1: Proto-файл (контракт)](#3-шаг-1-proto-файл)
4. [Шаг 2: Настройка сборки (build.gradle.kts)](#4-шаг-2-настройка-сборки)
5. [Шаг 3: Что генерирует protoc](#5-шаг-3-что-генерирует-protoc)
6. [Шаг 4: gRPC Service Implementation (handler)](#6-шаг-4-grpc-service-implementation)
7. [Шаг 5: Бизнес-логика (PricingService)](#7-шаг-5-бизнес-логика)
8. [Шаг 6: gRPC Server (запуск и жизненный цикл)](#8-шаг-6-grpc-server)
9. [Шаг 7: Подключение к Application](#9-шаг-7-подключение-к-application)
10. [Шаг 8: Тестирование gRPC](#10-шаг-8-тестирование)
11. [Полный путь запроса (request flow)](#11-полный-путь-запроса)
12. [Чеклист: как добавить новый gRPC-сервис с нуля](#12-чеклист)
13. [Справочник файлов](#13-справочник-файлов)

---

## 1. Общая картина

**gRPC** — это фреймворк для межсервисного общения (RPC — Remote Procedure Call). В отличие от REST:

| | REST | gRPC |
|---|---|---|
| Формат | JSON (текст) | Protobuf (бинарный) |
| Контракт | OpenAPI/Swagger (опционально) | `.proto` файл (обязательно) |
| Транспорт | HTTP/1.1 | HTTP/2 |
| Кодогенерация | Вручную пишешь DTO | Автоматически из .proto |
| Скорость | Медленнее (парсинг JSON) | Быстрее (бинарная сериализация) |

**Зачем нам gRPC в проекте:**
Transfer Service вызывает Pricing Service синхронно (~200 RPS, p99 < 150ms).
gRPC идеален для такого — строгий контракт, быстрая сериализация, поддержка корутин.

---

## 2. Архитектура слоёв

```
┌─────────────────────────────────────────────────────┐
│                  Transfer Service                    │
│  (gRPC client — вызывает pricing через stub)         │
└─────────────────────┬───────────────────────────────┘
                      │ gRPC call (HTTP/2 + Protobuf)
                      ▼
┌─────────────────────────────────────────────────────┐
│                 Pricing Service                      │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │ Proto-файл (.proto)                          │   │  ← Контракт
│  │ Описывает сервис, методы, сообщения          │   │
│  └──────────────┬───────────────────────────────┘   │
│                 │ protoc (кодогенерация при сборке)  │
│                 ▼                                    │
│  ┌──────────────────────────────────────────────┐   │
│  │ Сгенерированный код (build/generated/)       │   │  ← Авто
│  │ - Классы сообщений (Request/Response)        │   │
│  │ - Абстрактный базовый класс сервиса          │   │
│  └──────────────┬───────────────────────────────┘   │
│                 │ наследуем                          │
│                 ▼                                    │
│  ┌──────────────────────────────────────────────┐   │
│  │ PricingGrpcService (handler/adapter)         │   │  ← Пишем руками
│  │ - Принимает proto-сообщения                  │   │
│  │ - Маппит proto → domain DTO                  │   │
│  │ - Вызывает бизнес-логику                     │   │
│  │ - Маппит domain → proto-ответ                │   │
│  │ - Обрабатывает ошибки → gRPC Status          │   │
│  └──────────────┬───────────────────────────────┘   │
│                 │ вызывает                           │
│                 ▼                                    │
│  ┌──────────────────────────────────────────────┐   │
│  │ PricingService (бизнес-логика)               │   │  ← Пишем руками
│  │ - calculateQuote()                           │   │
│  │ - validateQuote()                            │   │
│  │ - Работает с доменными объектами (Quote)     │   │
│  │ - Кэширует в Redis                          │   │
│  └──────────────────────────────────────────────┘   │
│                                                      │
│  ┌──────────────────────────────────────────────┐   │
│  │ GrpcServer (инфраструктура)                  │   │  ← Пишем руками
│  │ - Создаёт и запускает io.grpc.Server         │   │
│  │ - Регистрирует PricingGrpcService            │   │
│  │ - Graceful shutdown                          │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

**Ключевая идея:** proto-файл — это КОНТРАКТ. Из него генерируются классы. Ты пишешь handler, который использует сгенерированные классы и делегирует бизнес-логике.

---

## 3. Шаг 1: Proto-файл (контракт)

**Файл:** `src/main/proto/pricing/v1/pricing_service.proto`

```protobuf
syntax = "proto3";

package com.transferhub.pricing.v1;

option java_multiple_files = true;                        // каждый класс в отдельном файле
option java_package = "com.transferhub.pricing.grpc.v1";  // пакет для сгенерированного Java/Kotlin кода

// Определение сервиса — какие методы доступны для вызова
service PricingService {
  rpc GetQuote (GetQuoteRequest) returns (QuoteResponse);
  rpc ValidateQuote (ValidateQuoteRequest) returns (ValidateQuoteResponse);
}

// Входящее сообщение для GetQuote
message GetQuoteRequest {
  string source_country = 1;       // номера полей — порядковые идентификаторы в бинарном формате
  string destination_country = 2;
  string send_currency = 3;
  string receive_currency = 4;
  string send_amount = 5;          // строка, не double — для точности BigDecimal
  string delivery_method = 6;
  string sender_id = 7;
}

// ... остальные сообщения
```

### Правила proto-файлов:
- **Числовые значения как string** — proto не имеет BigDecimal, а float/double теряет точность для денег
- **Номера полей (= 1, = 2...)** — это НЕ значения по умолчанию, а уникальные идентификаторы полей в бинарном формате. Никогда не меняй номер существующего поля!
- **snake_case** для имён полей — proto-конвенция
- **CamelCase** для имён сообщений и сервисов

---

## 4. Шаг 2: Настройка сборки (build.gradle.kts)

### 4.1 Плагины

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)      // com.google.protobuf — главный плагин
    // ...
}
```

### 4.2 Блок protobuf {}

```kotlin
import com.google.protobuf.gradle.*   // импорт DSL-функций

protobuf {

    // 1. Компилятор protoc — превращает .proto → код
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }

    // 2. Плагины для protoc — дополнительные генераторы кода
    plugins {
        // Генерирует Java-классы для gRPC (сервис, стабы)
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.asProvider().get()}"
        }
        // Генерирует Kotlin-корутинные стабы (suspend-функции!)
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpc.kotlin.get()}:jdk8@jar"
        }
    }

    // 3. Настройка задач генерации
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")      // включить Java gRPC генерацию
                create("grpckt")    // включить Kotlin gRPC генерацию
            }
            task.builtins {
                create("kotlin")    // включить Kotlin-обёртки для proto-сообщений
            }
        }
    }
}
```

### 4.3 Зависимости

```kotlin
dependencies {
    // gRPC runtime
    implementation(libs.grpc.netty)        // транспорт (HTTP/2 сервер)
    implementation(libs.grpc.protobuf)     // интеграция protobuf с gRPC
    implementation(libs.grpc.stub)         // базовые классы для стабов
    implementation(libs.grpc.kotlin.stub)  // Kotlin-корутинные стабы
    implementation(libs.protobuf.kotlin)   // Kotlin-расширения для proto-сообщений

    // тестирование gRPC
    testImplementation(libs.grpc.testing)    // вспомогательные классы
    testImplementation(libs.grpc.inprocess)  // in-process сервер (без сети)
}
```

### Что происходит при сборке:

```
./gradlew build
    │
    ├── protoc читает src/main/proto/**/*.proto
    │
    ├── protoc (core) генерирует:
    │   ├── Java-классы сообщений (GetQuoteRequest, QuoteResponse, ...)
    │   └── Kotlin-расширения для сообщений (DSL-билдеры)
    │
    ├── protoc-gen-grpc-java генерирует:
    │   └── PricingServiceGrpc.java (Java стабы, base classes)
    │
    └── protoc-gen-grpc-kotlin генерирует:
        └── PricingServiceGrpcKt.kt (Kotlin корутинные стабы)
            ├── PricingServiceCoroutineImplBase — базовый класс для СЕРВЕРА
            └── PricingServiceCoroutineStub — клиент для ВЫЗОВА сервиса
```

Сгенерированный код попадает в `build/generated/source/proto/` и автоматически добавляется в classpath.

---

## 5. Шаг 3: Что генерирует protoc

Ты НЕ видишь эти файлы в `src/` — они в `build/generated/`. Но ты их используешь как обычные классы.

### 5.1 Классы сообщений (из message)

Из каждого `message` в proto генерируются:

```kotlin
// Сгенерированный класс — иммутабельный, builder-паттерн
val request = GetQuoteRequest.newBuilder()
    .setSourceCountry("US")
    .setDestinationCountry("PH")
    .setSendAmount("500.00")
    .build()

// Kotlin DSL (благодаря builtins → kotlin)
val request = getQuoteRequest {
    sourceCountry = "US"
    destinationCountry = "PH"
    sendAmount = "500.00"
}
```

### 5.2 Базовый класс сервиса (из service + rpc)

```kotlin
// Сгенерированный абстрактный класс (Kotlin корутины)
abstract class PricingServiceCoroutineImplBase : ... {
    // Каждый rpc из proto → suspend-функция
    open suspend fun getQuote(request: GetQuoteRequest): QuoteResponse { ... }
    open suspend fun validateQuote(request: ValidateQuoteRequest): ValidateQuoteResponse { ... }
}
```

### 5.3 Клиентский стаб (для вызова сервиса)

```kotlin
// Сгенерированный клиент
class PricingServiceCoroutineStub(channel: Channel) {
    suspend fun getQuote(request: GetQuoteRequest): QuoteResponse
    suspend fun validateQuote(request: ValidateQuoteRequest): ValidateQuoteResponse
}
```

---

## 6. Шаг 4: gRPC Service Implementation (handler)

**Файл:** `src/main/kotlin/com/transferhub/pricing/grpc/PricingGrpcService.kt`

Это КЛЮЧЕВОЙ файл — мост между proto-миром и бизнес-логикой.

```kotlin
class PricingGrpcService(
    private val pricingService: PricingService   // инжектим бизнес-логику
) : PricingServiceGrpcKt.PricingServiceCoroutineImplBase() {
    //   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
    //   Наследуем сгенерированный базовый класс!
    //   Это говорит gRPC: "я реализую методы из proto"

    // Переопределяем сгенерированный suspend-метод
    override suspend fun getQuote(request: GetQuoteRequest): QuoteResponse {
        try {
            // 1. МАППИНГ: proto → доменный DTO
            val quoteRequest = QuoteRequest(
                sourceCountry = request.sourceCountry,
                destinationCountry = request.destinationCountry,
                sendCurrency = request.sendCurrency,
                receiveCurrency = request.receiveCurrency,
                sendAmount = request.sendAmount,
                deliveryMethod = request.deliveryMethod,
                senderId = request.senderId,
            )

            // 2. ВЫЗОВ бизнес-логики (работает с доменными объектами)
            val quote = pricingService.calculateQuote(quoteRequest)

            // 3. МАППИНГ: доменный объект → proto-ответ
            return toQuoteResponse(quote)

        } catch (e: CorridorNotSupportedException) {
            // 4. МАППИНГ ОШИБОК: доменные исключения → gRPC статусы
            throw StatusException(
                Status.INVALID_ARGUMENT.withDescription(e.message)
            )
        } catch (e: Exception) {
            throw StatusException(
                Status.INTERNAL.withDescription("Internal error")
            )
        }
    }

    // Маппер: доменный Quote → proto QuoteResponse
    private fun toQuoteResponse(quote: Quote): QuoteResponse {
        return quoteResponse {                         // Kotlin DSL (сгенерирован)
            quoteId = quote.quoteId
            sendAmount = quote.sendAmount.toPlainString()     // BigDecimal → String
            receiveAmount = quote.receiveAmount.toPlainString()
            exchangeRate = quote.exchangeRate.toPlainString()
            feeAmount = quote.feeAmount.toPlainString()
            feeCurrency = quote.feeCurrency
            sendCurrency = quote.sendCurrency
            receiveCurrency = quote.receiveCurrency
            deliveryMethod = quote.deliveryMethod
            expiresAtEpochMs = quote.expiresAt.toEpochMilli() // Instant → Long
            ttlSeconds = calculateTtl(quote)                  // вычисляем оставшийся TTL
        }
    }
}
```

### Почему handler отделён от бизнес-логики:

```
PricingGrpcService (handler)     PricingService (бизнес-логика)
├── знает про proto-классы       ├── НЕ знает про proto
├── маппит proto ↔ domain        ├── работает с Quote, BigDecimal
├── маппит ошибки → gRPC Status  ├── бросает доменные исключения
└── suspend (корутины)           └── suspend (корутины)
```

Бизнес-логика чистая — её можно вызвать из REST, из тестов, откуда угодно. Она не привязана к gRPC.

---

## 7. Шаг 5: Бизнес-логика (PricingService)

**Файл:** `src/main/kotlin/com/transferhub/pricing/service/PricingService.kt`

```kotlin
class PricingService(
    private val corridors: Map<String, CorridorConfig>,
    private val quoteCacheService: QuoteCacheService,
    private val quoteTtlSeconds: Long = 30,
) {
    suspend fun calculateQuote(request: QuoteRequest): Quote {
        // 1. Валидация (бросает доменные исключения)
        val corridor = corridors[corridorKey]
            ?: throw CorridorNotSupportedException(...)

        // 2. Бизнес-расчёты (BigDecimal для точности)
        val receiveAmount = (sendAmount - fee) * exchangeRate

        // 3. Создание доменного объекта
        val quote = Quote(
            quoteId = UUID.randomUUID().toString(),
            // ... все поля
            expiresAt = Instant.now().plusSeconds(quoteTtlSeconds),
        )

        // 4. Кэширование
        quoteCacheService.save(quote)

        return quote
    }

    suspend fun validateQuote(quoteId: String): QuoteValidationResult {
        val quote = quoteCacheService.get(quoteId)
            ?: return QuoteValidationResult(isValid = false, quote = null)

        if (quote.expiresAt.isBefore(Instant.now())) {
            return QuoteValidationResult(isValid = false, quote = null)
        }

        return QuoteValidationResult(isValid = true, quote = quote)
    }
}
```

Обрати внимание: PricingService **ничего не знает о gRPC**. Он работает с `QuoteRequest` (наш DTO), `Quote` (наш доменный объект), `QuoteCacheService` (наш Redis-сервис).

---

## 8. Шаг 6: gRPC Server (запуск и жизненный цикл)

**Файл:** `src/main/kotlin/com/transferhub/pricing/grpc/GrpcServer.kt`

```kotlin
class GrpcServer(
    private val config: AppConfig,
    private val pricingService: PricingService,
) {
    private lateinit var server: Server

    fun start() {
        // 1. Создаём gRPC-handler (передаём бизнес-сервис)
        val grpcService = PricingGrpcService(pricingService)

        // 2. Строим и запускаем gRPC-сервер
        server = ServerBuilder
            .forPort(config.grpcPort)        // порт 9090
            .addService(grpcService)          // регистрируем наш handler
            .build()
            .start()
    }

    fun stop() {
        server.shutdown()                     // graceful shutdown
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
            server.shutdownNow()              // force после 5 секунд
        }
    }
}
```

### Что здесь важно:
- `ServerBuilder.forPort()` — создаёт HTTP/2 сервер на указанном порту
- `.addService(grpcService)` — регистрирует наш handler; можно добавить несколько сервисов
- gRPC-сервер работает **отдельно** от HTTP-сервера (Ktor на порту 8082, gRPC на 9090)

---

## 9. Шаг 7: Подключение к Application

**Файл:** `src/main/kotlin/com/transferhub/pricing/Application.kt`

```kotlin
fun main() {
    // 1. Конфигурация
    val config = AppConfig.load()

    // 2. Инфраструктура
    val redisClient = RedisClientFactory(config.redisUrl)
    val cacheService = QuoteCacheService(redisClient, config.quoteTtlSeconds)

    // 3. Бизнес-логика
    val pricingService = PricingService(DEFAULT_CORRIDORS, cacheService, config.quoteTtlSeconds)

    // 4. gRPC-сервер
    val grpcServer = GrpcServer(config, pricingService)

    // 5. HTTP-сервер (Ktor)
    embeddedServer(Netty, port = config.serverPort) {
        module(config, pricingService, redisClient)
    }.start(wait = true)
}

fun Application.module(config: AppConfig, pricingService: PricingService, redis: RedisClientFactory) {
    // Ktor plugins ...

    // Привязка жизненного цикла gRPC к Ktor
    environment.monitor.subscribe(ApplicationStarted) {
        grpcServer.start()      // gRPC стартует вместе с Ktor
    }
    environment.monitor.subscribe(ApplicationStopped) {
        grpcServer.stop()       // gRPC останавливается вместе с Ktor
        redis.close()
    }
}
```

### Итого два сервера работают параллельно:
- **Ktor (HTTP)** на порту `8082` — REST API, health checks, Prometheus метрики
- **gRPC** на порту `9090` — межсервисное взаимодействие

---

## 10. Шаг 8: Тестирование gRPC

**Файл:** `src/test/kotlin/com/transferhub/pricing/PricingGrpcServiceTest.kt`

gRPC можно тестировать **без сети** через in-process транспорт:

```kotlin
class PricingGrpcServiceTest {

    // Мокаем зависимости
    private val mockCacheService = mockk<QuoteCacheService>(relaxed = true)
    private val pricingService = PricingService(DEFAULT_CORRIDORS, mockCacheService)

    // In-process gRPC — всё в памяти, без сети
    private val serverName = InProcessServerBuilder.generateName()

    private val server = InProcessServerBuilder
        .forName(serverName)
        .directExecutor()
        .addService(PricingGrpcService(pricingService))    // наш handler
        .build()
        .start()

    private val channel = InProcessChannelBuilder
        .forName(serverName)
        .directExecutor()
        .build()

    // Клиентский стаб — как будто вызываем с другого сервиса
    private val stub = PricingServiceGrpcKt.PricingServiceCoroutineStub(channel)

    @Test
    fun `getQuote returns valid quote`() = runBlocking<Unit> {
        // Формируем proto-запрос (как клиент)
        val request = getQuoteRequest {
            sourceCountry = "US"
            destinationCountry = "PH"
            sendCurrency = "USD"
            receiveCurrency = "PHP"
            sendAmount = "500.00"
            deliveryMethod = "BANK_DEPOSIT"
            senderId = "sender-1"
        }

        // Вызываем через стаб (имитация сетевого вызова)
        val response = stub.getQuote(request)

        // Проверяем proto-ответ
        response.quoteId.shouldNotBeBlank()
        response.sendAmount shouldBe "500.00"
        response.receiveCurrency shouldBe "PHP"
    }

    @Test
    fun `getQuote with unsupported corridor returns INVALID_ARGUMENT`() = runBlocking<Unit> {
        val request = getQuoteRequest {
            sourceCountry = "XX"
            // ...
        }

        val exception = shouldThrow<StatusException> {
            stub.getQuote(request)
        }

        exception.status.code shouldBe Status.Code.INVALID_ARGUMENT
    }
}
```

### Почему in-process:
- Быстро (нет TCP)
- Надёжно (нет проблем с портами)
- Тестирует весь стек: proto-сериализацию → handler → бизнес-логику → proto-десериализацию

---

## 11. Полный путь запроса (request flow)

```
Transfer Service (клиент)
    │
    │  val stub = PricingServiceCoroutineStub(channel)
    │  val response = stub.getQuote(request)
    │
    │  1. Kotlin-объект GetQuoteRequest сериализуется в Protobuf (бинарный)
    │  2. Отправляется по HTTP/2 на pricing-service:9090
    │
    ▼
GrpcServer (порт 9090)
    │
    │  3. Принимает HTTP/2 запрос
    │  4. Десериализует Protobuf → GetQuoteRequest объект
    │  5. Определяет метод: PricingService/GetQuote
    │  6. Вызывает PricingGrpcService.getQuote(request)
    │
    ▼
PricingGrpcService.getQuote()              ← handler (мы написали)
    │
    │  7. Маппит proto GetQuoteRequest → наш QuoteRequest DTO
    │  8. Вызывает pricingService.calculateQuote(quoteRequest)
    │
    ▼
PricingService.calculateQuote()            ← бизнес-логика (мы написали)
    │
    │  9.  Валидирует коридор, метод доставки, сумму
    │  10. Считает fee, exchange rate, receive amount (BigDecimal)
    │  11. Генерирует quoteId (UUID)
    │  12. Сохраняет в Redis через QuoteCacheService (TTL 30s)
    │  13. Возвращает доменный объект Quote
    │
    ▼
PricingGrpcService.getQuote()              ← возвращаемся в handler
    │
    │  14. Маппит Quote → proto QuoteResponse (toQuoteResponse)
    │      BigDecimal → String, Instant → epochMs
    │  15. Возвращает QuoteResponse
    │
    ▼
GrpcServer
    │
    │  16. Сериализует QuoteResponse в Protobuf
    │  17. Отправляет по HTTP/2 обратно клиенту
    │
    ▼
Transfer Service
    │
    │  18. Десериализует Protobuf → QuoteResponse объект
    │  19. Использует response.quoteId, response.receiveAmount, ...
    └
```

---

## 12. Чеклист: как добавить новый gRPC-сервис с нуля

Допустим, нужно добавить gRPC в новый сервис (например, `compliance-service`).

### Фаза 1: Подготовка

- [ ] **1. Добавить зависимости в `libs.versions.toml`** (если ещё нет):
  ```toml
  [versions]
  protobuf = "4.26.0"
  grpc     = "1.62.2"
  grpc-kotlin = "1.4.1"

  [libraries]
  grpc-netty       = { module = "io.grpc:grpc-netty-shaded", version.ref = "grpc" }
  grpc-protobuf    = { module = "io.grpc:grpc-protobuf",     version.ref = "grpc" }
  grpc-stub        = { module = "io.grpc:grpc-stub",         version.ref = "grpc" }
  grpc-kotlin-stub = { module = "io.grpc:grpc-kotlin-stub",  version.ref = "grpc-kotlin" }
  protobuf-kotlin  = { module = "com.google.protobuf:protobuf-kotlin", version.ref = "protobuf" }
  grpc-testing     = { module = "io.grpc:grpc-testing",      version.ref = "grpc" }
  grpc-inprocess   = { module = "io.grpc:grpc-inprocess",    version.ref = "grpc" }

  [plugins]
  protobuf = { id = "com.google.protobuf", version = "0.9.4" }
  ```

- [ ] **2. Настроить `build.gradle.kts`** — скопировать блок `protobuf {}` и зависимости из pricing-service

### Фаза 2: Контракт

- [ ] **3. Создать proto-файл**: `src/main/proto/<service>/v1/<service>.proto`
  - Определить `service`, `rpc` методы, `message` типы
  - Использовать string для денежных сумм
  - Задать `java_package` и `java_multiple_files = true`

- [ ] **4. Запустить сборку**: `./gradlew generateProto` (или просто `build`)
  - Убедиться, что классы сгенерировались в `build/generated/source/proto/`

### Фаза 3: Реализация

- [ ] **5. Написать доменные модели**: data class Quote, data class QuoteRequest, ...
- [ ] **6. Написать бизнес-сервис**: `SomeService.kt` с suspend-функциями, без знания о gRPC
- [ ] **7. Написать gRPC handler**: наследовать `...CoroutineImplBase()`, реализовать маппинг proto ↔ domain
- [ ] **8. Написать GrpcServer**: `ServerBuilder.forPort().addService().build().start()`
- [ ] **9. Подключить к Application**: запуск/остановка привязана к жизненному циклу приложения

### Фаза 4: Тестирование

- [ ] **10. Unit-тесты бизнес-логики**: обычные тесты, без gRPC
- [ ] **11. gRPC-тесты**: `InProcessServerBuilder` + `InProcessChannelBuilder` + `CoroutineStub`

### Фаза 5: Docker

- [ ] **12. Expose gRPC порт** в Dockerfile: `EXPOSE 9090`

---

## 13. Справочник файлов

| Что | Файл | Зачем |
|-----|------|-------|
| Proto-контракт | `src/main/proto/pricing/v1/pricing_service.proto` | Определяет API: сервис, методы, сообщения |
| Сборка | `build.gradle.kts` | Настройка protoc + плагины кодогенерации |
| gRPC handler | `src/main/.../grpc/PricingGrpcService.kt` | Мост proto ↔ бизнес-логика, обработка ошибок |
| gRPC сервер | `src/main/.../grpc/GrpcServer.kt` | Запуск/остановка io.grpc.Server на порту 9090 |
| Бизнес-логика | `src/main/.../service/PricingService.kt` | Расчёты, валидация, кэширование (чистый Kotlin) |
| Кэш-сервис | `src/main/.../service/QuoteCacheService.kt` | Redis SETEX/GET с kotlinx.serialization |
| Конфигурация | `src/main/.../config/AppConfig.kt` | Порты, TTL, URL Redis из env-переменных |
| Доменная модель | `src/main/.../model/Quote.kt` | BigDecimal, Instant — точные типы для денег |
| DTO | `src/main/.../api/dto/QuoteRequest.kt` | Входной DTO (используется и REST, и gRPC) |
| Точка входа | `src/main/.../Application.kt` | main(), создание зависимостей, старт серверов |
| gRPC тесты | `src/test/.../PricingGrpcServiceTest.kt` | In-process тесты всего gRPC стека |
| Unit тесты | `src/test/.../PricingServiceTest.kt` | Тесты бизнес-логики без gRPC |

---

## Резюме

**gRPC реализация = 4 вещи, которые пишешь руками:**

1. **Proto-файл** — контракт (что можно вызвать и какие данные)
2. **Handler** (`PricingGrpcService`) — мост между proto и бизнес-логикой
3. **Бизнес-сервис** (`PricingService`) — чистая логика без привязки к транспорту
4. **Server** (`GrpcServer`) — инфраструктура запуска

Всё остальное (классы сообщений, стабы, сериализация) — **генерируется автоматически** из proto-файла при сборке.
