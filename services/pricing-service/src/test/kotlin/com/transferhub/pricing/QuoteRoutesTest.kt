package com.transferhub.pricing

import com.transferhub.pricing.config.RedisClientFactory
import com.transferhub.pricing.plugins.configureErrorHandling
import com.transferhub.pricing.plugins.configureRouting
import com.transferhub.pricing.plugins.configureSerialization
import com.transferhub.pricing.service.DEFAULT_CORRIDORS
import com.transferhub.pricing.service.PricingService
import com.transferhub.pricing.service.QuoteCacheService
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class QuoteRoutesTest : IntegrationTestBase() {

    private lateinit var redisClientFactory: RedisClientFactory
    private lateinit var pricingService: PricingService

    @BeforeTest
    fun setup() {
        val config = testConfig()
        redisClientFactory = RedisClientFactory(config.redis)
        redisClientFactory.connect()
        val quoteCacheService = QuoteCacheService(redisClientFactory, config.redis.quoteTtlSeconds)
        pricingService = PricingService(DEFAULT_CORRIDORS, quoteCacheService, config.redis.quoteTtlSeconds)
    }

    @AfterTest
    fun teardown() {
        redisClientFactory.close()
    }

    private fun quoteTestApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureErrorHandling()
            configureRouting(pricingService)
        }
        block()
    }

    // ── GET /api/v1/quotes: happy path ─────────────────────────────────────────

    @Test
    fun `GET quotes returns 200 with valid quote for US_PH corridor`() = quoteTestApp {
        val response = client.get("/api/v1/quotes") {
            parameter("source_country", "US")
            parameter("destination_country", "PH")
            parameter("send_currency", "USD")
            parameter("receive_currency", "PHP")
            parameter("send_amount", "100.00")
            parameter("delivery_method", "BANK_TRANSFER")
            parameter("sender_id", "user-123")
        }

        response.status shouldBe HttpStatusCode.OK
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        json["sendCurrency"]?.jsonPrimitive?.content shouldBe "USD"
        json["receiveCurrency"]?.jsonPrimitive?.content shouldBe "PHP"
        json["receiveAmount"]?.jsonPrimitive?.content shouldBe "5283.36"
        json["feeAmount"]?.jsonPrimitive?.content shouldBe "5.99"
        json["deliveryMethod"]?.jsonPrimitive?.content shouldBe "BANK_TRANSFER"
    }

    // ── GET /api/v1/quotes: validation errors ──────────────────────────────────

    @Test
    fun `GET quotes returns 400 when required parameters are missing`() = quoteTestApp {
        val response = client.get("/api/v1/quotes") {
            parameter("source_country", "US")
        }

        response.status shouldBe HttpStatusCode.BadRequest
        val body = response.bodyAsText()
        body shouldContain "Missing required parameters"
    }

    @Test
    fun `GET quotes returns 422 for unsupported corridor`() = quoteTestApp {
        val response = client.get("/api/v1/quotes") {
            parameter("source_country", "XX")
            parameter("destination_country", "YY")
            parameter("send_currency", "XXX")
            parameter("receive_currency", "YYY")
            parameter("send_amount", "100.00")
            parameter("delivery_method", "BANK_TRANSFER")
            parameter("sender_id", "user-123")
        }

        response.status shouldBe HttpStatusCode.UnprocessableEntity
        response.bodyAsText() shouldContain "not supported"
    }

    @Test
    fun `GET quotes returns 400 for invalid amount`() = quoteTestApp {
        val response = client.get("/api/v1/quotes") {
            parameter("source_country", "US")
            parameter("destination_country", "PH")
            parameter("send_currency", "USD")
            parameter("receive_currency", "PHP")
            parameter("send_amount", "5.00")
            parameter("delivery_method", "BANK_TRANSFER")
            parameter("sender_id", "user-123")
        }

        response.status shouldBe HttpStatusCode.BadRequest
        response.bodyAsText() shouldContain "below minimum"
    }

    // ── GET /api/v1/quotes/{quoteId}/validate ──────────────────────────────────

    @Test
    fun `validate fresh quote returns 200`() = quoteTestApp {
        // Create a quote first
        val createResponse = client.get("/api/v1/quotes") {
            parameter("source_country", "US")
            parameter("destination_country", "PH")
            parameter("send_currency", "USD")
            parameter("receive_currency", "PHP")
            parameter("send_amount", "100.00")
            parameter("delivery_method", "BANK_TRANSFER")
            parameter("sender_id", "user-123")
        }
        createResponse.status shouldBe HttpStatusCode.OK
        val quoteJson = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val quoteId = quoteJson["quoteId"]?.jsonPrimitive?.content!!

        // Validate it
        val validateResponse = client.get("/api/v1/quotes/$quoteId/validate")

        validateResponse.status shouldBe HttpStatusCode.OK
        val validateJson = Json.parseToJsonElement(validateResponse.bodyAsText()).jsonObject
        validateJson["quoteId"]?.jsonPrimitive?.content shouldBe quoteId
    }

    @Test
    fun `validate unknown quote returns 410 Gone`() = quoteTestApp {
        val response = client.get("/api/v1/quotes/non-existent-id/validate")

        response.status shouldBe HttpStatusCode.Gone
        response.bodyAsText() shouldContain "expired or not found"
    }
}
