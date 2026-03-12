package com.swiftpay.transfer.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "transfer")
data class CorridorProperties(
    val corridors: List<CorridorConfig> = emptyList()
) {
    data class CorridorConfig(
        val corridorId: String,
        val deliveryMethods: String,
        val minimumAmount: BigDecimal = BigDecimal("1.00")
    )
}
