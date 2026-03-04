package com.swiftpay.transfer.exception

class QuoteExpiredException(
    quoteId: String,
    reason: String? = null
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/quote-expired",
    title = "Quote Expired",
    statusCode = 409,
    message = "Quote $quoteId is invalid: ${reason ?: "expired or not found"}"
)
