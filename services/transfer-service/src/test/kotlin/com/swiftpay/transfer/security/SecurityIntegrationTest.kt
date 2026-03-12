package com.swiftpay.transfer.security

import com.ninjasquad.springmockk.MockkBean
import com.swiftpay.transfer.IntegrationTestBase
import com.swiftpay.transfer.client.IdentityClient
import com.swiftpay.transfer.client.KycStatus
import com.swiftpay.transfer.client.PricingClient
import com.swiftpay.transfer.client.QuoteData
import com.swiftpay.transfer.domain.model.Recipient
import com.swiftpay.transfer.repository.RecipientRepository
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.*
import java.math.BigDecimal
import java.util.UUID

class SecurityIntegrationTest : IntegrationTestBase() {

    @MockkBean
    private lateinit var pricingClient: PricingClient

    @MockkBean
    private lateinit var identityClient: IdentityClient

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var recipientRepository: RecipientRepository

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        every { identityClient.checkKyc(any()) } returns KycStatus(
            userId = senderId.toString(), status = "APPROVED", kycLevel = "FULL"
        )
        every { pricingClient.validateQuote(any()) } returns QuoteData(
            quoteId = "test-quote", sendAmount = BigDecimal("200.00"),
            receiveAmount = BigDecimal("11240.00"), exchangeRate = BigDecimal("56.20"),
            feeAmount = BigDecimal("5.99"), feeCurrency = "USD",
            sendCurrency = "USD", receiveCurrency = "PHP"
        )
        if (recipientRepository.findRecipientById(recipientId) == null) {
            recipientRepository.save(
                Recipient(
                    id = recipientId, senderId = senderId, firstName = "Maria",
                    lastName = "Santos", country = "PH",
                    deliveryDetails = """{"bank_name": "BDO", "account_number": "1234567890"}"""
                )
            )
        }
    }

    // ================================================================
    // 1. Unauthenticated → 401
    // ================================================================

    @Test
    fun `should return 401 when no token provided`() {
        val response = restTemplate.getForEntity(
            "/api/v1/transfers/${UUID.randomUUID()}", Map::class.java
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    // ================================================================
    // 2. Expired token → 401
    // ================================================================

    @Test
    fun `should return 401 when token is expired`() {
        val headers = HttpHeaders().apply {
            setBearerAuth(TestJwtHelper.expiredToken())
        }
        val response = restTemplate.exchange(
            "/api/v1/transfers/${UUID.randomUUID()}", HttpMethod.GET,
            HttpEntity<Void>(headers), Map::class.java
        )
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    // ================================================================
    // 3. SENDER sees own transfers
    // ================================================================

    @Test
    fun `SENDER should be able to create and list own transfers`() {
        val token = TestJwtHelper.senderToken(senderId.toString())
        val transferId = createTransfer(token)
        assertNotNull(transferId)

        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val response = restTemplate.exchange(
            "/api/v1/transfers?limit=10", HttpMethod.GET,
            HttpEntity<Void>(headers), Map::class.java
        )
        assertEquals(HttpStatus.OK, response.statusCode)

        @Suppress("UNCHECKED_CAST")
        val items = response.body!!["items"] as List<Map<String, Any>>
        assertTrue(items.isNotEmpty())
    }

    // ================================================================
    // 4. OPERATOR can list transfers
    // ================================================================

    @Test
    fun `OPERATOR should be able to list transfers`() {
        val token = TestJwtHelper.operatorToken()
        val headers = HttpHeaders().apply { setBearerAuth(token) }
        val response = restTemplate.exchange(
            "/api/v1/transfers?limit=10", HttpMethod.GET,
            HttpEntity<Void>(headers), Map::class.java
        )
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    // ================================================================
    // 5. OPERATOR cannot create transfers (SENDER only)
    // ================================================================

    @Test
    fun `OPERATOR should get 403 when trying to create transfer`() {
        val token = TestJwtHelper.operatorToken()
        val headers = HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
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
            "/api/v1/transfers", HttpMethod.POST,
            HttpEntity(body, headers), Map::class.java
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    // ================================================================
    // 6. Health/metrics endpoints are public
    // ================================================================

    @Test
    fun `health endpoint should be public`() {
        val response = restTemplate.getForEntity("/actuator/health", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `actuator info endpoint should be public`() {
        val response = restTemplate.getForEntity("/actuator/info", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    // ================================================================
    // Helper
    // ================================================================

    private fun createTransfer(token: String): UUID {
        val headers = HttpHeaders().apply {
            setBearerAuth(token)
            contentType = MediaType.APPLICATION_JSON
            set("X-Idempotency-Key", UUID.randomUUID().toString())
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
            "/api/v1/transfers", HttpMethod.POST,
            HttpEntity(body, headers), Map::class.java
        )

        return UUID.fromString(response.body!!["id"] as String)
    }
}
