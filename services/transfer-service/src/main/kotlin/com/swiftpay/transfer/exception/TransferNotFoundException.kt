package com.swiftpay.transfer.exception

import java.util.UUID

class TransferNotFoundException(transferId: UUID) : BusinessException(
    errorType = "https://api.transferhub.com/errors/transfer-not-found",
    title = "Transfer Not Found",
    statusCode = 404,
    message = "Transfer with id $transferId not found"
)
