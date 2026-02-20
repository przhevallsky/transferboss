package com.swiftpay.transfer.api.dto.request

import jakarta.validation.constraints.*
import java.math.BigDecimal
import java.util.UUID

/**
 * HTTP request body для POST /api/v1/transfers.
 *
 * Bean Validation аннотации проверяют формат данных.
 * Бизнес-валидация (коридор поддерживается, лимиты) — в TransferService.
 *
 * Поля nullable + @NotNull: если клиент не отправит поле, Jackson присвоит null,
 * @NotNull поймает это и вернёт читаемую ошибку валидации.
 */
data class CreateTransferRequest(

    @field:NotNull(message = "quote_id is required")
    val quoteId: UUID? = null,

    @field:NotNull(message = "recipient_id is required")
    val recipientId: UUID? = null,

    @field:NotBlank(message = "delivery_method is required")
    @field:Pattern(
        regexp = "BANK_DEPOSIT|CASH_PICKUP|MOBILE_WALLET",
        message = "delivery_method must be one of: BANK_DEPOSIT, CASH_PICKUP, MOBILE_WALLET"
    )
    val deliveryMethod: String? = null,

    @field:NotNull(message = "send_amount is required")
    @field:Positive(message = "send_amount must be positive")
    @field:Digits(integer = 13, fraction = 2, message = "send_amount must have at most 2 decimal places")
    val sendAmount: BigDecimal? = null,

    @field:NotBlank(message = "send_currency is required")
    @field:Size(min = 3, max = 3, message = "send_currency must be ISO 4217 (3 chars)")
    val sendCurrency: String? = null,

    @field:NotBlank(message = "receive_currency is required")
    @field:Size(min = 3, max = 3, message = "receive_currency must be ISO 4217 (3 chars)")
    val receiveCurrency: String? = null,

    @field:NotBlank(message = "source_country is required")
    @field:Size(min = 2, max = 2, message = "source_country must be ISO 3166-1 alpha-2 (2 chars)")
    val sourceCountry: String? = null,

    @field:NotBlank(message = "dest_country is required")
    @field:Size(min = 2, max = 2, message = "dest_country must be ISO 3166-1 alpha-2 (2 chars)")
    val destCountry: String? = null,

    val purpose: String? = null,

    val referenceNote: String? = null
)
