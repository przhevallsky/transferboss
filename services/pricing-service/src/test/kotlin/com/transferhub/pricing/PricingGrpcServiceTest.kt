package com.transferhub.pricing

import com.transferhub.pricing.grpc.PricingGrpcService
import com.transferhub.pricing.grpc.v1.PricingServiceGrpcKt
import com.transferhub.pricing.grpc.v1.getQuoteRequest
import com.transferhub.pricing.grpc.v1.validateQuoteRequest
import com.transferhub.pricing.service.DEFAULT_CORRIDORS
import com.transferhub.pricing.service.PricingService
import com.transferhub.pricing.service.QuoteCacheService
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class PricingGrpcServiceTest {

    private val serverName = "pricing-test-${System.nanoTime()}"
    private val quoteCacheService = mockk<QuoteCacheService>(relaxed = true)
    private val pricingService = PricingService(DEFAULT_CORRIDORS, quoteCacheService, 30L)

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: PricingServiceGrpcKt.PricingServiceCoroutineStub

    @BeforeTest
    fun setup() {
        server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(PricingGrpcService(pricingService))
            .build()
            .start()

        channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build()

        stub = PricingServiceGrpcKt.PricingServiceCoroutineStub(channel)
    }

    @AfterTest
    fun teardown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `getQuote US to PH 100 USD returns correct values`() = runBlocking<Unit> {
        val request = getQuoteRequest {
            sourceCountry = "US"
            destinationCountry = "PH"
            sendCurrency = "USD"
            receiveCurrency = "PHP"
            sendAmount = "100.00"
            deliveryMethod = "BANK_TRANSFER"
            senderId = "user-123"
        }

        val response = stub.getQuote(request)

        response.quoteId.shouldNotBeBlank()
        response.sendAmount shouldBe "100.00"
        response.receiveAmount shouldBe "5283.36"
        response.exchangeRate shouldBe "56.20"
        response.feeAmount shouldBe "5.99"
        response.feeCurrency shouldBe "USD"
        response.sendCurrency shouldBe "USD"
        response.receiveCurrency shouldBe "PHP"
        response.deliveryMethod shouldBe "BANK_TRANSFER"
        response.expiresAtEpochMs shouldNotBe 0L
        (response.ttlSeconds in 1..30) shouldBe true
    }

    @Test
    fun `getQuote unsupported corridor returns INVALID_ARGUMENT`() = runBlocking<Unit> {
        val request = getQuoteRequest {
            sourceCountry = "XX"
            destinationCountry = "YY"
            sendCurrency = "XXX"
            receiveCurrency = "YYY"
            sendAmount = "100.00"
            deliveryMethod = "BANK_TRANSFER"
            senderId = "user-123"
        }

        val ex = shouldThrow<StatusException> {
            stub.getQuote(request)
        }

        ex.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    @Test
    fun `getQuote unavailable delivery method returns INVALID_ARGUMENT`() = runBlocking<Unit> {
        val request = getQuoteRequest {
            sourceCountry = "US"
            destinationCountry = "PH"
            sendCurrency = "USD"
            receiveCurrency = "PHP"
            sendAmount = "100.00"
            deliveryMethod = "CARRIER_PIGEON"
            senderId = "user-123"
        }

        val ex = shouldThrow<StatusException> {
            stub.getQuote(request)
        }

        ex.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    @Test
    fun `getQuote invalid amount returns INVALID_ARGUMENT`() = runBlocking<Unit> {
        val request = getQuoteRequest {
            sourceCountry = "US"
            destinationCountry = "PH"
            sendCurrency = "USD"
            receiveCurrency = "PHP"
            sendAmount = "not-a-number"
            deliveryMethod = "BANK_TRANSFER"
            senderId = "user-123"
        }

        val ex = shouldThrow<StatusException> {
            stub.getQuote(request)
        }

        ex.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }

    @Test
    fun `validateQuote with real quote returns valid`() = runBlocking<Unit> {
        val quoteResponse = stub.getQuote(getQuoteRequest {
            sourceCountry = "US"
            destinationCountry = "PH"
            sendCurrency = "USD"
            receiveCurrency = "PHP"
            sendAmount = "100.00"
            deliveryMethod = "BANK_TRANSFER"
            senderId = "user-456"
        })

        val quoteSlot = slot<com.transferhub.pricing.model.Quote>()
        coVerify { quoteCacheService.save(capture(quoteSlot)) }
        val savedQuote = quoteSlot.captured
        coEvery { quoteCacheService.get(savedQuote.quoteId) } returns savedQuote

        val validateResponse = stub.validateQuote(validateQuoteRequest {
            quoteId = quoteResponse.quoteId
        })

        validateResponse.isValid shouldBe true
        validateResponse.quote shouldNotBe null
        validateResponse.quote.quoteId shouldBe quoteResponse.quoteId
    }

    @Test
    fun `validateQuote unknown quoteId returns invalid with rejection reason`() = runBlocking<Unit> {
        coEvery { quoteCacheService.get("unknown-quote-id") } returns null

        val validateResponse = stub.validateQuote(validateQuoteRequest {
            quoteId = "unknown-quote-id"
        })

        validateResponse.isValid shouldBe false
        validateResponse.rejectionReason.shouldNotBeBlank()
    }

    @Test
    fun `validateQuote blank quoteId returns INVALID_ARGUMENT`() = runBlocking<Unit> {
        val ex = shouldThrow<StatusException> {
            stub.validateQuote(validateQuoteRequest {
                quoteId = ""
            })
        }

        ex.status.code shouldBe io.grpc.Status.INVALID_ARGUMENT.code
    }
}
