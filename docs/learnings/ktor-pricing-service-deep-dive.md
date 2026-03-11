# Ktor: теория, архитектура и почему pricing-service написан на нём

## Часть 1. Что такое Ktor

### Определение

Ktor — это **асинхронный фреймворк** для создания серверных и клиентских приложений на Kotlin, разработанный **JetBrains** (теми же людьми, кто создал Kotlin).

Ключевое отличие от Spring Boot: Ktor — **не контейнер**, а **библиотека**. Ты не "живёшь внутри фреймворка", а сам собираешь приложение из компонентов.

### Архитектура Ktor (как устроен внутри)

```
┌─────────────────────────────────────────────┐
│                 Application                  │
│                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  │
│  │ Plugin 1 │  │ Plugin 2 │  │ Plugin N │  │
│  │(Logging) │  │ (JSON)   │  │(Metrics) │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  │
│       │              │              │        │
│  ─────┴──────────────┴──────────────┴─────  │
│              Pipeline (корутины)              │
│  ─────────────────────────────────────────  │
│                                              │
│  ┌──────────────────────────────────────┐   │
│  │         Engine (Netty / CIO)          │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

**Три ключевых слоя:**

1. **Engine** — HTTP-сервер под капотом. Ktor поддерживает несколько движков:
   - **Netty** — production-grade, используем мы (лучшая производительность, поддержка HTTP/2)
   - **CIO** — Coroutine I/O, написан на чистом Kotlin (без внешних зависимостей)
   - **Jetty** — для случаев, когда нужен Servlet API
   - **Tomcat** — аналогично

2. **Pipeline** — цепочка обработки запроса. Каждый запрос проходит через pipeline как через middleware. Всё построено на **Kotlin Coroutines** — каждый запрос обрабатывается в корутине, без блокировки потоков.

3. **Plugins** (раньше назывались Features) — модульные расширения. Каждая функциональность — отдельный plugin, который "встраивается" в pipeline.

### Plugin-система (ключевая концепция)

В Spring Boot ты добавляешь starter-зависимость, и магия AutoConfiguration всё настраивает. В Ktor ты **явно устанавливаешь** каждый plugin:

```kotlin
// Наш pricing-service: Application.kt
fun Application.module(...) {
    configureLogging()        // install(CallLogging) { ... }
    configureSerialization()  // install(ContentNegotiation) { json(...) }
    configureMonitoring()     // install(MicrometerMetrics) { ... }
    configureErrorHandling()  // install(StatusPages) { ... }
    configureRouting(...)     // routing { ... }
}
```

**Плюсы**: ты точно знаешь, что установлено — нет скрытой магии.
**Минусы**: больше boilerplate на старте.

---

## Часть 2. Ktor vs Spring Boot — детальное сравнение

| Критерий | Ktor | Spring Boot |
|---|---|---|
| **Создатель** | JetBrains | VMware/Broadcom (Pivotal) |
| **Подход** | Библиотека (ты собираешь) | Фреймворк (ты живёшь внутри) |
| **Язык** | Kotlin-first, Kotlin-only | Java-first, Kotlin поддерживается |
| **Асинхронность** | Coroutines нативно | WebFlux (Reactor) или виртуальные потоки |
| **DI** | Нет встроенного (Koin, Kodein, или ручной) | Spring IoC Container (аннотации) |
| **Конфигурация** | HOCON / YAML + код | application.properties/yaml + AutoConfig |
| **Время старта** | ~0.3-1 сек | ~2-5 сек (с JPA/Kafka — дольше) |
| **Потребление RAM** | ~30-80 MB | ~150-400 MB |
| **Размер fat JAR** | ~15-30 MB | ~50-100 MB |
| **Кривая обучения** | Проще для Kotlin-разработчиков | Проще для Java-разработчиков |
| **Экосистема** | Маленькая, но растущая | Огромная |
| **ORM** | Exposed / вручную | Spring Data JPA (Hibernate) |
| **Тестирование** | `testApplication { }` (встроенный) | `@SpringBootTest` (поднимает контекст) |

### Пример: обработка запроса

**Ktor (наш pricing-service):**
```kotlin
// routes/QuoteRoutes.kt
fun Route.quoteRoutes(pricingService: PricingService) {
    route("/api/v1") {
        get("/quotes") {
            val sourceCountry = call.queryParameters["source_country"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing source_country")
            // ...
            val quote = pricingService.calculateQuote(request)  // suspend fun!
            call.respond(HttpStatusCode.OK, quote.toRestResponse())
        }
    }
}
```

**Spring Boot (для сравнения):**
```kotlin
@RestController
@RequestMapping("/api/v1")
class QuoteController(private val pricingService: PricingService) {
    @GetMapping("/quotes")
    fun getQuote(@RequestParam sourceCountry: String): QuoteResponse {
        return pricingService.calculateQuote(request)
    }
}
```

Spring короче, но за этой краткостью скрывается:
- `DispatcherServlet` → `HandlerMapping` → `HandlerAdapter` → `ArgumentResolver` → `MessageConverter`
- В Ktor: `routing { get("/path") { call.respond(...) } }` — **прямой DSL без магии**

---

## Часть 3. Почему для pricing-service выбран Ktor — 7 аргументов

### Аргумент 1: Pricing — stateless, CPU-light, I/O-bound сервис

Pricing-service делает три вещи:
1. Lookup в in-memory map (коридоры) — наносекунды
2. Арифметика BigDecimal — микросекунды
3. Запись/чтение Redis — миллисекунды (I/O)

**Нет JPA, нет Hibernate, нет Spring Data, нет SQL.** Зачем тащить Spring Boot со всем его обвесом, если основная работа — посчитать `(amount - fee) * rate` и положить в Redis?

Ktor запускается за **~0.5 сек** с ~50 MB RAM. Spring Boot с тем же функционалом — 3+ сек, 200+ MB.

### Аргумент 2: Kotlin Coroutines — нативная асинхронность

Redis-вызовы в pricing-service — асинхронные (Lettuce async):

```kotlin
// QuoteCacheService.kt — suspend функция, не блокирует поток
suspend fun save(quote: Quote) {
    val json = Json.encodeToString(CachedQuote.serializer(), cached)
    redisClientFactory.asyncCommands.setex(key, quoteTtlSeconds, json).await()  // неблокирующий
}
```

gRPC-сервис тоже на корутинах:

```kotlin
// PricingGrpcService.kt — suspend override
override suspend fun getQuote(request: GetQuoteRequest): QuoteResponse {
    val quote = pricingService.calculateQuote(quoteRequest)  // suspend
    return toQuoteResponse(quote)
}
```

В Ktor **весь стек** построен на корутинах. В Spring Boot для аналогичного поведения нужен WebFlux (Reactor), который добавляет Mono/Flux абстракции поверх.

### Аргумент 3: Polyglot architecture — демонстрация зрелости

На собеседовании это показывает, что ты:
- Умеешь **выбирать инструмент под задачу**, а не "Spring Boot для всего"
- Понимаешь trade-offs между фреймворками
- Можешь работать с разными технологиями в одном проекте

```
transfer-service  → Spring Boot (JPA, Kafka, сложный домен)
pricing-service   → Ktor (лёгкий, stateless, Redis + gRPC)
notification-gw   → Go (high-throughput fanout)
```

Это **реальный подход в индустрии** (Netflix, Grab, Wise используют разные стеки для разных сервисов).

### Аргумент 4: gRPC-интеграция проще без Spring

Spring Boot + gRPC требует:
- `grpc-spring-boot-starter` (сторонняя библиотека, не от Spring)
- Конфликты версий Netty (Spring использует свой Netty, gRPC — свой)
- Специальные аннотации `@GrpcService`

В Ktor мы просто поднимаем **gRPC-сервер рядом** на отдельном порту:

```kotlin
// GrpcServer.kt — чистый gRPC без обёрток
val server: Server = ServerBuilder
    .forPort(config.grpc.port)
    .addService(pricingGrpcService)
    .build()
```

```kotlin
// Application.kt — lifecycle через Ktor events
environment.monitor.subscribe(ApplicationStarted) { grpcServer.start() }
environment.monitor.subscribe(ApplicationStopped) { grpcServer.stop() }
```

Два сервера (HTTP:8082 + gRPC:9090) в одном процессе, без конфликтов.

### Аргумент 5: Размер Docker-образа и время холодного старта

В Kubernetes pricing-service масштабируется горизонтально (200 RPS, target p99 < 150ms). Чем быстрее поднимается pod, тем быстрее autoscaler реагирует на нагрузку.

| Метрика | Ktor | Spring Boot |
|---|---|---|
| Fat JAR | ~20 MB | ~70 MB |
| Docker image | ~120 MB | ~200 MB |
| Cold start | ~0.5 сек | ~3-5 сек |
| RAM (idle) | ~50 MB | ~200 MB |

При 10 репликах pricing-service: **500 MB** vs **2 GB** RAM — это реальная экономия на облаке.

### Аргумент 6: Отсутствие DI — не проблема для маленького сервиса

В pricing-service **4 класса бизнес-логики**:
- `PricingService` (расчёт)
- `QuoteCacheService` (Redis)
- `PricingGrpcService` (gRPC adapter)
- `RedisClientFactory` (connection pool)

Для 4 классов Spring IoC Container — overkill. Ручная инъекция в `main()` — прозрачна и читаема:

```kotlin
// Application.kt — "DI" в 3 строки
val quoteCacheService = QuoteCacheService(redisClientFactory, config.redis.quoteTtlSeconds)
val pricingService = PricingService(DEFAULT_CORRIDORS, quoteCacheService, config.redis.quoteTtlSeconds)
val grpcServer = GrpcServer(config, pricingService)
```

Если сервис вырастет до 20+ классов — можно добавить Koin (DI для Kotlin) за 10 минут.

### Аргумент 7: Тестируемость — встроенный test engine

Ktor предоставляет `testApplication { }` — поднимает сервер **in-memory**, без реального порта:

```kotlin
// QuoteRoutesTest.kt
@Test
fun `GET quotes returns 200`() = testApplication {
    application {
        configureSerialization()
        configureRouting(pricingService)
    }
    val response = client.get("/api/v1/quotes") {
        parameter("source_country", "US")
        // ...
    }
    response.status shouldBe HttpStatusCode.OK
}
```

Тесты запускаются **мгновенно** — нет Spring Context, нет component scan, нет AutoConfiguration. В transfer-service `@SpringBootTest` поднимается 5-10 секунд; в pricing-service тесты стартуют за миллисекунды.

---

## Часть 4. Архитектура pricing-service

### Диаграмма компонентов

```
                    ┌─────────────────────────────────────────┐
                    │           pricing-service                │
                    │                                          │
 HTTP :8082         │  ┌────────────┐    ┌─────────────────┐  │
 ──────────────────►│  │ QuoteRoutes│───►│                 │  │
                    │  └────────────┘    │  PricingService  │  │
 gRPC :9090         │  ┌────────────┐   │                  │  │
 ──────────────────►│  │PricingGrpc │───►│  calculateQuote()│  │
  (transfer-svc)    │  │  Service   │    │  validateQuote() │  │
                    │  └────────────┘    └───────┬─────────┘  │
                    │                            │             │
                    │                   ┌────────▼────────┐   │
                    │                   │ QuoteCacheService│   │
                    │                   │   (Redis TTL)    │   │
                    │                   └────────┬─────────┘   │
                    │                            │             │
                    └────────────────────────────┼─────────────┘
                                                 │
                                          ┌──────▼──────┐
                                          │    Redis     │
                                          │ quote:{id}   │
                                          │  TTL: 30s    │
                                          └─────────────┘
```

### Два протокола — одна бизнес-логика

pricing-service предоставляет **два интерфейса** к одному и тому же `PricingService`:

1. **REST (HTTP)** — для фронтенда / внешних клиентов / тестирования
   - `GET /api/v1/quotes?source_country=US&...` → рассчитать котировку
   - `GET /api/v1/quotes/{id}/validate` → проверить, жива ли котировка

2. **gRPC** — для transfer-service (inter-service communication)
   - `rpc GetQuote(GetQuoteRequest) → QuoteResponse`
   - `rpc ValidateQuote(ValidateQuoteRequest) → ValidateQuoteResponse`

Оба вызывают `pricingService.calculateQuote()` и `pricingService.validateQuote()`.

### Жизненный цикл котировки (Quote Lifecycle)

```
1. Клиент вызывает GetQuote (gRPC) или GET /quotes (REST)
         │
2. PricingService.calculateQuote():
   ├── Находит коридор в in-memory map (US_PH, US_MX, ...)
   ├── Валидирует delivery method, сумму, min/max
   ├── Считает: receiveAmount = (sendAmount - fee) × rate
   ├── Генерирует UUID как quoteId
   └── Сохраняет в Redis: key="quote:{id}", TTL=30 сек
         │
3. Клиент получает quoteId и показывает пользователю
         │
4. Пользователь подтверждает перевод (в transfer-service)
         │
5. transfer-service вызывает ValidateQuote(quoteId) через gRPC
         │
6. PricingService.validateQuote():
   ├── Ищет в Redis по key="quote:{id}"
   ├── Если нет или TTL истёк → isValid=false
   └── Если есть → isValid=true + данные котировки
         │
7. transfer-service создаёт Transfer с данными из котировки
```

### Формула расчёта

```
receiveAmount = (sendAmount - feeAmount) × exchangeRate

Пример (US → PH, $100):
  sendAmount    = 100.00 USD
  feeAmount     = 5.99 USD        (фиксированная комиссия коридора)
  exchangeRate  = 56.20 PHP/USD
  receiveAmount = (100.00 - 5.99) × 56.20 = 94.01 × 56.20 = 5283.36 PHP
```

Арифметика — `BigDecimal` с `RoundingMode.HALF_UP`, масштаб 2 знака. Это **обязательное требование в финтехе** — `Double` теряет точность на денежных операциях.

---

## Часть 5. Ключевые концепции Ktor (для собеседования)

### 5.1. Routing DSL

Ktor использует **type-safe DSL** для маршрутизации:

```kotlin
routing {
    route("/api/v1") {
        get("/quotes") { /* обработчик */ }
        post("/orders") { /* обработчик */ }
        route("/users") {
            get { /* список */ }
            get("/{id}") { /* по ID */ }
        }
    }
}
```

Это **обычные Kotlin-функции**, а не аннотации. Маршруты можно выносить в extension-функции:

```kotlin
// QuoteRoutes.kt — вынесенные маршруты
fun Route.quoteRoutes(pricingService: PricingService) {
    route("/api/v1") {
        get("/quotes") { ... }
    }
}

// Routing.kt — подключение
fun Application.configureRouting(pricingService: PricingService?) {
    routing {
        get("/health/live") { call.respondText("OK") }
        if (pricingService != null) {
            quoteRoutes(pricingService)
        }
    }
}
```

### 5.2. Content Negotiation

Автоматическая сериализация/десериализация тела запроса и ответа:

```kotlin
install(ContentNegotiation) {
    json(Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    })
}

