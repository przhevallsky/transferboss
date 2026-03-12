package com.swiftpay.transfer.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.swiftpay.transfer.exception.PricingUnavailableException
import com.swiftpay.transfer.exception.QuoteExpiredException
import com.transferhub.pricing.grpc.v1.PricingServiceGrpc
import com.transferhub.pricing.grpc.v1.ValidateQuoteRequest
import com.transferhub.pricing.grpc.v1.ValidateQuoteResponse
import com.transferhub.pricing.grpc.v1.QuoteResponse
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal
import java.time.Duration

class PricingClientTest {

    private val serverName = "pricing-test-${System.nanoTime()}"
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var pricingClient: PricingClient
    private lateinit var fakeService: FakePricingService
    private val redisTemplate: RedisTemplate<String, String> = mockk(relaxed = true)
    private val valueOps: ValueOperations<String, String> = mockk(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private fun createRegistryWithConfig(
        minCalls: Int = 5,
        failureThreshold: Float = 50f,
        waitDuration: Duration = Duration.ofSeconds(30)
    ): CircuitBreakerRegistry {
        val config = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(minCalls)
            .failureRateThreshold(failureThreshold)
            .waitDurationInOpenState(waitDuration)
            .build()
        return CircuitBreakerRegistry.of(config)
    }

    @BeforeEach
    fun setup() {
        fakeService = FakePricingService()
        server = InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(fakeService)
            .build()
            .start()

        channel = InProcessChannelBuilder
            .forName(serverName)
            .directExecutor()
            .build()

        every { redisTemplate.opsForValue() } returns valueOps

        pricingClient = PricingClient(
            channel,
            createRegistryWithConfig(),
            redisTemplate,
            objectMapper
        )
    }

    @AfterEach
    fun teardown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private val validResponse = ValidateQuoteResponse.newBuilder()
        .setIsValid(true)
        .setQuote(
            QuoteResponse.newBuilder()
                .setQuoteId("q-123")
                .setSendAmount("100.00")
                .setReceiveAmount("5620.00")
                .setExchangeRate("56.20")
                .setFeeAmount("5.99")
                .setFeeCurrency("USD")
                .setSendCurrency("USD")
                .setReceiveCurrency("PHP")
                .build()
        )
        .build()

    @Nested
    inner class HappyPath {

        @Test
        fun `should return QuoteData when quote is valid`() {
            fakeService.response = validResponse

            val result = pricingClient.validateQuote("q-123")

            assertEquals("q-123", result.quoteId)
            assertEquals(BigDecimal("100.00"), result.sendAmount)
            assertEquals(BigDecimal("5620.00"), result.receiveAmount)
            assertEquals(BigDecimal("56.20"), result.exchangeRate)
            assertEquals(BigDecimal("5.99"), result.feeAmount)
            assertEquals("USD", result.feeCurrency)
            assertEquals("USD", result.sendCurrency)
            assertEquals("PHP", result.receiveCurrency)
        }

        @Test
        fun `should cache quote on successful validation`() {
            fakeService.response = validResponse

            pricingClient.validateQuote("q-123")

            verify { valueOps.set(eq("quote:validated:q-123"), any(), any<Duration>()) }
        }
    }

    @Nested
    inner class ExpiredQuote {

        @Test
        fun `should throw QuoteExpiredException when quote is not valid`() {
            fakeService.response = ValidateQuoteResponse.newBuilder()
                .setIsValid(false)
                .setRejectionReason("Quote expired")
                .build()

            val ex = assertThrows<QuoteExpiredException> {
                pricingClient.validateQuote("q-expired")
            }
            assertEquals(409, ex.statusCode)
        }

        @Test
        fun `should throw QuoteExpiredException with default reason when blank`() {
            fakeService.response = ValidateQuoteResponse.newBuilder()
                .setIsValid(false)
                .setRejectionReason("")
                .build()

            assertThrows<QuoteExpiredException> {
                pricingClient.validateQuote("q-expired")
            }
        }
    }

    @Nested
    inner class GrpcErrors {

        @Test
        fun `should throw QuoteExpiredException on NOT_FOUND status`() {
            fakeService.statusError = Status.NOT_FOUND.withDescription("Quote not found")

            val ex = assertThrows<QuoteExpiredException> {
                pricingClient.validateQuote("q-missing")
            }
            assertEquals(409, ex.statusCode)
        }

        @Test
        fun `should throw QuoteExpiredException on INVALID_ARGUMENT status`() {
            fakeService.statusError = Status.INVALID_ARGUMENT.withDescription("Bad quote id")

            assertThrows<QuoteExpiredException> {
                pricingClient.validateQuote("bad-id")
            }
        }

        @Test
        fun `should throw PricingUnavailableException on UNAVAILABLE status`() {
            fakeService.statusError = Status.UNAVAILABLE.withDescription("Service down")

            val ex = assertThrows<PricingUnavailableException> {
                pricingClient.validateQuote("q-123")
            }
            assertEquals(503, ex.statusCode)
        }

        @Test
        fun `should throw PricingUnavailableException on INTERNAL status`() {
            fakeService.statusError = Status.INTERNAL.withDescription("Server error")

            assertThrows<PricingUnavailableException> {
                pricingClient.validateQuote("q-123")
            }
        }
    }

    @Nested
    inner class CircuitBreakerBehavior {

        @Test
        fun `should open circuit after consecutive failures and use cache fallback`() {
            // Use a registry with minCalls=3 for faster testing
            pricingClient = PricingClient(
                channel,
                createRegistryWithConfig(minCalls = 3),
                redisTemplate,
                objectMapper
            )
            fakeService.statusError = Status.UNAVAILABLE.withDescription("down")

            // Trigger 3 failures to open the circuit
            repeat(3) {
                assertThrows<PricingUnavailableException> {
                    pricingClient.validateQuote("q-123")
                }
            }

            // Cache has a valid quote → fallback should return it
            val cachedJson = objectMapper.writeValueAsString(
                QuoteData("q-123", BigDecimal("100"), BigDecimal("5620"), BigDecimal("56.20"),
                    BigDecimal("5.99"), "USD", "USD", "PHP")
            )
            every { valueOps.get("quote:validated:q-123") } returns cachedJson

            // 4th call — circuit is open, should use cache fallback
            val result = pricingClient.validateQuote("q-123")
            assertEquals("q-123", result.quoteId)
        }

        @Test
        fun `should throw PricingUnavailableException when circuit open and no cache`() {
            pricingClient = PricingClient(
                channel,
                createRegistryWithConfig(minCalls = 3),
                redisTemplate,
                objectMapper
            )
            fakeService.statusError = Status.UNAVAILABLE.withDescription("down")

            repeat(3) {
                assertThrows<PricingUnavailableException> {
                    pricingClient.validateQuote("q-nocache")
                }
            }

            // No cache entry
            every { valueOps.get("quote:validated:q-nocache") } returns null

            val ex = assertThrows<PricingUnavailableException> {
                pricingClient.validateQuote("q-nocache")
            }
            assert(ex.message!!.contains("no cached quote"))
        }

        @Test
        fun `should transition back to closed after successful calls in half-open`() {
            val registry = createRegistryWithConfig(minCalls = 3, waitDuration = Duration.ofMillis(100))
            pricingClient = PricingClient(channel, registry, redisTemplate, objectMapper)
            fakeService.statusError = Status.UNAVAILABLE.withDescription("down")

            // Open the circuit
            repeat(3) {
                assertThrows<PricingUnavailableException> { pricingClient.validateQuote("q-123") }
            }

            // Wait for half-open
            Thread.sleep(200)

            // Now service is back
            fakeService.statusError = null
            fakeService.response = validResponse

            // Half-open → success → closed
            val result = pricingClient.validateQuote("q-123")
            assertEquals("q-123", result.quoteId)

            // Verify circuit is closed — subsequent calls go through
            val result2 = pricingClient.validateQuote("q-123")
            assertEquals("q-123", result2.quoteId)
        }
    }

    /**
     * Fake gRPC service implementation that can be configured per test.
     */
    private class FakePricingService : PricingServiceGrpc.PricingServiceImplBase() {

        var response: ValidateQuoteResponse? = null
        var statusError: Status? = null

        override fun validateQuote(
            request: ValidateQuoteRequest,
            responseObserver: StreamObserver<ValidateQuoteResponse>
        ) {
            statusError?.let {
                responseObserver.onError(it.asRuntimeException())
                return
            }
            response?.let {
                responseObserver.onNext(it)
                responseObserver.onCompleted()
                return
            }
            responseObserver.onError(Status.UNIMPLEMENTED.asRuntimeException())
        }
    }
}
