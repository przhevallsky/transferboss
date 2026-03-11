package com.swiftpay.transfer.client

import com.swiftpay.transfer.exception.IdentityUnavailableException
import com.swiftpay.transfer.exception.KycRejectedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.UUID

class IdentityClientTest {

    private lateinit var identityClient: IdentityClient
    private lateinit var mockServer: MockRestServiceServer
    private lateinit var restClientBuilder: RestClient.Builder
    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun createRegistry(minCalls: Int = 5): CircuitBreakerRegistry {
        val config = CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(minCalls)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build()
        return CircuitBreakerRegistry.of(config)
    }

    @BeforeEach
    fun setup() {
        restClientBuilder = RestClient.builder().baseUrl("http://identity-test")
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build()
        identityClient = IdentityClient(restClientBuilder.build(), createRegistry())
    }

    @Nested
    inner class HappyPath {

        @Test
        fun `should return KycStatus when user is approved`() {
            mockServer.expect(requestTo("http://identity-test/internal/api/v1/users/$senderId/kyc-status"))
                .andRespond(
                    withSuccess(
                        """{"user_id":"$senderId","status":"APPROVED","kyc_level":"FULL","verified_at":"2025-01-01T00:00:00Z"}""",
                        MediaType.APPLICATION_JSON
                    )
                )

            val result = identityClient.checkKyc(senderId)

            assertEquals("APPROVED", result.status)
            assertEquals("FULL", result.kycLevel)
            mockServer.verify()
        }
    }

    @Nested
    inner class KycRejected {

        @Test
        fun `should throw KycRejectedException when user is rejected`() {
            mockServer.expect(requestTo("http://identity-test/internal/api/v1/users/$senderId/kyc-status"))
                .andRespond(
                    withSuccess(
                        """{"user_id":"$senderId","status":"REJECTED","kyc_level":"NONE"}""",
                        MediaType.APPLICATION_JSON
                    )
                )

            val ex = assertThrows<KycRejectedException> {
                identityClient.checkKyc(senderId)
            }
            assertEquals(403, ex.statusCode)
            mockServer.verify()
        }
    }

    @Nested
    inner class ServiceErrors {

        @Test
        fun `should throw IdentityUnavailableException on server error`() {
            mockServer.expect(requestTo("http://identity-test/internal/api/v1/users/$senderId/kyc-status"))
                .andRespond(withServerError())

            val ex = assertThrows<IdentityUnavailableException> {
                identityClient.checkKyc(senderId)
            }
            assertEquals(503, ex.statusCode)
            mockServer.verify()
        }
    }

    @Nested
    inner class CircuitBreakerBehavior {

        @Test
        fun `should throw IdentityUnavailableException when circuit open`() {
            restClientBuilder = RestClient.builder().baseUrl("http://identity-test")
            mockServer = MockRestServiceServer.bindTo(restClientBuilder).build()
            identityClient = IdentityClient(restClientBuilder.build(), createRegistry(minCalls = 3))

            // Expect 3 server errors to open the circuit
            mockServer.expect(ExpectedCount.times(3),
                requestTo("http://identity-test/internal/api/v1/users/$senderId/kyc-status"))
                .andRespond(withServerError())

            // Trigger 3 failures
            repeat(3) {
                assertThrows<IdentityUnavailableException> {
                    identityClient.checkKyc(senderId)
                }
            }

            // 4th call — circuit open, no HTTP call made
            val ex = assertThrows<IdentityUnavailableException> {
                identityClient.checkKyc(senderId)
            }
            assert(ex.message!!.contains("circuit breaker is open"))
        }
    }
}
