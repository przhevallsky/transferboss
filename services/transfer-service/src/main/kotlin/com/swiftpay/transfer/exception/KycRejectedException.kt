package com.swiftpay.transfer.exception

class KycRejectedException(
    senderId: String
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/kyc-rejected",
    title = "KYC Verification Failed",
    statusCode = 403,
    message = "Sender $senderId has not passed KYC verification"
)
