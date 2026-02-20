package com.swiftpay.transfer.lock

import com.swiftpay.transfer.exception.BusinessException

class DistributedLockException(key: String) : BusinessException(
    errorType = "https://api.transferhub.com/errors/lock-acquisition-timeout",
    title = "Service Temporarily Unavailable",
    statusCode = 503,
    message = "Could not acquire lock for key '$key' within timeout. Please retry."
)
