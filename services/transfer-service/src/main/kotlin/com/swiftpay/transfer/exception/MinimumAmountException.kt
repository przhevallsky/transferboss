package com.swiftpay.transfer.exception

import java.math.BigDecimal

class MinimumAmountException(
    corridorId: String,
    minAmount: BigDecimal,
    currency: String,
    requestedAmount: BigDecimal
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/minimum-amount",
    title = "Below Minimum Amount",
    statusCode = 422,
    message = "Minimum send amount for $corridorId is $minAmount $currency. Requested: $requestedAmount"
)
