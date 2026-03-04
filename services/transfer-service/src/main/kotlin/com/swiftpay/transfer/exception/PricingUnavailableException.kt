package com.swiftpay.transfer.exception

class PricingUnavailableException(
    detail: String,
    cause: Throwable? = null
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/pricing-unavailable",
    title = "Pricing Service Unavailable",
    statusCode = 503,
    message = detail
) {
    init {
        if (cause != null) initCause(cause)
    }
}
