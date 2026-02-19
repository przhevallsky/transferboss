package com.swiftpay.transfer.domain.vo

/**
 * Способ получения денег.
 * Разные коридоры поддерживают разные delivery methods.
 */
enum class DeliveryMethod {
    BANK_DEPOSIT,     // На банковский счёт
    CASH_PICKUP,      // Наличные в пункте выдачи
    MOBILE_WALLET;    // На мобильный кошелёк (GCash, M-Pesa)

    companion object {
        fun fromString(value: String): DeliveryMethod =
            entries.find { it.name == value.uppercase() }
                ?: throw IllegalArgumentException("Unknown delivery method: $value. Allowed: ${entries.map { it.name }}")
    }
}