// Использование — call.respond() автоматически сериализует в JSON
call.respond(HttpStatusCode.OK, quoteResponse)  // → {"quoteId":"...", ...}
```

Ktor использует **kotlinx.serialization** (compile-time, без рефлексии), а не Jackson (runtime reflection). Это быстрее и безопаснее.

### 5.3. StatusPages (обработка ошибок)

Аналог `@ControllerAdvice` в Spring, но через plugin:

```kotlin
install(StatusPages) {
    // По типу исключения
    exception<CorridorNotSupportedException> { call, cause ->
        call.respond(HttpStatusCode.UnprocessableEntity, ProblemDetail(
            title = "Unprocessable Entity",
            status = 422,
            detail = cause.message
        ))
    }

    // По HTTP-статусу (например, 404 от routing)
    status(HttpStatusCode.NotFound) { call, status ->
        call.respond(status, ProblemDetail(title = "Not Found", status = 404))
    }

    // Catch-all
    exception<Throwable> { call, cause ->
        call.respond(HttpStatusCode.InternalServerError, ProblemDetail(...))
    }
}
```

### 5.4. CallLogging + MDC (распределённая трассировка)

```kotlin
install(CallLogging) {
    level = Level.INFO
    mdc("traceId") { call ->
        call.request.headers["X-Trace-Id"] ?: UUID.randomUUID().toString()
    }
}

