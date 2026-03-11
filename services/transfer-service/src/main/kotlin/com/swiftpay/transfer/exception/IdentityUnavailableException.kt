package com.swiftpay.transfer.exception

class IdentityUnavailableException(
    detail: String,
    cause: Throwable? = null
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/identity-unavailable",
    title = "Identity Verification Unavailable",
    statusCode = 503,
    message = detail
) {
    init {
        if (cause != null) initCause(cause)
    }
}
