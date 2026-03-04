package com.transferhub.pricing.service

import java.math.BigDecimal

data class CorridorPricing(
    val sourceCountry: String,
    val destinationCountry: String,
    val sendCurrency: String,
    val receiveCurrency: String,
    val feeAmount: BigDecimal,
    val exchangeRate: BigDecimal,
    val deliveryMethods: List<DeliveryMethodConfig>,
    val minAmount: BigDecimal,
    val maxAmount: BigDecimal,
)

data class DeliveryMethodConfig(
    val type: String,
    val estimateMinMinutes: Int,
    val estimateMaxMinutes: Int,
)

val DEFAULT_CORRIDORS: Map<String, CorridorPricing> = mapOf(
    "US_PH" to CorridorPricing(
        sourceCountry = "US",
        destinationCountry = "PH",
        sendCurrency = "USD",
        receiveCurrency = "PHP",
        feeAmount = BigDecimal("5.99"),
        exchangeRate = BigDecimal("56.20"),
        deliveryMethods = listOf(
            DeliveryMethodConfig("BANK_TRANSFER", estimateMinMinutes = 60, estimateMaxMinutes = 1440),
            DeliveryMethodConfig("MOBILE_WALLET", estimateMinMinutes = 5, estimateMaxMinutes = 30),
        ),
        minAmount = BigDecimal("10.00"),
        maxAmount = BigDecimal("2500.00"),
    ),
    "US_MX" to CorridorPricing(
        sourceCountry = "US",
        destinationCountry = "MX",
        sendCurrency = "USD",
        receiveCurrency = "MXN",
        feeAmount = BigDecimal("4.99"),
        exchangeRate = BigDecimal("17.15"),
        deliveryMethods = listOf(
            DeliveryMethodConfig("BANK_TRANSFER", estimateMinMinutes = 30, estimateMaxMinutes = 1440),
            DeliveryMethodConfig("CASH_PICKUP", estimateMinMinutes = 15, estimateMaxMinutes = 60),
        ),
        minAmount = BigDecimal("10.00"),
        maxAmount = BigDecimal("3000.00"),
    ),
    "GB_IN" to CorridorPricing(
        sourceCountry = "GB",
        destinationCountry = "IN",
        sendCurrency = "GBP",
        receiveCurrency = "INR",
        feeAmount = BigDecimal("3.99"),
        exchangeRate = BigDecimal("105.25"),
        deliveryMethods = listOf(
            DeliveryMethodConfig("BANK_TRANSFER", estimateMinMinutes = 60, estimateMaxMinutes = 2880),
            DeliveryMethodConfig("MOBILE_WALLET", estimateMinMinutes = 5, estimateMaxMinutes = 30),
        ),
        minAmount = BigDecimal("5.00"),
        maxAmount = BigDecimal("5000.00"),
    ),
    "US_IN" to CorridorPricing(
        sourceCountry = "US",
        destinationCountry = "IN",
        sendCurrency = "USD",
        receiveCurrency = "INR",
        feeAmount = BigDecimal("5.49"),
        exchangeRate = BigDecimal("83.12"),
        deliveryMethods = listOf(
            DeliveryMethodConfig("BANK_TRANSFER", estimateMinMinutes = 60, estimateMaxMinutes = 2880),
        ),
        minAmount = BigDecimal("10.00"),
        maxAmount = BigDecimal("5000.00"),
    ),
)
