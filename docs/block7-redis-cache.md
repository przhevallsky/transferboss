# Block 7 — Redis Cache (Cache-Aside) для GET /api/v1/transfers/{id}

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 7.** Blocks 1–6 завершены: работающий POST/GET API с Flyway, domain model, repositories, service layer, controller, error handling.

GET /api/v1/transfers/{id} уже работает (Block 5), но каждый раз идёт в PostgreSQL. Нужно добавить Redis как acceleration layer.

## Задача

Реализовать Cache-Aside pattern для получения перевода по ID через Redis. Ручная реализация через RedisTemplate (не `@Cacheable`) — для полного контроля и явной логики, которую можно объяснить на собеседовании.

## Структура файлов

Создать / изменить в `services/transfer-service/src/main/kotlin/com/transferhub/transfer/`:

```
config/
  RedisConfig.kt               — СОЗДАТЬ: конфигурация RedisTemplate с JSON serialization
service/
  TransferCacheService.kt      — СОЗДАТЬ: Cache-Aside логика
  TransferService.kt           — ИЗМЕНИТЬ: вызывать cache в getTransfer()
```

---

## Что создать

### 1. RedisConfig.kt

```kotlin
package com.transferhub.transfer.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
class RedisConfig {

    /**
     * RedisTemplate с JSON-сериализацией для values.
     * Keys — String (human-readable, удобно для debugging через redis-cli).
     * Values — JSON (через Jackson, поддерживает Kotlin data classes и Instant).
     */
    @Bean
    fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> {
        val objectMapper = ObjectMapper().apply {
            registerKotlinModule()
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // ISO 8601
            // Включаем type information для десериализации
            activateDefaultTyping(
                polymorphicTypeValidator,
                ObjectMapper.DefaultTyping.NON_FINAL
            )
        }

        val template = RedisTemplate<String, Any>()
        template.connectionFactory = connectionFactory
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = GenericJackson2JsonRedisSerializer(objectMapper)
        template.hashKeySerializer = StringRedisSerializer()
        template.hashValueSerializer = GenericJackson2JsonRedisSerializer(objectMapper)
        template.afterPropertiesSet()
        return template
    }
}
```

**Примечание:** `activateDefaultTyping` добавляет type info в JSON, чтобы Jackson мог десериализовать обратно в нужный тип. Если это вызывает проблемы — можно использовать простую String-сериализацию (serialize/deserialize вручную через ObjectMapper).

**Альтернативный подход (проще и надёжнее):**

Вместо `GenericJackson2JsonRedisSerializer` можно использовать `StringRedisSerializer` для values и сериализовать/десериализовать JSON вручную в TransferCacheService. Это избегает проблем с type info:

```kotlin
@Bean
fun redisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, String> {
    val template = RedisTemplate<String, String>()
    template.connectionFactory = connectionFactory
    template.keySerializer = StringRedisSerializer()
    template.valueSerializer = StringRedisSerializer()
    template.afterPropertiesSet()
    return template
}
```

**Рекомендация: используй String-вариант** — проще, надёжнее, меньше магии. Ниже TransferCacheService написан под оба варианта.

---

### 2. TransferCacheService.kt

```kotlin
package com.transferhub.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.transferhub.transfer.api.dto.response.TransferResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Cache-Aside pattern для статуса перевода.
 *
 * Стратегия:
 * 1. GET → check Redis → hit? return cached
 * 2. miss? → query PostgreSQL → write to Redis (TTL 30s) → return
 * 3. UPDATE status → delete from Redis (invalidation)
 *
 * Redis key pattern: "transfer:status:{transfer_id}"
 * TTL: 30 секунд — подходит для eventually consistent статуса.
 *
 * Redis здесь — acceleration layer, NOT source of truth.
 * Если Redis недоступен — fallback на PostgreSQL. Система работает корректно,
 * просто медленнее (30ms вместо 5ms).
 */
@Service
class TransferCacheService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(TransferCacheService::class.java)

    companion object {
        private const val KEY_PREFIX = "transfer:status:"
        private val CACHE_TTL = Duration.ofSeconds(30)
    }

    /**
     * Получить cached response. Null если нет в кэше или Redis недоступен.
     */
    fun getCached(transferId: UUID): TransferResponse? {
        return try {
            val key = "$KEY_PREFIX$transferId"
            val json = redisTemplate.opsForValue().get(key)
            if (json != null) {
                log.debug("Cache HIT: transferId={}", transferId)
                objectMapper.readValue(json, TransferResponse::class.java)
            } else {
                log.debug("Cache MISS: transferId={}", transferId)
                null
            }
        } catch (e: Exception) {
            // Redis недоступен — не ломаем flow, fallback на PostgreSQL
            log.warn("Redis GET failed for transferId={}: {}", transferId, e.message)
            null
        }
    }

    /**
     * Записать в кэш с TTL.
     */
    fun put(transferId: UUID, response: TransferResponse) {
        try {
            val key = "$KEY_PREFIX$transferId"
            val json = objectMapper.writeValueAsString(response)
            redisTemplate.opsForValue().set(key, json, CACHE_TTL)
            log.debug("Cache PUT: transferId={}, ttl={}s", transferId, CACHE_TTL.seconds)
        } catch (e: Exception) {
            // Ошибка записи в кэш — не критично, следующий GET пойдёт в PostgreSQL
            log.warn("Redis SET failed for transferId={}: {}", transferId, e.message)
        }
    }

    /**
     * Инвалидация: удалить из кэша при изменении статуса.
     * Вызывается в Sprint 2 при обновлении статуса через Kafka consumer.
     */
    fun evict(transferId: UUID) {
        try {
            val key = "$KEY_PREFIX$transferId"
            val deleted = redisTemplate.delete(key)
            log.debug("Cache EVICT: transferId={}, deleted={}", transferId, deleted)
        } catch (e: Exception) {
            log.warn("Redis DELETE failed for transferId={}: {}", transferId, e.message)
        }
    }
}
```

