package com.swiftpay.transfer.domain.vo

import java.math.BigDecimal

/**
 * Value object для денежных сумм.
 * КРИТИЧЕСКИ ВАЖНО: в финтехе деньги — ТОЛЬКО BigDecimal.
 * Double/Float запрещены: 0.1 + 0.2 != 0.3 в floating point.
 */
data class Money(
    val amount: BigDecimal,
    val currency: String   // ISO 4217: USD, EUR, GBP, PHP, MXN, INR
) {
    init {
        require(currency.length == 3) { "Currency must be ISO 4217 (3 chars), got: $currency" }
        require(amount >= BigDecimal.ZERO) { "Amount must be non-negative, got: $amount" }
    }

    /** Проверка что сумма строго положительная (для send amount) */
    fun isPositive(): Boolean = amount > BigDecimal.ZERO

    override fun toString(): String = "$amount $currency"
}
