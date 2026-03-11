package com.swiftpay.transfer.api.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/internal/api/v1/users")
class MockIdentityController {

    @GetMapping("/{id}/kyc-status")
    fun getKycStatus(@PathVariable id: String): Map<String, Any?> {
        if (id == "usr_slow") {
            Thread.sleep(5000)
        }

        val status = if (id == "usr_blocked") "REJECTED" else "APPROVED"
        val kycLevel = if (id == "usr_blocked") "NONE" else "FULL"

        return mapOf(
            "user_id" to id,
            "status" to status,
            "kyc_level" to kycLevel,
            "verified_at" to if (status == "APPROVED") Instant.now().toString() else null
        )
    }
}
