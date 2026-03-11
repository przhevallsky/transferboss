# Анатомия gRPC-клиента: разбор PricingClient.kt

## Контекст

При создании перевода `TransferService.createTransfer()` синхронно вызывает pricing-service через gRPC, чтобы валидировать quote (котировку). Весь клиентский код сосредоточен в `PricingClient.kt`. Этот документ разбирает **каждый элемент** клиента: зачем он нужен, как работает, и какие альтернативы существуют.

Файлы:
- Клиент: `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/client/PricingClient.kt`
- Конфигурация канала: `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/config/GrpcConfig.kt`
- Proto-контракт: `services/transfer-service/src/main/proto/pricing/v1/pricing_service.proto`

---

## 1. ManagedChannel — TCP-соединение с сервером

### Что это

```kotlin
// GrpcConfig.kt
@Bean
fun pricingChannel(): ManagedChannel {
    val ch = ManagedChannelBuilder
        .forAddress(host, port)     // куда подключаемся
        .usePlaintext()             // без TLS
        .keepAliveTime(30, TimeUnit.SECONDS)  // keep-alive пинги
        .build()
    channel = ch
    return ch
}
```

`ManagedChannel` — это **абстракция поверх HTTP/2-соединения** между transfer-service и pricing-service. Слово "managed" означает, что gRPC-библиотека сама управляет жизненным циклом соединения: переподключение при обрыве, балансировка нагрузки, DNS-резолвинг.

### Почему один канал на всё приложение

Канал создаётся как Spring Bean (singleton) — один на весь transfer-service. Это не ошибка, а правильный подход:

- **HTTP/2 мультиплексирование**: один TCP-socket обрабатывает тысячи параллельных запросов. Каждый запрос — это "stream" внутри одного соединения. В HTTP/1.1 пришлось бы открывать отдельное соединение на каждый параллельный запрос.
- **Экономия ресурсов**: TCP handshake + TLS handshake — дорогие операции (~50-150ms). Создав канал один раз, экономим это на каждом вызове.
- **Thread-safe**: `ManagedChannel` потокобезопасен. 200 RPS из 10 потоков — все идут через один канал без блокировок.

**Аналогия**: `ManagedChannel` — как connection pool в JDBC (`HikariCP`), только для gRPC. Разница в том, что HTTP/2 настолько эффективен, что одного "соединения" обычно хватает (пул не нужен).

### usePlaintext() — почему без TLS

```kotlin
.usePlaintext()
```

В production между сервисами внутри одного Kubernetes кластера / Docker network TLS часто не нужен — трафик не выходит за пределы приватной сети. В нашем случае pricing-service доступен по адресу `transferhub-pricing:50051` внутри Docker-сети.

Для production с TLS было бы:
```kotlin
.useTransportSecurity()
.sslContext(...)  // или через service mesh (Istio/Linkerd), который добавляет mTLS прозрачно
```

### keepAliveTime — зачем пинговать

```kotlin
.keepAliveTime(30, TimeUnit.SECONDS)
```

HTTP/2 соединение может "тихо умереть" — сервер перезапустился, firewall сбросил idle-соединение, сеть мигнула. Без keep-alive клиент узнает об этом только при следующем запросе (получит ошибку, потеряет время).

Keep-alive каждые 30 секунд отправляет HTTP/2 PING frame. Если ответа нет — канал знает, что соединение мёртвое, и переподключается **до** того, как придёт реальный запрос.

### Graceful shutdown

```kotlin
@PreDestroy
fun shutdown() {
    channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
}
```

`@PreDestroy` вызывается Spring'ом при остановке приложения. `shutdown()` — мягкое завершение: дожидаемся ответов на текущие запросы (до 5 сек), потом закрываем TCP-соединение. Без этого можно потерять in-flight запросы при деплое.

---

## 2. Stub — прокси-объект для вызова методов

### Что это

```kotlin
private val stub = PricingServiceGrpc.newBlockingStub(pricingChannel)
```

Stub (стаб) — это **клиентский прокси**, сгенерированный из proto-файла. Он выглядит как обычный объект с методами (`validateQuote(request)`), но внутри каждый вызов метода:

1. Сериализует аргумент в protobuf-байты
2. Отправляет HTTP/2 запрос через `ManagedChannel`
3. Получает HTTP/2 ответ
4. Десериализует байты обратно в protobuf-объект

Ты вызываешь `stub.validateQuote(request)` — как будто это локальный метод. Но на самом деле данные летят по сети на другой сервер.

### Три типа stub'ов

gRPC-java генерирует три варианта stub'а из одного proto-файла:

