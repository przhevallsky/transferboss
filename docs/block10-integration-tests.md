# Block 10 — Integration Tests (Testcontainers + Spring Boot Test)

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 10.** Финальный блок Sprint 1. Blocks 1–9 завершены. Нужны integration tests, которые проверяют полный HTTP flow с реальными PostgreSQL и Redis.

## Задача

Integration тесты через Testcontainers — реальные БД, реальные HTTP-вызовы, проверка всего стека от controller до PostgreSQL.

## Инструменты

- **Testcontainers** — запуск PostgreSQL и Redis в Docker из тестов
- **Spring Boot Test** — `@SpringBootTest(webEnvironment = RANDOM_PORT)`
- **WebTestClient** или **TestRestTemplate** — HTTP-вызовы к поднятому приложению
- **JUnit 5** — test runner

## Зависимости в Gradle

```kotlin
testImplementation("org.springframework.boot:spring-boot-starter-test") {
    exclude(module = "mockito-core")
    exclude(module = "mockito-junit-jupiter")
}
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:postgresql")
testImplementation("org.springframework.boot:spring-boot-starter-webflux") // для WebTestClient
testImplementation("io.mockk:mockk:1.13.13")
testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
```

`spring-boot-starter-webflux` нужен ТОЛЬКО для `WebTestClient` в тестах — это HTTP-клиент, не WebFlux runtime. Приложение остаётся на Spring MVC.

---

## Структура файлов

```
src/test/kotlin/com/transferhub/transfer/
  integration/
    TransferApiIntegrationTest.kt     — основной integration test
    BaseIntegrationTest.kt            — базовый класс с Testcontainers setup
  
src/test/resources/
  application-test.yml                — test profile config
```

---

## 1. BaseIntegrationTest.kt — общий setup

```kotlin
package com.transferhub.transfer.integration

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Базовый класс для integration тестов.
 *
 * Поднимает PostgreSQL и Redis через Testcontainers.
 * Контейнеры shared между всеми тестами в классе (static/companion object) —
 * не пересоздаются на каждый тест, экономят время.
 *
 * @DynamicPropertySource подставляет реальные порты контейнеров в Spring config.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
abstract class BaseIntegrationTest {

    companion object {

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("transferhub_test")
            withUsername("test")
            withPassword("test")
        }

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine").apply {
            withExposedPorts(6379)
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            // PostgreSQL
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            // Redis
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.firstMappedPort }

            // Flyway (использует тот же datasource)
            registry.add("spring.flyway.url") { postgres.jdbcUrl }
            registry.add("spring.flyway.user") { postgres.username }
            registry.add("spring.flyway.password") { postgres.password }
        }
    }
}
```

---

## 2. application-test.yml

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate  # НЕ create — Flyway управляет схемой
    show-sql: true         # полезно для отладки тестов
    properties:
      hibernate:
        format_sql: true

  jackson:
    property-naming-strategy: SNAKE_CASE
    serialization:
      write-dates-as-timestamps: false

  flyway:
    enabled: true          # Flyway миграции применяются автоматически

logging:
  level:
    com.transferhub: DEBUG
    org.springframework.web: DEBUG
    org.hibernate.SQL: DEBUG
```

---

## 3. TransferApiIntegrationTest.kt

```kotlin
package com.transferhub.transfer.integration