---

### 3. Изменить TransferService.getTransfer()

В `TransferService.kt` добавить `TransferCacheService` как зависимость и изменить метод `getTransfer`:

```kotlin
// Добавить в конструктор TransferService:
private val transferCacheService: TransferCacheService

// Добавить import для маппера:
// import com.transferhub.transfer.api.mapper.TransferMapper.toResponse

/**
 * Получить перевод по ID с Cache-Aside.
 *
 * Flow:
 * 1. Check Redis cache
 * 2. Cache hit → return cached TransferResponse
 * 3. Cache miss → query PostgreSQL → map to response → put in cache → return
 *
 * Если Redis недоступен — transparent fallback на PostgreSQL.
 */
@Transactional(readOnly = true)
fun getTransfer(transferId: UUID): Transfer {
    // Cache-Aside: check Redis first
    // Примечание: кэшируем на уровне TransferResponse, не Transfer entity
    // (entity имеет JPA proxy, lazy fields — не подходит для сериализации в Redis)
    val cached = transferCacheService.getCached(transferId)
    if (cached != null) {
        // Cache hit — но нам нужно вернуть Transfer entity для контроллера.
        // Вариант 1: кэширование в контроллере (лучше, но требует рефакторинга)
        // Вариант 2: всё равно загружаем entity (кэш ускорит только response)
        // Для Sprint 1 — оставляем простой подход: кэшируем в контроллере.
        // Здесь — просто загрузка из PostgreSQL.
    }

    return transferRepository.findTransferById(transferId)
        ?: throw TransferNotFoundException(transferId)
}
```

**Лучший подход: кэширование на уровне контроллера.**

Вместо усложнения TransferService, добавь cache в контроллер — это проще и чище:

### 4. Изменить TransferController.getTransfer()

```kotlin
/**
 * GET /api/v1/transfers/{id} — получить перевод по ID.
 * Cache-Aside: Redis → PostgreSQL → Redis.
 */
@GetMapping("/{id}")
fun getTransfer(@PathVariable id: UUID): ResponseEntity<TransferResponse> {

    // 1. Check cache
    val cached = transferCacheService.getCached(id)
    if (cached != null) {
        return ResponseEntity.ok(cached)
    }

    // 2. Cache miss → load from DB
    val transfer = transferService.getTransfer(id)
    val recipient = recipientRepository.findRecipientById(transfer.recipientId)
    val response = transfer.toResponse(recipient)

    // 3. Put in cache (async-safe: если Redis упал — не ломаем ответ)
    transferCacheService.put(id, response)

    return ResponseEntity.ok(response)
}
```

Добавь `TransferCacheService` в конструктор `TransferController`:

```kotlin
class TransferController(
    private val transferService: TransferService,
    private val recipientRepository: RecipientRepository,
    private val transferCacheService: TransferCacheService  // ← добавить
)
```

---

## Настройка Redis connection

В `application.yml` (если нет):

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms        # connection timeout
      connect-timeout: 2000ms
```

Docker Compose уже должен поднимать Redis на порту 6379.

## Зависимости в Gradle

Убедись что есть:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

---

## Проверка результата

1. Компилируется и запускается.

2. Первый GET:
```bash
curl http://localhost:8080/api/v1/transfers/{existing-id}
# В логах: "Cache MISS: transferId=...", затем "Cache PUT: transferId=..."
```

3. Повторный GET (в течение 30 секунд):
```bash
curl http://localhost:8080/api/v1/transfers/{existing-id}
# В логах: "Cache HIT: transferId=..."
```

4. Через 30+ секунд — снова cache miss (TTL expired).

5. Redis-cli проверка:
```bash
docker exec -it redis redis-cli
GET "transfer:status:{transfer-id}"
TTL "transfer:status:{transfer-id}"
```

6. Если Redis остановить (`docker stop redis`), GET всё равно работает (fallback на PostgreSQL, warning в логах).

## Чего НЕ делать

- Не используй `@Cacheable` — ручной Cache-Aside даёт больше контроля и лучше для собеседования
- Не кэшируй JPA entity напрямую — proxy объекты, lazy loading ломают сериализацию
- Не пиши тесты — Block 10
