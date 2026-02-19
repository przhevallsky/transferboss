package com.swiftpay.transfer.exception

class InvalidTransferStateException(
    currentStatus: String,
    targetStatus: String
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/invalid-transfer-state",
    title = "Invalid Transfer State",
    statusCode = 409,
    message = "Cannot transition from $currentStatus to $targetStatus"
)
