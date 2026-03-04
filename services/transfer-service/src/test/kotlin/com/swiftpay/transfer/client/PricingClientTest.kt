package com.swiftpay.transfer.client

import com.swiftpay.transfer.exception.PricingUnavailableException
import com.swiftpay.transfer.exception.QuoteExpiredException
import com.transferhub.pricing.grpc.v1.PricingServiceGrpc
import com.transferhub.pricing.grpc.v1.ValidateQuoteRequest
import com.transferhub.pricing.grpc.v1.ValidateQuoteResponse
import com.transferhub.pricing.grpc.v1.QuoteResponse
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class PricingClientTest {

    private val serverName = "pricing-test-${System.nanoTime()}"
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var pricingClient: PricingClient
    private lateinit var fakeService: FakePricingService

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

        pricingClient = PricingClient(channel)
    }

    @AfterEach
    fun teardown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `should return QuoteData when quote is valid`() {
            fakeService.response = ValidateQuoteResponse.newBuilder()
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
