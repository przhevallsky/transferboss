package com.swiftpay.transfer.exception

import java.util.UUID

class QuoteExpiredException(quoteId: UUID) : BusinessException(
    errorType = "https://api.transferhub.com/errors/quote-expired",
    title = "Quote Expired",
    statusCode = 422,
    message = "Quote $quoteId has expired. Please request a new quote."
)