// Interceptor — пробрасывает traceId в response header
intercept(ApplicationCallPipeline.Plugins) {
    val traceId = MDC.get("traceId")
    call.response.header("X-Trace-Id", traceId)
}
```

Это обеспечивает **сквозную трассировку**: transfer-service отправляет `X-Trace-Id` → pricing-service подхватывает → все логи привязаны к одному trace.

### 5.5. Embedded Server vs Application Engine

Два способа запустить Ktor:

```kotlin
// 1. Embedded server (наш подход) — программный контроль
fun main() {
    embeddedServer(Netty, port = 8082) {
        module(config, grpcServer, pricingService)
    }.start(wait = true)
}

// 2. Engine main (через конфиг) — ktor.deployment в application.yaml
// fun main(args: Array<String>) = EngineMain.main(args)
```

Мы используем `embeddedServer`, потому что нам нужен программный контроль: инициализация Redis, запуск gRPC-сервера, DI вручную.

### 5.6. Lifecycle Events

```kotlin
// Application.kt — хуки жизненного цикла
environment.monitor.subscribe(ApplicationStarted) {
    grpcServer.start()  // gRPC стартует после Ktor
}

environment.monitor.subscribe(ApplicationStopped) {
    grpcServer.stop()       // graceful shutdown gRPC
    redisClientFactory?.close()  // закрываем Redis-пул
}
```

Аналог Spring `@PostConstruct` / `@PreDestroy`, но через event-систему.

---

## Часть 6. Когда НЕ стоит использовать Ktor

Ktor — не серебряная пуля. Spring Boot лучше, когда:

1. **Сложный домен с JPA/Hibernate** — Spring Data JPA + `@Transactional` + `ddl-auto: validate` — зрелая экосистема. В Ktor пришлось бы использовать Exposed (менее зрелый ORM) или JDBC вручную.

2. **Kafka consumers** — Spring Kafka даёт `@KafkaListener`, `@RetryableTopic`, DLT из коробки. В Ktor — писать consumer loop руками.

3. **Большая команда Java-разработчиков** — Spring Boot знают 90% Java-бэкендеров. Ktor знает значительно меньше людей.

4. **Enterprise-интеграции** — Spring Security, Spring Cloud, Spring Batch — нет аналогов в Ktor.

Именно поэтому **transfer-service на Spring Boot** (JPA, Kafka, сложный state machine), а **pricing-service на Ktor** (stateless вычисления + Redis).

---

## Часть 7. Готовые ответы для собеседования

### «Почему pricing-service на Ktor, а не на Spring Boot?»

> «Pricing-service — stateless сервис с простой задачей: посчитать комиссию и курс, закэшировать котировку в Redis на 30 секунд. Нет JPA, нет Kafka consumers, нет сложного домена.
>
> Ktor дал три преимущества:
> 1. **Быстрый старт и низкое потребление** — ~0.5 сек, ~50 MB RAM. При горизонтальном масштабировании на 10 реплик это экономия полутора гигабайт.
> 2. **Нативные корутины** — Redis-вызовы через Lettuce async + await(), gRPC через suspend-функции. Всё неблокирующее без Reactor/WebFlux.
> 3. **Чистая gRPC-интеграция** — gRPC-сервер рядом с HTTP на отдельном порту, без конфликтов Netty-версий, которые бывают в Spring + gRPC.
>
> Transfer-service остался на Spring Boot, потому что там JPA, Kafka, транзакции — для этого Spring значительно зрелее.»

### «Как устроена plugin-система Ktor?»

> «Ktor построен на pipeline — цепочке обработки запроса. Plugin — это модуль, который встраивается в определённую фазу pipeline. Например, ContentNegotiation встраивается в фазу Receive/Send и конвертирует JSON. StatusPages перехватывает исключения. CallLogging логирует каждый запрос.
>
> Ключевое отличие от Spring Boot AutoConfiguration: в Ktor каждый plugin устанавливается явно через `install()`. Нет classpath scanning, нет условной конфигурации, нет магии — ты всегда знаешь, что включено.»

### «Как вы решили проблему DI без Spring IoC?»

> «В pricing-service всего 4 класса бизнес-логики. Ручная инъекция в main() — три строки кода:
> ```
> val cache = QuoteCacheService(redis, ttl)
> val pricing = PricingService(corridors, cache, ttl)
> val grpc = GrpcServer(config, pricing)
> ```
> Это проще, чем поднимать IoC-контейнер. Если сервис вырастет — можно добавить Koin (Kotlin-native DI) за 10 минут, без рефакторинга.»

### «Почему kotlinx.serialization, а не Jackson?»

> «kotlinx.serialization работает на этапе компиляции — генерирует сериализаторы без рефлексии. Это:
> - Быстрее (нет reflection overhead)
> - Безопаснее (ошибки маппинга видны при компиляции, а не в runtime)
> - Меньше зависимостей
> - Нативно поддерживается Ktor
>
> Jackson используется в transfer-service, потому что Spring Boot его по умолчанию подключает, и Spring Data JPA тоже его использует.»

### «Как тестируете Ktor-сервис?»

> «Ktor предоставляет `testApplication {}` — поднимает HTTP-сервер in-memory, без реального порта. Тесты стартуют мгновенно, потому что нет Spring Context.
>
> gRPC тестируется через `InProcessServer` — то же самое, in-memory, без сети. Redis и MongoDB — через Testcontainers.
>
> Unit-тесты PricingService — с mockk для QuoteCacheService. Integration-тесты — полный стек с реальным Redis.»