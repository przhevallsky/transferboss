package com.transferhub.pricing

import com.transferhub.pricing.grpc.PricingGrpcService
import com.transferhub.pricing.grpc.v1.PricingServiceGrpcKt
import com.transferhub.pricing.grpc.v1.getQuoteRequest
import com.transferhub.pricing.grpc.v1.validateQuoteRequest
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PricingGrpcServiceTest {

    private val serverName = "pricing-test-${System.nanoTime()}"
    private lateinit var channel: ManagedChannel
    private lateinit var stub: PricingServiceGrpcKt.PricingServiceCoroutineStub

    @BeforeAll
    fun setup() {
        InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(PricingGrpcService())
            .build()
            .start()

        channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build()

        stub = PricingServiceGrpcKt.PricingServiceCoroutineStub(channel)
    }

    @AfterAll
    fun teardown() {
        channel.shutdownNow()
    }

    @Test
    fun `getQuote returns valid mock quote`() = runBlocking {
        val request = getQuoteRequest {
            sourceCountry = "GB"
            destinationCountry = "PL"
            sendCurrency = "GBP"
            receiveCurrency = "PLN"
            sendAmount = "1000.00"
            deliveryMethod = "BANK_TRANSFER"
            senderId = "user-123"
        }

        val response = stub.getQuote(request)

        response.quoteId.shouldNotBeBlank()
        response.sendAmount shouldBe "1000.00"
        response.receiveAmount shouldBe "4250.00"
        response.exchangeRate shouldBe "4.2500"
        response.feeAmount shouldBe "3.99"
        response.feeCurrency shouldBe "GBP"
        response.sendCurrency shouldBe "GBP"
        response.receiveCurrency shouldBe "PLN"
        response.deliveryMethod shouldBe "BANK_TRANSFER"
        response.expiresAtEpochMs shouldNotBe 0L
        response.ttlSeconds shouldBe 30
    }

    @Test
    fun `validateQuote returns valid for existing quote`() = runBlocking {
        val quoteResponse = stub.getQuote(getQuoteRequest {
            sourceCountry = "GB"
            destinationCountry = "PL"
            sendCurrency = "GBP"
            receiveCurrency = "PLN"
            sendAmount = "500.00"
            deliveryMethod = "BANK_TRANSFER"
            senderId = "user-456"
        })

        val validateResponse = stub.validateQuote(validateQuoteRequest {
            quoteId = quoteResponse.quoteId
        })

        validateResponse.isValid shouldBe true
        validateResponse.quote shouldNotBe null
        validateResponse.quote.quoteId shouldBe quoteResponse.quoteId
    }
}