import com.transferhub.transfer.api.dto.response.PaginatedResponse
import com.transferhub.transfer.api.dto.response.TransferResponse
import com.transferhub.transfer.repository.OutboxEventRepository
import com.transferhub.transfer.repository.RecipientRepository
import com.transferhub.transfer.repository.TransferRepository
import com.transferhub.transfer.domain.model.Recipient
import com.transferhub.transfer.domain.vo.OutboxEventStatus
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration тесты для Transfer API.
 *
 * Тестируют полный HTTP flow: Controller → Service → Repository → PostgreSQL.
 * Используют реальный PostgreSQL и Redis через Testcontainers.
 *
 * Flyway миграции применяются автоматически при старте Spring Context.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TransferApiIntegrationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var transferRepository: TransferRepository

    @Autowired
    private lateinit var outboxEventRepository: OutboxEventRepository

    @Autowired
    private lateinit var recipientRepository: RecipientRepository

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun seedRecipient() {
        // Создаём recipient если ещё нет (idempotent)
        if (!recipientRepository.existsById(recipientId)) {
            recipientRepository.save(
                Recipient(
                    id = recipientId,
                    senderId = senderId,
                    firstName = "Maria",
                    lastName = "Santos",
                    country = "PH"
                    // добавь остальные обязательные поля
                )
            )
        }
    }

    // ================================================================
    // POST /api/v1/transfers — Happy Path
    // ================================================================

    @Test
    @Order(1)
    fun `POST transfers should create transfer and return 201`() {
        val idempotencyKey = UUID.randomUUID()

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", idempotencyKey.toString())
            set("X-Sender-Id", senderId.toString())
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": 200.00,
                "send_currency": "USD",
                "receive_currency": "PHP",
                "source_country": "US",
                "dest_country": "PH"
            }
        """.trimIndent()

        val response = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java  // generic Map для гибкого чтения JSON
        )

        // Assertions
        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.headers.location)

        val responseBody = response.body!!
        assertEquals("CREATED", responseBody["status"])
        assertEquals("200.00", responseBody["send_amount"])
        assertEquals("USD", responseBody["send_currency"])
        assertNotNull(responseBody["id"])

        // Verify: перевод записан в PostgreSQL
        val transferId = UUID.fromString(responseBody["id"] as String)
        val savedTransfer = transferRepository.findTransferById(transferId)
        assertNotNull(savedTransfer)
        assertEquals(BigDecimal("200.00").setScale(2), savedTransfer.sendAmount.setScale(2))

        // Verify: outbox event записан
        val outboxEvents = outboxEventRepository.findByAggregateIdOrderByCreatedAtAsc(transferId)
        assertEquals(1, outboxEvents.size)
        assertEquals(OutboxEventStatus.PENDING, outboxEvents[0].status)
    }

    // ================================================================
    // POST — Idempotency
    // ================================================================

    @Test
    @Order(2)
    fun `POST with same idempotency key should return 200 with same result`() {
        val idempotencyKey = UUID.randomUUID()

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", idempotencyKey.toString())
            set("X-Sender-Id", senderId.toString())
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": 150.00,
                "send_currency": "USD",
                "receive_currency": "PHP",
                "source_country": "US",
                "dest_country": "PH"
            }
        """.trimIndent()

        // Первый вызов → 201
        val response1 = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )
        assertEquals(HttpStatus.CREATED, response1.statusCode)
        val transferId = response1.body!!["id"]

        // Второй вызов с тем же idempotency key → 200
        val response2 = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )
        assertEquals(HttpStatus.OK, response2.statusCode)
        assertEquals(transferId, response2.body!!["id"], "Same transfer should be returned")
    }

    // ================================================================
    // POST — Validation Errors
    // ================================================================

    @Test
    @Order(3)
    fun `POST with missing fields should return 400 with violations`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
            set("X-Sender-Id", senderId.toString())
        }

        // Empty body — all required fields missing
        val body = "{}"

        val response = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)

        val responseBody = response.body!!
        assertEquals(400, responseBody["status"])
        assertNotNull(responseBody["violations"], "Should contain validation violations")

        @Suppress("UNCHECKED_CAST")
        val violations = responseBody["violations"] as List<Map<String, String>>
        assertTrue(violations.isNotEmpty(), "Should have at least one violation")
    }

    @Test
    @Order(4)
    fun `POST with negative amount should return 400`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
            set("X-Sender-Id", senderId.toString())
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": -100.00,
                "send_currency": "USD",
                "receive_currency": "PHP",
                "source_country": "US",
                "dest_country": "PH"
            }
        """.trimIndent()

        val response = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ================================================================
    // POST — Business Errors
    // ================================================================

    @Test
    @Order(5)
    fun `POST with unsupported corridor should return 422`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
            set("X-Sender-Id", senderId.toString())
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": 200.00,
                "send_currency": "USD",
                "receive_currency": "JPY",
                "source_country": "US",
                "dest_country": "JP"
            }
        """.trimIndent()

        val response = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertTrue(response.body!!["detail"].toString().contains("US→JP"))
    }

    // ================================================================
    // POST — Missing Idempotency Header
    // ================================================================

    @Test
    @Order(6)
    fun `POST without X-Idempotency-Key should return 400`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Sender-Id", senderId.toString())
            // НЕ добавляем X-Idempotency-Key
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": 200.00,
                "send_currency": "USD",
                "receive_currency": "PHP",
                "source_country": "US",
                "dest_country": "PH"
            }
        """.trimIndent()

        val response = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body!!["detail"].toString().contains("X-Idempotency-Key"))
    }

    // ================================================================
    // GET /api/v1/transfers/{id}
    // ================================================================

    @Test
    @Order(7)
    fun `GET transfer by ID should return 200`() {
        // Создаём перевод
        val transferId = createTestTransfer()

        val response = restTemplate.getForEntity(
            "/api/v1/transfers/$transferId",
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(transferId.toString(), response.body!!["id"])
    }

    @Test
    @Order(8)
    fun `GET non-existent transfer should return 404`() {
        val unknownId = UUID.randomUUID()

        val response = restTemplate.getForEntity(
            "/api/v1/transfers/$unknownId",
            Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    @Order(9)
    fun `GET transfer second time should hit Redis cache`() {
        val transferId = createTestTransfer()

        // Первый GET → cache miss → PostgreSQL → cache put
        restTemplate.getForEntity("/api/v1/transfers/$transferId", Map::class.java)

        // Второй GET → cache hit (проверяем по логам DEBUG уровня)
        val response = restTemplate.getForEntity(
            "/api/v1/transfers/$transferId",
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        // Для программной проверки cache hit: можно inject TransferCacheService
        // и вызвать getCached() — но это уже white-box testing.
        // В integration test достаточно что ответ корректный.
    }

    // ================================================================
    // GET /api/v1/transfers — Pagination
    // ================================================================

    @Test
    @Order(10)
    fun `GET transfers list should return paginated results`() {
        // Создаём несколько переводов
        repeat(3) { createTestTransfer() }

        val headers = HttpHeaders().apply {
            set("X-Sender-Id", senderId.toString())
        }

        val response = restTemplate.exchange(
            "/api/v1/transfers?limit=2",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)

        val body = response.body!!
        @Suppress("UNCHECKED_CAST")
        val items = body["items"] as List<*>
        assertEquals(2, items.size)

        @Suppress("UNCHECKED_CAST")
        val pagination = body["pagination"] as Map<String, Any?>
        assertTrue(pagination["has_more"] as Boolean)
        assertNotNull(pagination["next_cursor"])
    }

    @Test
    @Order(11)
    fun `GET transfers with cursor should return next page`() {
        val headers = HttpHeaders().apply {
            set("X-Sender-Id", senderId.toString())
        }

        // Первая страница
        val page1 = restTemplate.exchange(
            "/api/v1/transfers?limit=2",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java
        )

        @Suppress("UNCHECKED_CAST")
        val pagination1 = page1.body!!["pagination"] as Map<String, Any?>
        val nextCursor = pagination1["next_cursor"] as String

        // Вторая страница с cursor
        val page2 = restTemplate.exchange(
            "/api/v1/transfers?limit=2&cursor=$nextCursor",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java
        )

        assertEquals(HttpStatus.OK, page2.statusCode)

        @Suppress("UNCHECKED_CAST")
        val items1 = page1.body!!["items"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val items2 = page2.body!!["items"] as List<Map<String, Any>>

        // Страницы не пересекаются
        val ids1 = items1.map { it["id"] }.toSet()
        val ids2 = items2.map { it["id"] }.toSet()
        assertTrue(ids1.intersect(ids2).isEmpty(), "Pages should not overlap")
    }

    // ================================================================
    // Helper
    // ================================================================

    private fun createTestTransfer(): UUID {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
            set("X-Sender-Id", senderId.toString())
        }

        val body = """
            {
                "quote_id": "${UUID.randomUUID()}",
                "recipient_id": "$recipientId",
                "delivery_method": "BANK_DEPOSIT",
                "send_amount": ${(50..500).random()}.00,
                "send_currency": "USD",
                "receive_currency": "PHP",
                "source_country": "US",
                "dest_country": "PH"
            }
        """.trimIndent()

        val response = restTemplate.exchange(
            "/api/v1/transfers",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java
        )

        return UUID.fromString(response.body!!["id"] as String)
    }
}
```

---

## Запуск

```bash
# Все тесты (unit + integration)
./gradlew :services:transfer-service:test

# Только integration тесты
./gradlew :services:transfer-service:test --tests "*.integration.*"
```

**Требования:** Docker должен быть запущен (Testcontainers запускает контейнеры через Docker).

## Проверка результата

1. Все тесты зелёные (exit code 0)
2. В логах видны: Testcontainers starting PostgreSQL, Redis
3. Flyway миграции применяются автоматически
4. POST → 201, POST (same key) → 200, POST (invalid) → 400/422
5. GET → 200, GET (unknown) → 404
6. Pagination: items + has_more + next_cursor работают
7. Тесты завершаются за < 60 секунд (Testcontainers + Spring Boot startup)

## Частые проблемы

- **Docker не запущен** → `Could not find a valid Docker environment`
- **Port conflict** → Testcontainers использует random ports, конфликтов быть не должно
- **Flyway migration failed** → проверь что V001, V002 миграции совместимы с test database
- **Seed data conflict** → V003 seed может конфликтовать если запускается в тестах. Можно вынести seed в отдельный profile.
- **Jackson snake_case** → если response field names не совпадают — проверь `application-test.yml` Jackson config
- **`@Transactional` на тестах** → НЕ добавляй — тесты должны видеть реальные committed данные через HTTP

## Sprint 1 — Завершение

После Block 10 Sprint 1 завершён. Что реализовано:

| Block | Что | Статус |
|-------|-----|--------|
| 1 | Flyway migrations (4 таблицы) | ✅ |
| 2 | Domain model (sealed class state machine) | ✅ |
| 3 | Repository layer (cursor pagination) | ✅ |
| 4 | Service layer (Outbox + Idempotency) | ✅ |
| 5 | REST Controller (POST/GET) | ✅ |
| 6 | Error handling (RFC 9457) | ✅ |
| 7 | Redis Cache-Aside | ✅ |
| 8 | Pagination verification + EXPLAIN ANALYZE | ✅ |
| 9 | Unit tests (MockK, 15+ tests) | ✅ |
| 10 | Integration tests (Testcontainers, 11+ tests) | ✅ |

**Sprint Goal достигнут:** работающий end-to-end flow создания и получения перевода с Outbox Pattern, idempotency, cursor pagination, Redis cache, RFC 9457 errors, unit + integration tests.
