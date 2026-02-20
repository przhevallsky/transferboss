package com.swiftpay.transfer.integration

import com.swiftpay.transfer.IntegrationTestBase
import com.swiftpay.transfer.domain.model.Recipient
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.RecipientRepository
import com.swiftpay.transfer.repository.TransferRepository
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import java.math.BigDecimal
import java.util.UUID

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TransferApiIntegrationTest : IntegrationTestBase() {

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
        if (recipientRepository.findRecipientById(recipientId) == null) {
            recipientRepository.save(
                Recipient(
                    id = recipientId,
                    senderId = senderId,
                    firstName = "Maria",
                    lastName = "Santos",
                    country = "PH",
                    deliveryDetails = """{"bank_name": "BDO", "account_number": "1234567890"}"""
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
            Map::class.java
        )

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.headers.location)

        val responseBody = response.body!!
        assertEquals("CREATED", responseBody["status"])
        assertEquals("200.00", responseBody["send_amount"])
        assertEquals("USD", responseBody["send_currency"])
        assertNotNull(responseBody["id"])

        // Verify in PostgreSQL
        val transferId = UUID.fromString(responseBody["id"] as String)
        val savedTransfer = transferRepository.findTransferById(transferId)
        assertNotNull(savedTransfer)
        assertEquals(BigDecimal("200.00").setScale(2), savedTransfer!!.sendAmount.setScale(2))

        // Verify outbox event
        val outboxEvents = outboxEventRepository.findByEntityIdOrderByCreatedAtAsc(transferId)
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

        val response1 = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
        )
        assertEquals(HttpStatus.CREATED, response1.statusCode)
        val transferId = response1.body!!["id"]

        val response2 = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
        )
        assertEquals(HttpStatus.OK, response2.statusCode)
        assertEquals(transferId, response2.body!!["id"])
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

        val response = restTemplate.exchange(
            "/api/v1/transfers", HttpMethod.POST, HttpEntity("{}", headers), Map::class.java
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        val responseBody = response.body!!
        assertEquals(400, responseBody["status"])
        assertNotNull(responseBody["violations"])

        @Suppress("UNCHECKED_CAST")
        val violations = responseBody["violations"] as List<Map<String, String>>
        assertTrue(violations.isNotEmpty())
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
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
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
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
    }

    // ================================================================
    // POST — Missing Header
    // ================================================================

    @Test
    @Order(6)
    fun `POST without X-Idempotency-Key should return 400`() {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
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
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    // ================================================================
    // GET /api/v1/transfers/{id}
    // ================================================================

    @Test
    @Order(7)
    fun `GET transfer by ID should return 200`() {
        val transferId = createTestTransfer()

        val response = restTemplate.getForEntity(
            "/api/v1/transfers/$transferId", Map::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(transferId.toString(), response.body!!["id"])
    }

    @Test
    @Order(8)
    fun `GET non-existent transfer should return 404`() {
        val response = restTemplate.getForEntity(
            "/api/v1/transfers/${UUID.randomUUID()}", Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    @Order(9)
    fun `GET transfer second time should return same data from cache`() {
        val transferId = createTestTransfer()

        val response1 = restTemplate.getForEntity("/api/v1/transfers/$transferId", Map::class.java)
        val response2 = restTemplate.getForEntity("/api/v1/transfers/$transferId", Map::class.java)

        assertEquals(HttpStatus.OK, response1.statusCode)
        assertEquals(HttpStatus.OK, response2.statusCode)
        assertEquals(response1.body!!["id"], response2.body!!["id"])
    }

    // ================================================================
    // GET /api/v1/transfers — Pagination
    // ================================================================

    @Test
    @Order(10)
    fun `GET transfers list should return paginated results`() {
        repeat(3) { createTestTransfer() }

        val headers = HttpHeaders().apply {
            set("X-Sender-Id", senderId.toString())
        }

        val response = restTemplate.exchange(
            "/api/v1/transfers?limit=2", HttpMethod.GET, HttpEntity<Void>(headers), Map::class.java
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
    fun `GET transfers with cursor should return next page without overlap`() {
        val headers = HttpHeaders().apply {
            set("X-Sender-Id", senderId.toString())
        }

        val page1 = restTemplate.exchange(
            "/api/v1/transfers?limit=2", HttpMethod.GET, HttpEntity<Void>(headers), Map::class.java
        )

        @Suppress("UNCHECKED_CAST")
        val pagination1 = page1.body!!["pagination"] as Map<String, Any?>
        val nextCursor = pagination1["next_cursor"] as String

        val page2 = restTemplate.exchange(
            "/api/v1/transfers?limit=2&cursor=$nextCursor",
            HttpMethod.GET, HttpEntity<Void>(headers), Map::class.java
        )

        assertEquals(HttpStatus.OK, page2.statusCode)

        @Suppress("UNCHECKED_CAST")
        val items1 = page1.body!!["items"] as List<Map<String, Any>>
        @Suppress("UNCHECKED_CAST")
        val items2 = page2.body!!["items"] as List<Map<String, Any>>

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
            "/api/v1/transfers", HttpMethod.POST, HttpEntity(body, headers), Map::class.java
        )

        return UUID.fromString(response.body!!["id"] as String)
    }
}
