package com.swiftpay.transfer.exception

import java.util.UUID

class RecipientNotFoundException(recipientId: UUID) : BusinessException(
    errorType = "https://api.transferhub.com/errors/recipient-not-found",
    title = "Recipient Not Found",
    statusCode = 404,
    message = "Recipient with id $recipientId not found"
)
