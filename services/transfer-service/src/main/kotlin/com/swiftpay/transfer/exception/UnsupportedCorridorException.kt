package com.swiftpay.transfer.exception

class UnsupportedCorridorException(
    sourceCountry: String,
    destCountry: String
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/unsupported-corridor",
    title = "Unsupported Corridor",
    statusCode = 422,
    message = "Corridor ${sourceCountry}→${destCountry} is not supported"
)
