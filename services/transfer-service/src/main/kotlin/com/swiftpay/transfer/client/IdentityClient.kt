package com.swiftpay.transfer.client

import com.swiftpay.transfer.exception.IdentityUnavailableException
import com.swiftpay.transfer.exception.KycRejectedException
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.util.UUID

data class KycStatus(
    val userId: String,
    val status: String,
    val kycLevel: String
)

@Component
class IdentityClient(
    private val identityRestClient: RestClient,
    circuitBreakerRegistry: CircuitBreakerRegistry
) {

    private val log = LoggerFactory.getLogger(IdentityClient::class.java)

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker("identity-service")

    fun checkKyc(senderId: UUID): KycStatus {
        try {
            return circuitBreaker.executeSupplier {
                val response = identityRestClient.get()
                    .uri("/internal/api/v1/users/{id}/kyc-status", senderId)
                    .retrieve()
                    .body(KycResponse::class.java)
                    ?: throw IdentityUnavailableException("Empty response from identity service")

                if (response.status == "REJECTED") {
                    throw KycRejectedException(senderId.toString())
                }

                KycStatus(
                    userId = response.userId,
                    status = response.status,
                    kycLevel = response.kycLevel
                )
            }
        } catch (e: KycRejectedException) {
            throw e
        } catch (e: CallNotPermittedException) {
            log.warn("Circuit breaker open for identity-service: {}", e.message)
            throw IdentityUnavailableException("Identity service circuit breaker is open", e)
        } catch (e: RestClientException) {
            log.error("REST call to identity-service failed: {}", e.message)
            throw IdentityUnavailableException("Identity service error: ${e.message}", e)
        } catch (e: IdentityUnavailableException) {
            throw e
        } catch (e: Exception) {
            log.error("Unexpected error calling identity-service", e)
            throw IdentityUnavailableException("Identity service unavailable: ${e.message}", e)
        }
    }

    private data class KycResponse(
        val userId: String = "",
        val status: String = "",
        val kycLevel: String = "",
        val verifiedAt: String? = null
    )
}