| Тип | Создание | Поведение | Когда использовать |
|-----|----------|-----------|-------------------|
| **BlockingStub** | `newBlockingStub()` | Блокирует вызывающий поток до получения ответа | Простые синхронные вызовы. Мы используем это |
| **FutureStub** | `newFutureStub()` | Возвращает `ListenableFuture<Response>` | Когда нужно запустить несколько вызовов параллельно и подождать все |
| **AsyncStub** | `newStub()` | Принимает `StreamObserver<Response>` callback | Стриминг, реактивные паттерны |

**Почему BlockingStub здесь:**
`createTransfer()` — синхронный метод в `@Transactional`. Нам нужен ответ pricing-service прямо здесь, чтобы решить: создавать Transfer или вернуть ошибку. Async/Future stub усложнил бы код без выгоды.

### Важное свойство: stub immutable, но настраиваемый

```kotlin
stub.withDeadlineAfter(3, TimeUnit.SECONDS).validateQuote(request)
```

`withDeadlineAfter()` **не мутирует** stub — создаёт новый с дедлайном. Оригинальный `stub` остаётся без дедлайна. Это позволяет безопасно переиспользовать один stub из разных потоков с разными настройками.

---

## 3. Protobuf Request/Response — Builder паттерн

### Создание запроса

```kotlin
val request = ValidateQuoteRequest.newBuilder()
    .setQuoteId(quoteId)
    .build()
```

Protobuf-объекты **immutable** (неизменяемые). Создаются через Builder pattern:

1. `newBuilder()` — создаёт мутабельный builder
2. `.setQuoteId(...)` — устанавливает поля (возвращает `this` для chaining)
3. `.build()` — создаёт финальный immutable объект

**Почему immutable**: protobuf-объекты thread-safe, могут кешироваться, передаваться между потоками без синхронизации. Builder — единственный способ задать значения.

### Маппинг protobuf → domain

```kotlin
val quote = response.quote
QuoteData(
    quoteId = quote.quoteId,
    sendAmount = BigDecimal(quote.sendAmount),    // string → BigDecimal
    receiveAmount = BigDecimal(quote.receiveAmount),
    exchangeRate = BigDecimal(quote.exchangeRate),
    feeAmount = BigDecimal(quote.feeAmount),
    feeCurrency = quote.feeCurrency,
    sendCurrency = quote.sendCurrency,
    receiveCurrency = quote.receiveCurrency,
)
```

**Зачем маппинг**: protobuf Java-классы неудобны в бизнес-логике:
- Суммы — `String` в proto (потому что protobuf не имеет decimal-типа). В домене — `BigDecimal` для точных финансовых вычислений
- Proto-геттеры: Java-стиль (`getQuoteId()`), много boilerplate. Kotlin data class — лаконичнее
- Proto-объекты тянут зависимость на `protobuf-java` — не хотим протаскивать её через всё приложение

**`QuoteData`** — наш domain DTO, используется в `TransferService` для заполнения Transfer entity. Proto-зависимость изолирована в `PricingClient`.

---

## 4. Deadline — таймаут с пробрасыванием

```kotlin
val response = stub
    .withDeadlineAfter(3, TimeUnit.SECONDS)
    .validateQuote(request)
```

### Что это

Deadline — это **абсолютное время**, до которого ответ должен быть получен. `withDeadlineAfter(3, SECONDS)` означает: "если через 3 секунды ответа нет — отмена".

### Чем отличается от HTTP timeout

| | HTTP timeout | gRPC deadline |
|--|-------------|---------------|
| Где задаётся | На клиенте (connect timeout + read timeout) | На клиенте, но **пробрасывается на сервер** |
| Знает ли сервер | Нет. Сервер не знает, что клиент уже ушёл | Да. Сервер видит оставшееся время |
| Каскадные вызовы | Каждый hop — свой таймаут, они суммируются | Deadline единый: A→B→C, если у A deadline 3 сек, B видит "осталось 2.8 сек", C видит "осталось 2.5 сек" |
| При превышении | IOException на клиенте | `StatusRuntimeException` с `DEADLINE_EXCEEDED` на обоих сторонах |

**Пример проблемы без deadline propagation:**
- Клиент: read timeout 3 сек → через 3 сек бросает ошибку
- Сервер: не знает, что клиент ушёл → продолжает обрабатывать запрос → тратит ресурсы впустую
- С gRPC deadline: сервер увидит, что дедлайн истёк, и прекратит обработку

### Почему 3 секунды

Pricing-service целевой p99 — 150ms. 3 секунды — щедрый запас (20x от нормы). Это защита от:
- Сеть подтормаживает (GC pause, congestion)
- Pricing-service перегружен, но жив
- Редкие тяжёлые запросы (cold cache)

