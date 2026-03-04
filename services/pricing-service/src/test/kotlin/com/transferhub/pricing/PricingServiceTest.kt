package com.transferhub.pricing

import com.transferhub.pricing.api.dto.QuoteRequest
import com.transferhub.pricing.model.Quote
import com.transferhub.pricing.service.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test

class PricingServiceTest {

    private val quoteCacheService = mockk<QuoteCacheService>(relaxed = true)
    private val pricingService = PricingService(DEFAULT_CORRIDORS, quoteCacheService, 30L)

    // ── calculateQuote: happy paths ────────────────────────────────────────────

    @Test
    fun `calculateQuote US_PH $100 returns correct receiveAmount`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = "100.00",
            deliveryMethod = "BANK_TRANSFER",
            senderId = "user-123",
        )

        val quote = pricingService.calculateQuote(request)

        // (100.00 - 5.99) * 56.20 = 94.01 * 56.20 = 5283.36
        quote.receiveAmount shouldBe BigDecimal("5283.36")
        quote.exchangeRate shouldBe BigDecimal("56.20")
        quote.feeAmount shouldBe BigDecimal("5.99")
        quote.feeCurrency shouldBe "USD"
        quote.sendCurrency shouldBe "USD"
        quote.receiveCurrency shouldBe "PHP"
        quote.deliveryMethod shouldBe "BANK_TRANSFER"
        quote.senderId shouldBe "user-123"
        quote.sourceCountry shouldBe "US"
        quote.destinationCountry shouldBe "PH"
        quote.quoteId shouldNotBe null
    }

    @Test
    fun `calculateQuote US_MX with CASH_PICKUP returns correct values`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "MX",
            sendCurrency = "USD",
            receiveCurrency = "MXN",
            sendAmount = "200.00",
            deliveryMethod = "CASH_PICKUP",
            senderId = "user-456",
        )

        val quote = pricingService.calculateQuote(request)

        // (200.00 - 4.99) * 17.15 = 195.01 * 17.15 = 3344.42
        quote.receiveAmount shouldBe BigDecimal("3344.42")
        quote.deliveryEstimateMinMinutes shouldBe 15
        quote.deliveryEstimateMaxMinutes shouldBe 60
    }

    @Test
    fun `calculateQuote saves quote to cache`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = "100.00",
            deliveryMethod = "BANK_TRANSFER",
            senderId = "user-123",
        )

        pricingService.calculateQuote(request)

        coVerify(exactly = 1) { quoteCacheService.save(any()) }
    }

    // ── calculateQuote: error cases ────────────────────────────────────────────

    @Test
    fun `calculateQuote throws CorridorNotSupportedException for unknown corridor`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "XX",
            destinationCountry = "YY",
            sendCurrency = "XXX",
            receiveCurrency = "YYY",
            sendAmount = "100.00",
            deliveryMethod = "BANK_TRANSFER",
            senderId = "user-123",
        )

        val ex = shouldThrow<CorridorNotSupportedException> {
            pricingService.calculateQuote(request)
        }
        ex.message shouldContain "XX_YY"
    }

    @Test
    fun `calculateQuote throws DeliveryMethodNotAvailableException for wrong method`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "IN",
            sendCurrency = "USD",
            receiveCurrency = "INR",
            sendAmount = "100.00",
            deliveryMethod = "CASH_PICKUP",
            senderId = "user-123",
        )

        val ex = shouldThrow<DeliveryMethodNotAvailableException> {
            pricingService.calculateQuote(request)
        }
        ex.message shouldContain "CASH_PICKUP"
    }

    @Test
    fun `calculateQuote throws InvalidAmountException for amount below minimum`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = "5.00",
            deliveryMethod = "BANK_TRANSFER",
            senderId = "user-123",
        )

        val ex = shouldThrow<InvalidAmountException> {
            pricingService.calculateQuote(request)
        }
        ex.message shouldContain "below minimum"
    }

    @Test
    fun `calculateQuote throws InvalidAmountException for amount above maximum`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = "5000.00",
            deliveryMethod = "BANK_TRANSFER",
            senderId = "user-123",
        )

        val ex = shouldThrow<InvalidAmountException> {
            pricingService.calculateQuote(request)
        }
        ex.message shouldContain "exceeds maximum"
    }

    @Test
    fun `calculateQuote throws InvalidAmountException for amount equal to fee`() = runBlocking {
        val request = QuoteRequest(
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = "5.99",
            deliveryMethod = "BANK_TRANSFER",
            senderId = "user-123",
        )

        val ex = shouldThrow<InvalidAmountException> {
            pricingService.calculateQuote(request)
        }
        ex.message shouldContain "greater than fee"
    }

    // ── validateQuote ──────────────────────────────────────────────────────────

    @Test
    fun `validateQuote returns valid for cached non-expired quote`() = runBlocking {
        val quote = Quote(
            quoteId = "test-quote-id",
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = BigDecimal("100.00"),
            receiveAmount = BigDecimal("5283.36"),
            exchangeRate = BigDecimal("56.20"),
            feeAmount = BigDecimal("5.99"),
            feeCurrency = "USD",
            deliveryMethod = "BANK_TRANSFER",
            expiresAt = Instant.now().plusSeconds(60),
            senderId = "user-123",
            deliveryEstimateMinMinutes = 60,
            deliveryEstimateMaxMinutes = 1440,
        )

        coEvery { quoteCacheService.get("test-quote-id") } returns quote

        val result = pricingService.validateQuote("test-quote-id")

        result.isValid shouldBe true
        result.quote shouldNotBe null
        result.quote!!.quoteId shouldBe "test-quote-id"
    }

    @Test
    fun `validateQuote returns invalid for expired quote`() = runBlocking {
        val quote = Quote(
            quoteId = "expired-quote-id",
            sourceCountry = "US",
            destinationCountry = "PH",
            sendCurrency = "USD",
            receiveCurrency = "PHP",
            sendAmount = BigDecimal("100.00"),
            receiveAmount = BigDecimal("5283.36"),
            exchangeRate = BigDecimal("56.20"),
            feeAmount = BigDecimal("5.99"),
            feeCurrency = "USD",
            deliveryMethod = "BANK_TRANSFER",
            expiresAt = Instant.now().minusSeconds(60),
            senderId = "user-123",
            deliveryEstimateMinMinutes = 60,
            deliveryEstimateMaxMinutes = 1440,
        )

        coEvery { quoteCacheService.get("expired-quote-id") } returns quote

        val result = pricingService.validateQuote("expired-quote-id")

        result.isValid shouldBe false
        result.quote shouldBe null
    }

    @Test
    fun `validateQuote returns invalid for unknown quoteId`() = runBlocking {
        coEvery { quoteCacheService.get("unknown-id") } returns null

        val result = pricingService.validateQuote("unknown-id")

        result.isValid shouldBe false
        result.quote shouldBe null
    }
}
