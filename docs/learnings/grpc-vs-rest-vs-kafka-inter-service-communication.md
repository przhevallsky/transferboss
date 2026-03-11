# gRPC vs REST vs Kafka — выбор протокола между сервисами

## Контекст

В TransferBoss при создании перевода transfer-service синхронно вызывает pricing-service для валидации quote (курса + комиссии). Для этого используется gRPC, а не REST или Kafka. Ниже — подробное обоснование.

## Почему gRPC для pricing-service

### 1. Производительность на критическом пути

Вызов pricing — часть `createTransfer()`. Пользователь нажал "Отправить" и ждёт ответа. Каждая миллисекунда важна.

**HTTP/2 мультиплексирование:**
- REST чаще всего работает поверх HTTP/1.1 (Spring Boot + Tomcat по умолчанию). REST не привязан к версии HTTP — можно настроить HTTP/2, но это требует дополнительной конфигурации (SSL, Netty или h2c). На практике большинство REST-сервисов используют HTTP/1.1, где одно TCP-соединение = один запрос, и возникает head-of-line blocking.
  - `RestTemplate` — HTTP/1.1. Под капотом использует `HttpURLConnection` (стандартная Java), который поддерживает только HTTP/1.1. Замена на Apache HttpClient или OkHttp не меняет ситуацию — HTTP/2 нужно включать явно.
  - `WebClient` (реактивный, на Netty) — может работать с HTTP/2, но тоже не по умолчанию, требует явной настройки.
- gRPC **требует** HTTP/2 — это не опция, а часть протокола. Одно TCP-соединение мультиплексирует множество запросов параллельно. Никакого head-of-line blocking, никакого повторного handshake. Соединение создаётся один раз (`ManagedChannel`) и переиспользуется. HTTP/2 гарантирован из коробки без дополнительной настройки.

**Protocol Buffers (бинарная сериализация):**
- JSON (REST): текстовый формат, парсинг строк, кавычки, экранирование. Для `QuoteResponse` с 11 полями — ~400-500 байт.
- Protobuf (gRPC): бинарный формат, ~50-100 байт для тех же данных. Сериализация/десериализация в 5-10 раз быстрее чем JSON.

Для pricing-service с целевым p99 < 150ms при ~200 RPS (указано в proto-файле) — эта разница существенна.

**Deadline (таймаут):**
gRPC имеет встроенный механизм deadline — `stub.withDeadlineAfter(3, TimeUnit.SECONDS)`. Если pricing не ответил за 3 секунды — вызов автоматически отменяется на обоих сторонах. В REST нужно настраивать connect/read timeout отдельно, и сервер не узнает что клиент уже ушёл.

### 2. Строгий контракт через Proto Schema

В финансовом сервисе нельзя ошибиться с типами данных. Proto-файл (`pricing_service.proto`) задаёт контракт жёстко:

```protobuf
message QuoteResponse {
  string quote_id = 1;
  string send_amount = 2;      // BigDecimal как string — точность гарантирована
  string receive_amount = 3;
  string exchange_rate = 4;
  string fee_amount = 5;
  string fee_currency = 6;
  int64 expires_at_epoch_ms = 10;  // точный тип, не "any"
}
```

**Почему это лучше REST + JSON:**
- JSON не различает int/float/decimal. `"exchange_rate": 75` и `"exchange_rate": 75.0` — разные значения, но JSON парсер может интерпретировать одинаково. С protobuf — тип зафиксирован на этапе компиляции.
- Добавили поле в proto — клиент без перекомпиляции просто не видит новое поле (forward compatibility). Убрали поле — старый клиент получает дефолтное значение (backward compatibility).
- REST + OpenAPI/Swagger даёт документацию, но не compile-time проверки. Proto даёт и то, и другое.

**Кодогенерация:**
Из proto-файла автоматически генерируются:
- Java/Kotlin классы (`ValidateQuoteRequest`, `QuoteResponse`) — не нужно писать DTOs вручную
- Stub-классы (`PricingServiceGrpc.newBlockingStub()`) — не нужно писать HTTP-клиент, настраивать URL, headers, сериализацию
- При несовпадении контракта — ошибка компиляции, а не 500 в рантайме