Если 3 секунды прошли — значит что-то серьёзно сломалось, и нет смысла ждать дольше.

---

## 5. Circuit Breaker — автоматический выключатель

### Аналогия

Автомат в электрощитке: если ток слишком большой (короткое замыкание) — автомат выбивает, защищая проводку. Когда починили — включаешь обратно.

В микросервисах "короткое замыкание" — это когда downstream-сервис падает, а upstream продолжает слать запросы. Каждый запрос — таймаут 3 секунды, заблокированный поток, исчерпание thread pool. Один упавший сервис может положить весь кластер — это **cascading failure**.

### Три состояния

```
                  ┌─────────────────────────────┐
                  ▼                             │
             ┌─────────┐   >50% ошибок    ┌──────────┐
             │ CLOSED  │ ───────────────► │  OPEN    │
             │ (норма) │                  │ (отказ)  │
             └─────────┘                  └──────────┘
                  ▲                             │
                  │  вызов успешен        30 сек прошло
                  │                             │
             ┌──────────┐                       │
             └──────────┘
```

**CLOSED** (замкнут, ток идёт): все запросы проходят к pricing-service. CB считает статистику.

**OPEN** (разомкнут, ток не идёт): запросы **не отправляются** к pricing-service вообще. Сразу кидается `CallNotPermittedException`. Это мгновенный fail (~0ms) вместо ожидания 3 сек.

**HALF_OPEN** (полуоткрыт): пропускает несколько пробных запросов. Если успешны — возвращается в CLOSED. Если нет — обратно в OPEN.

### Конфигурация в нашем коде

```kotlin
private val circuitBreaker = CircuitBreaker.of("pricing-service",
    CircuitBreakerConfig.custom()
        .failureRateThreshold(50f)
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .slidingWindowSize(10)
        .minimumNumberOfCalls(5)
        .build()
)
```

| Параметр | Значение | Что значит |
|----------|----------|-----------|
| `failureRateThreshold(50f)` | 50% | Если из последних N вызовов больше половины упали — открываем CB |
| `slidingWindowSize(10)` | 10 | Считаем статистику по последним 10 вызовам (скользящее окно) |
| `minimumNumberOfCalls(5)` | 5 | Не открываем CB пока не было хотя бы 5 вызовов (избегаем ложных срабатываний при старте) |
| `waitDurationInOpenState(30s)` | 30 сек | Сколько CB остаётся открытым перед пробным запросом |

**Сценарий**: transfer-service стартовал, первые 5 запросов к pricing ушли. 3 из 5 упали (60% > 50%) → CB открывается. Следующие 30 секунд все вызовы `validateQuote()` мгновенно получают ошибку. Через 30 сек CB переходит в HALF_OPEN, пропускает 1 запрос. Если OK — CLOSED. Если нет — ещё 30 сек OPEN.

### executeSupplier — обёртка

```kotlin
return circuitBreaker.executeSupplier {
    // ... gRPC вызов ...
}
```

`executeSupplier` делает три вещи:
1. Проверяет состояние CB: если OPEN — сразу `CallNotPermittedException`
2. Выполняет лямбду (gRPC вызов)
3. Записывает результат: успех или ошибка → обновляет статистику

---

## 6. Обработка ошибок — четыре уровня

```kotlin
try {
    return circuitBreaker.executeSupplier { /* ... */ }
} catch (e: QuoteExpiredException) { throw e }
  catch (e: StatusRuntimeException) { /* ... */ }
  catch (e: CallNotPermittedException) { /* ... */ }
  catch (e: Exception) { /* ... */ }
```

Порядок catch-блоков критически важен — от самого специфичного к самому общему:

### Уровень 1: QuoteExpiredException (бизнес-ошибка)

```kotlin
catch (e: QuoteExpiredException) { throw e }
```

Это **наше** исключение, брошенное внутри лямбды (когда `response.isValid == false`). Не нужно оборачивать или логировать — просто пробрасываем. Контроллер вернёт 422.

**Зачем отдельный catch**: без него `QuoteExpiredException` попало бы в `catch (e: Exception)` и превратилось в `PricingUnavailableException` (503) — неправильный HTTP-код и потеря информации.

### Уровень 2: StatusRuntimeException (gRPC-ошибка)

```kotlin
catch (e: StatusRuntimeException) {
    when (e.status.code) {
        Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND ->
            throw QuoteExpiredException(quoteId, e.status.description)
        else ->
            throw PricingUnavailableException("Pricing service error: ${e.status.code}", e)
    }
}
```

