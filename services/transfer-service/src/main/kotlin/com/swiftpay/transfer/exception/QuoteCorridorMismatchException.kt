package com.swiftpay.transfer.exception

class QuoteCorridorMismatchException(
    quoteId: String,
    quoteCurrency: String,
    requestCurrency: String
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/quote-corridor-mismatch",
    title = "Quote Corridor Mismatch",
    statusCode = 400,
    message = "Quote $quoteId currency ($quoteCurrency) doesn't match request ($requestCurrency)"
)
