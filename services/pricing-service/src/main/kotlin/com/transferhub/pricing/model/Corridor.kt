package com.transferhub.pricing.model

import java.math.BigDecimal

data class Corridor(
    val sourceCountry: String,
    val destinationCountry: String,
    val sendCurrency: String,
    val receiveCurrency: String,
    val deliveryMethods: List<DeliveryMethod>,
    val active: Boolean,
)

data class DeliveryMethod(
    val type: String,
    val feeTiers: List<FeeTier>,
    val minAmount: BigDecimal,
    val maxAmount: BigDecimal,
)

data class FeeTier(
    val minAmount: BigDecimal,
    val maxAmount: BigDecimal,
    val feeType: FeeType,
    val feeValue: BigDecimal,
)

enum class FeeType {
    FIXED,
    PERCENTAGE,
}