### 3. Встроенная обработка ошибок через Status Codes

gRPC имеет стандартизированные статус-коды, заточенные под межсервисное взаимодействие:

```kotlin
when (e.status.code) {
    Status.Code.INVALID_ARGUMENT -> // невалидный quoteId
    Status.Code.NOT_FOUND ->        // quote не найден
    Status.Code.DEADLINE_EXCEEDED -> // таймаут
    Status.Code.UNAVAILABLE ->      // сервис недоступен
}
```

В REST коды HTTP смешивают транспортные и бизнес-ошибки: 404 — это "endpoint не существует" или "ресурс не найден"? 503 — это nginx proxy или сам сервис? gRPC разделяет это чётко.

### 4. Circuit Breaker интеграция

В нашем `PricingClient.kt` gRPC обёрнут в Resilience4j Circuit Breaker:
- Если pricing-service падает — circuit breaker открывается после 5 неудачных вызовов
- 30 секунд запросы не уходят вообще (быстрый fail, не грузим мёртвый сервис)
- Потом пробует снова (half-open state)

Это работает с любым протоколом, но gRPC + circuit breaker = особенно эффективно, потому что gRPC deadline гарантирует что "зависший" вызов не будет ждать бесконечно и быстро засчитается как failure.

## Почему НЕ REST для pricing

| Аспект | REST + JSON | gRPC + Protobuf |
|--------|-------------|-----------------|
| Размер payload | ~400-500 байт | ~50-100 байт |
| Сериализация | Медленная (текст) | Быстрая (бинарный) |
| Соединение | Обычно HTTP/1.1 (HTTP/2 возможен, но требует настройки) | HTTP/2 обязателен, из коробки |
| Контракт | OpenAPI (документация) | Proto (компиляция + документация) |
| Кодогенерация | Частичная (Swagger Codegen) | Полная (grpc-java/grpc-kotlin) |
| Таймауты | Ручная настройка | Встроенный deadline propagation |
| Ошибки | HTTP-коды (размытые) | gRPC Status (точные) |
| Браузеры | Поддерживается | Не поддерживается |

REST проигрывает по всем пунктам кроме поддержки браузеров, которая тут не нужна — это internal service-to-service call.

## Почему НЕ Kafka (async) для pricing

Quote validation **должна быть синхронной**:

1. **Курс протухает.** Quote живёт 30 секунд (`ttl_seconds` в proto). Если отправить запрос в Kafka, пока consumer обработает — quote может истечь. Пользователь не может ждать.

2. **Ответ нужен немедленно.** Результат `validateQuote` определяет — создавать transfer или нет. Если quote невалидный — нужно вернуть 422 клиенту прямо сейчас, а не "потом как-нибудь".

3. **Request-Reply через Kafka — антипаттерн** для low-latency. Можно сделать, но это ~50-200ms overhead на publish + consume + reply, плюс сложность с correlation ID, timeout handling, и всё ради того что gRPC делает из коробки за ~10-50ms.

## Когда Kafka — правильный выбор

Kafka используется для **асинхронных** операций где ответ не нужен немедленно:

- `PAYMENT_REQUESTED` — transfer создан, платёж обработается когда обработается
- `PAYOUT_COMPLETED` — уведомление что выплата прошла
- События для notification-gateway

Пользователь получил 201 Created и видит статус "Payment Pending" — это нормально. А вот "Quote validation pending" — нет.

## Правило выбора протокола в TransferBoss

| Сценарий | Протокол | Пример |
|----------|----------|--------|
| Клиент → сервис (public API) | REST + JSON | Mobile app → TransferController |
| Сервис → сервис (синхронно, критический путь) | gRPC + Protobuf | transfer-service → pricing-service |
| Сервис → сервис (асинхронно, eventual consistency) | Kafka | transfer-service → payment-service → payout |
| Broadcast / уведомления | Kafka | Любой сервис → notification-gateway |