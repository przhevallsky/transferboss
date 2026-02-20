package com.swiftpay.transfer.api.dto.response

import java.time.Instant

/**
 * HTTP response body — представление перевода для клиента.
 *
 * Отдельный от entity: не все поля entity нужны клиенту,
 * клиент не должен видеть internal fields (version, idempotency_key, outbox status).
 */
data class TransferResponse(
    val id: String,
    val status: String,
    val displayStatus: String,
    val sendAmount: String,
    val sendCurrency: String,
    val receiveAmount: String,
    val receiveCurrency: String,
    val exchangeRate: String,
    val feeAmount: String,
    val deliveryMethod: String,
    val sourceCountry: String,
    val destCountry: String,
    val recipient: RecipientBrief,
    val createdAt: Instant,
    val statusReason: String? = null
)

/** Краткая информация о получателе в ответе перевода */
data class RecipientBrief(
    val id: String,
    val firstName: String,
    val lastName: String
)
