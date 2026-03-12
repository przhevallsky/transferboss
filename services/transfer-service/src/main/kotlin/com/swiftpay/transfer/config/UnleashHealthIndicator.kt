package com.swiftpay.transfer.config

import io.getunleash.Unleash
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.stereotype.Component

@Component
class UnleashHealthIndicator(private val unleash: Unleash) : HealthIndicator {

    override fun health(): Health {
        return try {
            val toggleCount = unleash.more().featureToggleNames.size
            Health.up().withDetail("toggles-count", toggleCount).build()
        } catch (e: Exception) {
            Health.down().withDetail("error", e.message ?: "unknown").build()
        }
    }
}
