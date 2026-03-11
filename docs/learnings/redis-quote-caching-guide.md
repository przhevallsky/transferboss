# Redis для квот — Полное руководство

## Оглавление

1. [Зачем Redis для квот](#1-зачем-redis-для-квот)
2. [Теория: паттерны кэширования](#2-теория-паттерны-кэширования)
3. [Архитектура Redis в проекте](#3-архитектура-redis-в-проекте)
4. [Реализация в pricing-service](#4-реализация-в-pricing-service)
5. [Реализация в transfer-service](#5-реализация-в-transfer-service)
6. [Сериализация: почему BigDecimal → String](#6-сериализация)
7. [Docker конфигурация Redis](#7-docker-конфигурация)
8. [Тестирование](#8-тестирование)
9. [Трейдоффы и ограничения](#9-трейдоффы-и-ограничения)
10. [Что нужно для прода](#10-что-нужно-для-прода)
11. [TTL, race conditions и двойная проверка](#11-ttl-и-race-conditions)
12. [FAQ](#12-faq)

---

## 1. Зачем Redis для квот

### Проблема

Когда пользователь инициирует перевод, система должна:
1. **Рассчитать котировку** (курс, комиссия, итоговая сумма) — pricing-service
2. **Зафиксировать курс на 30 секунд** (rate lock) — пока пользователь подтверждает
3. **Проверить котировку** при создании трансфера — transfer-service вызывает `ValidateQuote`

Котировка живёт 30 секунд. После этого — протухает. Это **не долгосрочные данные**.

### Почему не PostgreSQL?

| Критерий | PostgreSQL | Redis |
|----------|-----------|-------|
| Латентность чтения | ~1-5ms (диск/кэш) | ~0.1-0.5ms (RAM) |
| Автоматическое удаление по TTL | Нет (нужен cron/pg_cron) | Встроенный `SETEX` |
| Нагрузка на запись+удаление | Создаёт мёртвые строки (VACUUM) | Просто освобождает память |
| Масштабирование | Вертикальное | Горизонтальное (Cluster) |
| Подходит для | Долгосрочные данные, транзакции | Короткоживущие данные, кэш |

PostgreSQL пришлось бы:
- Писать row → читать row → удалять row через 30 секунд
- Запускать `VACUUM` для очистки мёртвых строк
- При 200 RPS = 12,000 записей/минуту, которые тут же удаляются — бесполезная нагрузка на WAL и диск

### Почему не MongoDB?

MongoDB в проекте используется для **corridor config** (долгосрочная конфигурация коридоров). Для TTL-данных Redis лучше:
- MongoDB TTL index проверяет истечение раз в 60 секунд (неточно для 30-секундного TTL)
- Redis удаляет ключи с точностью до миллисекунды (lazy + periodic expiration)

### Почему Redis — правильный выбор

1. **Встроенный TTL** — `SETEX key 30 value` автоматически удалит через 30 секунд
2. **Скорость** — всё в RAM, p99 < 1ms
3. **Простота** — SET/GET, нет схемы, нет миграций
4. **Атомарность** — `SETEX` = SET + EXPIRE в одной команде
5. **Идеально для rate lock** — зафиксировал курс, подождал подтверждения, проверил — всё

---

## 2. Теория: паттерны кэширования

В нашем проекте используются **два разных паттерна** в двух разных сервисах.

### 2.1 Write-Through (pricing-service → квоты)

```
Клиент → PricingService.calculateQuote()
              │
              ├── 1. Рассчитал котировку
              ├── 2. Записал в Redis (SETEX)  ← запись ВСЕГДА происходит
              └── 3. Вернул ответ клиенту

Клиент → PricingService.validateQuote()
              │
              ├── 1. Читает из Redis (GET)
              ├── 2. Проверяет expiry
              └── 3. Вернул результат
```

**Суть:** данные ВСЕГДА пишутся в кэш при создании. Нет "основного хранилища" — Redis И ЕСТЬ единственное хранилище для квот. Котировка живёт только в Redis 30 секунд — потом исчезает навсегда.

**Это не совсем классический кэш** — это скорее **временное хранилище** (ephemeral store). Котировки не хранятся в PostgreSQL или MongoDB. Redis — единственный источник правды для активных квот.

### 2.2 Cache-Aside (transfer-service → статус трансфера)

```
GET /transfers/{id}
    │
    ├── 1. Проверить Redis (GET)
    │       ├── HIT  → вернуть из кэша
    │       └── MISS → продолжить ↓
    │
    ├── 2. Загрузить из PostgreSQL
    ├── 3. Положить в Redis (SET + TTL)
    └── 4. Вернуть ответ

Kafka Event (статус изменился)
    │
    ├── 1. Обновить в PostgreSQL
    └── 2. Удалить из Redis (DELETE)  ← инвалидация
```

**Суть:** основное хранилище — PostgreSQL. Redis — кэш для ускорения чтения. При изменении данных (через Kafka) — кэш инвалидируется.

### 2.3 Сравнение паттернов

| | Write-Through (квоты) | Cache-Aside (трансферы) |
|---|---|---|
| Основное хранилище | Redis (единственное!) | PostgreSQL |
| Запись | Всегда в Redis | Сначала в БД, потом в кэш |
| Чтение | Всегда из Redis | Сначала кэш, потом БД |
| Инвалидация | TTL (автоматическая) | Явный DELETE из Kafka consumer |
| Потеря данных при рестарте Redis | Котировки пропадут (ОК — 30 сек) | Перечитает из PostgreSQL |

### 2.4 Другие паттерны (для справки)

**Read-Through** — кэш сам ходит в БД при промахе (мы так не делаем):
```
GET → Redis MISS → Redis сам идёт в PostgreSQL → кладёт к себе → возвращает
```

**Write-Behind (Write-Back)** — пишем в кэш, а в БД асинхронно потом:
```
WRITE → Redis → [через N секунд] → PostgreSQL
```
Опасно: при падении Redis данные теряются до flush в БД.

---

## 3. Архитектура Redis в проекте

### Один инстанс Redis — два сервиса

```
┌──────────────────────┐     ┌──────────────────────┐
│   pricing-service    │     │  transfer-service     │
│                      │     │                       │
│  QuoteCacheService   │     │  TransferCacheService │
│  (Lettuce async)     │     │  (Spring Data Redis)  │
│                      │     │                       │
│  Ключи:              │     │  Ключи:               │
│  quote:{uuid}        │     │  transfer:status:{uuid}│
│  TTL: 30s            │     │  TTL: 30s             │
└─────────┬────────────┘     └──────────┬────────────┘
          │                             │
          └─────────┬───────────────────┘
                    ▼
          ┌─────────────────┐
          │  Redis 7 Alpine │
          │  Port: 6379     │
          │  256MB RAM      │
          │  LRU eviction   │
          │  AOF persistence│
          └─────────────────┘
```

### Разные клиентские библиотеки — почему?

- **pricing-service** использует **Lettuce** напрямую — потому что это Ktor (не Spring), нужен async API с корутинами
- **transfer-service** использует **Spring Data Redis** (внутри тоже Lettuce) — потому что это Spring Boot, используем его экосистему

### Неймспейсы ключей

Ключи разделены префиксами — не конфликтуют:
```
quote:a1b2c3d4-...          ← pricing-service
transfer:status:e5f6g7h8-...  ← transfer-service
```

---

## 4. Реализация в pricing-service

### 4.1 RedisClientFactory — подключение

**Файл:** `services/pricing-service/src/main/kotlin/com/transferhub/pricing/config/RedisClientFactory.kt`

```kotlin
class RedisClientFactory(private val redisConfig: RedisConfig) {

    private lateinit var client: RedisClient
    private lateinit var connection: StatefulRedisConnection<String, String>

    // Ленивый доступ к async-командам
    val asyncCommands: RedisAsyncCommands<String, String>
        get() = connection.async()

    fun connect() {
        val uri = RedisURI.builder()
            .withHost(redisConfig.host)    // из env: REDIS_HOST (default: localhost)
            .withPort(redisConfig.port)    // из env: REDIS_PORT (default: 6379)
            .build()
        client = RedisClient.create(uri)
        connection = client.connect()      // одно TCP-соединение
    }

    fun close() {
        if (::connection.isInitialized) connection.close()
        if (::client.isInitialized) client.shutdown()
    }
}
```

**Что происходит:**
1. `RedisClient.create(uri)` — создаёт Lettuce-клиент (не подключается ещё)
2. `client.connect()` — устанавливает одно TCP-соединение
3. `connection.async()` — возвращает объект для отправки async-команд через это соединение

**Lettuce** — единственный Redis-клиент, встроенный в Spring Boot. Он thread-safe: одно соединение может обрабатывать множество параллельных команд через pipelining (multiplexing). Это **не** Jedis (там нужен пул).

### 4.2 QuoteCacheService — сохранение и чтение

**Файл:** `services/pricing-service/src/main/kotlin/com/transferhub/pricing/service/QuoteCacheService.kt`

#### Сохранение (save)

```kotlin
suspend fun save(quote: Quote) {
    val key = "quote:${quote.quoteId}"              // 1. Формируем ключ
    val cached = CachedQuote.fromDomain(quote)       // 2. Quote → CachedQuote (BigDecimal → String)
    val json = Json.encodeToString(                   // 3. Сериализуем в JSON-строку
        CachedQuote.serializer(), cached
    )
    redisClientFactory.asyncCommands                  // 4. Отправляем в Redis
        .setex(key, quoteTtlSeconds, json)            //    SETEX = SET + EXPIRE атомарно
        .await()                                      // 5. Ждём подтверждения (suspend)
}
```

**Redis команда:** `SETEX quote:a1b2c3d4 30 {"quoteId":"a1b2c3d4",...}`

Это ОДНА атомарная команда. Не может случиться так, что ключ записался, а TTL не установился.

#### Чтение (get)

```kotlin
suspend fun get(quoteId: String): Quote? {
    val key = "quote:$quoteId"
    val json = redisClientFactory.asyncCommands
        .get(key)                                    // 1. GET из Redis
        .await()                                     // 2. Ждём ответа (suspend)
        ?: return null                               // 3. Ключ не найден (истёк/не было)

    return try {
        val cached = Json.decodeFromString(           // 4. JSON → CachedQuote
            CachedQuote.serializer(), json
        )
        cached.toDomain()                             // 5. CachedQuote → Quote (String → BigDecimal)
    } catch (e: Exception) {
        logger.warn("Failed to deserialize: {}", e.message)
        null                                          // 6. Если JSON битый — как будто нет ключа
    }
}
```

**Важно:** `get` возвращает `null` и при отсутствии ключа, и при ошибке десериализации. Вызывающий код не различает эти случаи — для него квота просто "не найдена".

#### CachedQuote — промежуточный класс для сериализации

```kotlin
@Serializable
private data class CachedQuote(
    val quoteId: String,
    val sendAmount: String,       // ← BigDecimal хранится как String!
    val receiveAmount: String,
    val exchangeRate: String,
    val feeAmount: String,
    val expiresAtEpochMs: Long,   // ← Instant хранится как epoch millis
    // ... остальные поля
) {
    fun toDomain(): Quote = Quote(
        sendAmount = BigDecimal(sendAmount),          // String → BigDecimal
        expiresAt = Instant.ofEpochMilli(expiresAtEpochMs),  // Long → Instant
        // ...
    )

    companion object {
        fun fromDomain(quote: Quote): CachedQuote = CachedQuote(
            sendAmount = quote.sendAmount.toPlainString(),   // BigDecimal → String
            expiresAtEpochMs = quote.expiresAt.toEpochMilli(), // Instant → Long
            // ...
        )
    }
}
```

**Зачем отдельный класс, а не сериализовать Quote напрямую?**
- `Quote` содержит `BigDecimal` и `Instant` — kotlinx.serialization не умеет их сериализовать из коробки
- Можно написать кастомный serializer, но отдельный DTO проще и явнее
- Разделение: `Quote` — доменная модель, `CachedQuote` — формат хранения

### 4.3 Как PricingService использует кэш

```kotlin
// calculateQuote() — ПИШЕТ в кэш
suspend fun calculateQuote(request: QuoteRequest): Quote {
    // ... валидация, расчёты ...
    val quote = Quote(
        quoteId = UUID.randomUUID().toString(),
        expiresAt = Instant.now().plusSeconds(quoteTtlSeconds),  // TTL в доменном объекте
        // ...
    )
    quoteCacheService.save(quote)    // ← записали в Redis
    return quote
}

// validateQuote() — ЧИТАЕТ из кэша
suspend fun validateQuote(quoteId: String): QuoteValidationResult {
    val quote = quoteCacheService.get(quoteId)       // ← прочитали из Redis
        ?: return QuoteValidationResult(isValid = false, quote = null)

    if (quote.expiresAt.isBefore(Instant.now())) {   // ← двойная проверка!
        return QuoteValidationResult(isValid = false, quote = null)
    }

    return QuoteValidationResult(isValid = true, quote = quote)
}
```

### 4.4 Конфигурация

**Файл:** `services/pricing-service/src/main/kotlin/com/transferhub/pricing/config/AppConfig.kt`

```kotlin
data class RedisConfig(
    val host: String,                  // env: REDIS_HOST, default: "localhost"
    val port: Int,                     // env: REDIS_PORT, default: 6379
    val quoteTtlSeconds: Long = 30,    // env: REDIS_QUOTE_TTL_SECONDS, default: 30
)
```

---

## 5. Реализация в transfer-service

### Cache-Aside для GET /transfers/{id}

**Файл:** `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/service/TransferCacheService.kt`

```kotlin
@Service
class TransferCacheService(
    private val redisTemplate: RedisTemplate<String, String>,   // Spring Data Redis
    private val objectMapper: ObjectMapper                       // Jackson
) {
    companion object {
        private const val KEY_PREFIX = "transfer:status:"
        private val CACHE_TTL = Duration.ofSeconds(30)
    }

    // Чтение из кэша
    fun getCached(transferId: UUID): TransferResponse? {
        return try {
            val key = "$KEY_PREFIX$transferId"
            val json = redisTemplate.opsForValue().get(key)
            json?.let { objectMapper.readValue(it, TransferResponse::class.java) }
        } catch (e: Exception) {
            log.warn("Redis GET failed: {}", e.message)
            null    // кэш недоступен — ничего страшного, пойдём в БД
        }
    }

    // Запись в кэш
    fun put(transferId: UUID, response: TransferResponse) {
        try {
            val key = "$KEY_PREFIX$transferId"
            val json = objectMapper.writeValueAsString(response)
            redisTemplate.opsForValue().set(key, json, CACHE_TTL)
        } catch (e: Exception) {
            log.warn("Redis SET failed: {}", e.message)
            // не бросаем — кэш опционален
        }
    }

    // Инвалидация кэша (вызывается из Kafka consumers)
    fun evict(transferId: UUID) {
        try {
            redisTemplate.delete("$KEY_PREFIX$transferId")
        } catch (e: Exception) {
            log.warn("Redis DELETE failed: {}", e.message)
        }
    }
}
```

### Кто вызывает evict?

Kafka consumers — когда приходит событие об изменении статуса трансфера:

```kotlin
// PaymentEventConsumer.kt
fun handlePaymentEvent(event: PaymentEvent) {
    // 1. Обновить статус в PostgreSQL
    transferService.updateStatus(event.transferId, newStatus)
    // 2. Инвалидировать кэш — следующий GET пойдёт в БД за свежими данными
    transferCacheService.evict(event.transferId)
}
```

### Ключевое отличие от pricing-service

| | pricing-service | transfer-service |
|---|---|---|
| Паттерн | Write-Through (ephemeral store) | Cache-Aside |
| Основное хранилище | Redis (единственное) | PostgreSQL |
| Клиент | Lettuce async + корутины | Spring Data Redis (sync) |
| Сериализация | kotlinx.serialization | Jackson ObjectMapper |
| Инвалидация | TTL автоматически | Явный DELETE + TTL как safety net |
| При падении Redis | Котировки теряются (ок для 30с) | Просто чуть медленнее (читает из БД) |

---

## 6. Сериализация

### Почему BigDecimal → String, а не число?

Redis хранит всё как строки. Вопрос в формате JSON внутри этих строк.

```json
// ПЛОХО — потеря точности
{ "sendAmount": 500.10 }           // JavaScript/JSON число = IEEE 754 double
                                    // 500.10 может стать 500.0999999999...

// ХОРОШО — точное представление
{ "sendAmount": "500.10" }          // Строка, точность сохранена
                                    // При чтении: BigDecimal("500.10") — точно
```

**Правило для финтеха:** денежные суммы НИКОГДА не хранятся как floating-point. Только String или целые числа (центы).

### kotlinx.serialization vs Jackson

**pricing-service** использует `kotlinx.serialization`:
```kotlin
@Serializable
data class CachedQuote(val sendAmount: String, ...)
Json.encodeToString(CachedQuote.serializer(), cached)
```
- Compile-time генерация serializer'а (быстрее)
- Нативный для Kotlin
- Используется в Ktor-проектах

**transfer-service** использует `Jackson`:
```kotlin
objectMapper.writeValueAsString(response)
objectMapper.readValue(json, TransferResponse::class.java)
```
- Стандарт Spring Boot
- Runtime reflection (чуть медленнее)
- Богатая экосистема модулей

**Почему разные?** Каждый сервис использует то, что идиоматично для его фреймворка. Ktor ↔ kotlinx, Spring ↔ Jackson.

### Почему Instant → epochMs?

```kotlin
// CachedQuote
val expiresAtEpochMs: Long   // 1709913600000

// Quote (доменная модель)
val expiresAt: Instant        // 2024-03-08T16:00:00Z
```

`Instant` — Java-класс, kotlinx.serialization не знает как его сериализовать. `Long` (epoch milliseconds) — универсальный числовой формат, который:
- Легко сериализуется
- Не зависит от часовых поясов
- Конвертируется обратно: `Instant.ofEpochMilli(epochMs)`

---

## 7. Docker конфигурация

**Файл:** `infra/docker/docker-compose.yml`

```yaml
redis:
  image: redis:7-alpine
  container_name: transferhub-redis
  ports:
    - "6379:6379"
  command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru --appendonly yes
  volumes:
    - redis-data:/data
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 5s
    timeout: 3s
    retries: 5
```

### Разбор параметров

**`--maxmemory 256mb`**
Лимит памяти. Redis не сможет использовать больше 256 МБ. Без этого параметра Redis будет расти до OOM-kill.

**`--maxmemory-policy allkeys-lru`**
Что делать, когда память заполнена:
- `allkeys-lru` — удалить наименее недавно использованный ключ (из ВСЕХ ключей)
- Альтернативы: `volatile-lru` (только ключи с TTL), `noeviction` (вернуть ошибку)
- Для нас `allkeys-lru` — безопасный выбор: если память заполнена, старые квоты удалятся первыми

**`--appendonly yes`**
Включает AOF (Append-Only File) — журнал всех операций записи на диск:
- При рестарте Redis воспроизводит журнал и восстанавливает данные
- Для квот с TTL 30s это не критично (после рестарта они и так бы протухли)
- Но для transfer-service кэша — полезно при быстром рестарте

**`redis:7-alpine`**
- Redis 7 — последняя мажорная версия
- Alpine — минимальный Linux-образ (~5 МБ vs ~100 МБ для debian)

**Health check**
```yaml
test: ["CMD", "redis-cli", "ping"]     # PONG = жив
interval: 5s                            # проверять каждые 5 сек
timeout: 3s                             # таймаут на проверку
retries: 5                              # 5 неудач = unhealthy
```

Другие сервисы зависят от `condition: service_healthy` — не стартуют, пока Redis не ответит PONG.

---

## 8. Тестирование

### Testcontainers — настоящий Redis в тестах

Тесты НЕ используют моки Redis — они поднимают настоящий Redis в Docker-контейнере.

**pricing-service** (`IntegrationTestBase.kt`):
```kotlin
val redisContainer = GenericContainer("redis:7.2-alpine")
    .withExposedPorts(6379)
    .withReuse(true)   // переиспользовать контейнер между тестами (быстрее)
```

**transfer-service** (`IntegrationTestBase.kt`):
```kotlin
val redis = GenericContainer("redis:7-alpine")
    .withExposedPorts(6379)

// Динамически подставляем порт в Spring-конфигурацию
registry.add("spring.data.redis.host") { redis.host }
registry.add("spring.data.redis.port") { redis.firstMappedPort }
```

### Что тестируется

- **QuoteCacheService**: save → get возвращает тот же объект; get несуществующего → null
- **TransferCacheService**: put → getCached; evict → getCached возвращает null
- **Интеграционные**: GET /transfers/{id} → cache miss → cache hit при повторном вызове

### Unit-тесты с моками

Для тестов бизнес-логики (PricingService) QuoteCacheService мокается:

```kotlin
val mockCacheService = mockk<QuoteCacheService>(relaxed = true)
// relaxed = true → save() ничего не делает, get() возвращает null
```

---

## 9. Трейдоффы и ограничения

### Что хорошо сейчас

1. **Простота** — минимальный код, легко понять
2. **Надёжность** — fail-open (если Redis упал, приложение продолжает работать, только без кэша)
3. **Разделение ключей** — префиксы не дают коллизий между сервисами
4. **Атомарность TTL** — SETEX гарантирует, что ключ получит TTL
5. **Тестируемость** — Testcontainers с реальным Redis

### Ограничения текущей реализации

#### 1. Нет Connection Pooling (pricing-service)

```kotlin
// Сейчас: одно TCP-соединение
connection = client.connect()
```

Lettuce multiplexes команды через одно соединение — это работает до ~100K ops/sec. Для нашей нагрузки (200 RPS) более чем достаточно.

Но: если соединение оборвётся — все текущие команды упадут. Lettuce auto-reconnect поможет, но будет кратковременный провал.

#### 2. Нет Sentinel/Cluster (single point of failure)

```yaml
# Сейчас: один Redis-инстанс
redis:
  image: redis:7-alpine
```

Если Redis упал:
- pricing-service: квоты не создаются (calculateQuote запишет, но в пустоту; validateQuote всегда null)
- transfer-service: работает, но медленнее (все GET идут в PostgreSQL)

#### 3. Нет retry policy

```kotlin
// Сейчас: если SETEX упал — исключение пробрасывается наверх
redisClientFactory.asyncCommands.setex(key, ttl, json).await()
```

Нет повторных попыток при transient errors (сетевой glitch).

#### 4. Cache Stampede не обработан

Если 1000 запросов одновременно запрашивают один и тот же transferId, и кэш пустой:
- Все 1000 пойдут в PostgreSQL
- Все 1000 запишут в Redis

Для квот (pricing) это не проблема — каждая квота уникальна (UUID). Для трансферов при высокой нагрузке — может быть.

#### 5. Нет метрик Redis операций

Не отслеживается:
- Cache hit/miss ratio
- Латентность Redis-команд
- Количество ошибок Redis

#### 6. Нет пароля

```kotlin
val uri = RedisURI.builder()
    .withHost(redisConfig.host)
    .withPort(redisConfig.port)
    .build()
// Нет .withPassword()
```

Для локальной разработки ок, для прода — нет.

---

## 10. Что нужно для прода

### Чеклист: от dev к production

#### Обязательно (P0)

- [ ] **Аутентификация** — `requirepass` в Redis config, `.withPassword()` в клиенте
- [ ] **Redis Sentinel или Cluster** — high availability (failover при падении master)
  ```
  Sentinel: 1 master + 2 replicas + 3 sentinels → автоматический failover
  Cluster: данные sharded по нескольким нодам → горизонтальное масштабирование
  ```
- [ ] **TLS** — шифрование трафика между сервисами и Redis
- [ ] **Метрики** — hit/miss ratio, latency p50/p99, error count → Prometheus + Grafana
- [ ] **Alerts** — память > 80%, латентность > 10ms, недоступность > 30s

#### Желательно (P1)

- [ ] **Connection pooling** (для transfer-service; pricing Lettuce multiplexing достаточен)
- [ ] **Circuit breaker** — если Redis недоступен, не ждать таймаут на каждом запросе
  ```kotlin
  // Пример с Resilience4j
  circuitBreaker.executeSupplier {
      redisTemplate.opsForValue().get(key)
  }
  ```
- [ ] **Retry policy** — 1-2 retry с экспоненциальным backoff для transient errors
- [ ] **Key expiry monitoring** — `MONITOR` или Redis Keyspace Notifications для отладки

#### По необходимости (P2)

- [ ] **Read replicas** — если чтений значительно больше записей
- [ ] **Lua scripts** — если нужна атомарность нескольких команд (сейчас не нужно)
- [ ] **Cache stampede protection** — mutex/lock при заполнении кэша (вряд ли нужно при 200 RPS)

---

## 11. TTL и Race Conditions

### Почему TTL = 30 секунд?

Это бизнес-требование: **курс обмена фиксируется на 30 секунд**. За это время пользователь должен подтвердить перевод.

- Слишком мало (5s) → пользователь не успевает подтвердить
- Слишком много (5min) → рыночный курс может измениться, и мы зафиксировали невыгодный курс

30 секунд — стандарт для fintech rate-locking.

### Двойная проверка expiry — зачем?

```kotlin
// Redis TTL: ключ автоматически удалится через 30 секунд
redisClientFactory.asyncCommands.setex(key, 30, json)

// Но в validateQuote() ещё раз проверяем:
if (quote.expiresAt.isBefore(Instant.now())) {
    return QuoteValidationResult(isValid = false, quote = null)
}
```

**Зачем проверять дважды, если Redis сам удалит ключ?**

1. **Race condition:** Redis удаляет ключи не мгновенно. Есть два механизма:
   - **Lazy expiration** — ключ удаляется при обращении к нему (GET)
   - **Periodic expiration** — Redis каждые 100ms сканирует случайные ключи

   Между моментом истечения TTL и фактическим удалением может пройти до нескольких миллисекунд. В этот зазор GET вернёт данные, хотя логически квота уже протухла.

2. **Рассинхронизация часов:** TTL Redis отсчитывается его собственными часами. `Instant.now()` в JVM — по часам сервера. Если часы немного разъехались — TTL Redis может быть чуть позже, чем `expiresAt` в квоте.

3. **Defensive programming:** кэш — это оптимизация, а `expiresAt` — бизнес-правило. Бизнес-правило не должно зависеть от деталей реализации кэша.

### Другие race conditions

**Concurrent calculateQuote + validateQuote:**
```
T=0s:  calculateQuote() → SETEX quote:123 30 {...}
T=29s: validateQuote("123") → GET quote:123 → found, expiresAt в будущем → valid!
T=30s: Redis удаляет quote:123
T=30.001s: Transfer Service пытается списать деньги по quote:123
           → квота уже невалидна, но деньги списали?
```

**Защита:** Transfer Service должен вызвать `ValidateQuote` максимально близко к моменту списания. В нашем случае это происходит в рамках одной саги — промежуток минимальный.

---

## 12. FAQ

### Что будет, если Redis упадёт?

**pricing-service:** `calculateQuote()` бросит исключение (не сможет сохранить), клиент получит ошибку. `validateQuote()` вернёт `isValid = false` (квота не найдена).

**transfer-service:** будет работать, но чуть медленнее — все GET пойдут напрямую в PostgreSQL.

### Можно ли хранить квоты в PostgreSQL вместо Redis?

Технически — да. Но:
- Нужно самим реализовать удаление по TTL (scheduler/pg_cron)
- Дополнительная нагрузка на БД (12K writes/min + 12K deletes/min при 200 RPS)
- Медленнее (диск vs RAM)

Не имеет смысла для данных, которые живут 30 секунд.

### Почему не Redis Streams/Pub-Sub для событий?

У нас уже есть Kafka для event-driven коммуникации. Redis используется только как хранилище, не как message broker. Kafka даёт гарантии доставки (at-least-once), retention, consumer groups — Redis Streams слабее в этом.

### Как мигрировать на Redis Cluster?

Lettuce из коробки поддерживает Cluster:
```kotlin
// Было:
val client = RedisClient.create(uri)

// Стало:
val client = RedisClusterClient.create(listOf(uri1, uri2, uri3))
```

Spring Data Redis тоже — достаточно поменять конфигурацию:
```yaml
spring.data.redis.cluster.nodes: node1:6379,node2:6379,node3:6379
```

### Будет ли меняться текущая реализация?

Для текущей нагрузки (200 RPS, dev/staging) — реализация достаточна. При выходе в прод потребуется:
1. Redis Sentinel (минимум) для высокой доступности
2. Аутентификация и TLS
3. Мониторинг и метрики

Код сервисов при этом почти не изменится — изменится только конфигурация подключения.