`StatusRuntimeException` — стандартное исключение gRPC. Содержит `Status` с кодом и описанием. Коды gRPC — аналог HTTP-кодов, но для inter-service:

| gRPC Status | Аналог HTTP | Значение | Наша обработка |
|-------------|-------------|----------|----------------|
| `NOT_FOUND` | 404 | Quote не найден в Redis | → QuoteExpiredException (422) |
| `INVALID_ARGUMENT` | 400 | Невалидный UUID | → QuoteExpiredException (422) |
| `DEADLINE_EXCEEDED` | 408/504 | Таймаут 3 сек | → PricingUnavailableException (503) |
| `UNAVAILABLE` | 503 | Сервис упал | → PricingUnavailableException (503) |
| `INTERNAL` | 500 | Баг в pricing | → PricingUnavailableException (503) |

**Принцип маппинга**: клиентские ошибки (плохой quoteId) → 4xx (QuoteExpired). Серверные ошибки (pricing упал) → 5xx (PricingUnavailable). Так фронтенд знает: 422 — показать "quote expired, get a new one". 503 — показать "try again later".

### Уровень 3: CallNotPermittedException (circuit breaker)

```kotlin
catch (e: CallNotPermittedException) {
    log.warn("Circuit breaker open for pricing-service: {}", e.message)
    throw PricingUnavailableException("Pricing service circuit breaker is open", e)
}
```

Это исключение Resilience4j. Означает: CB открыт, запрос к pricing-service **даже не отправлялся**. Логируем как `warn` (не `error`), потому что это ожидаемое поведение защитного механизма, а не неожиданная ошибка.

### Уровень 4: Exception (всё остальное)

```kotlin
catch (e: Exception) {
    log.error("Unexpected error calling pricing-service", e)
    throw PricingUnavailableException("Pricing service unavailable: ${e.message}", e)
}
```

Страховка от неожиданных ошибок: DNS-резолв упал, SSL-ошибка, Netty выбросил что-то экзотическое. Логируем как `error` (неожиданное) и оборачиваем в PricingUnavailableException.

---

## 7. Полный поток вызова (sequence)

```
TransferService.createTransfer()
    │
    ├─ pricingClient.validateQuote("abc-123")
    │       │
    │       ├── CircuitBreaker: проверка состояния
    │       │       ├── OPEN → CallNotPermittedException → 503
    │       │       └── CLOSED/HALF_OPEN → продолжаем
    │       │
    │       ├── Строим protobuf: ValidateQuoteRequest { quote_id: "abc-123" }
    │       │       └── Сериализация: объект → ~20 байт
    │       │
    │       ├── gRPC вызов через ManagedChannel
    │       │       ├── HTTP/2 POST → pricing-service:50051
    │       │       ├── Deadline: 3 секунды
    │       │       ├── Ожидание ответа (поток заблокирован)
    │       │       └── Десериализация ответа → ValidateQuoteResponse
    │       │
    │       ├── Проверка ответа
    │       │       ├── is_valid=false → QuoteExpiredException → 422
    │       │       └── is_valid=true → продолжаем
    │       │
    │       ├── Маппинг: QuoteResponse (protobuf) → QuoteData (Kotlin data class)
    │       │
    │       └── CircuitBreaker: запись результата (success/failure)
    │
    ├─ Проверка валют quote vs request (QuoteCorridorMismatchException)
    │
    └─ Создание Transfer entity с данными из QuoteData
```

---

## 8. Что НЕ делает этот клиент (и почему)

### Нет retry

В клиенте нет встроенного retry (повторных попыток). Причина: quote живёт 30 секунд. Если pricing недоступен и CB открыт — retry через 30 секунд бесполезен, quote уже протух. Лучше сразу вернуть ошибку пользователю и предложить пересоздать quote.

### Нет кеширования

Результат `validateQuote` не кешируется. Причина: quote — одноразовый. После привязки к transfer повторно использоваться не должен. Кешировать "is_valid" бессмысленно — через секунду ответ может измениться (TTL истёк).

### Нет асинхронности

Используется blocking stub, а не async. Причина: `createTransfer()` работает внутри `@Transactional`. Spring DB-транзакция привязана к текущему потоку. Async-вызов потребовал бы переключения на другой поток, усложнения работы с транзакцией, и всё это — ради одного вызова. Не оправдано.

### Нет load balancing

`ManagedChannel` подключается к одному адресу (`host:port`). В production с несколькими инстансами pricing-service балансировка происходит на уровне:
- Kubernetes Service (L4 load balancing)
- Или gRPC client-side LB (требует DNS SRV записи или service mesh)
