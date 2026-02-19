package com.swiftpay.transfer.exception

class UnsupportedDeliveryMethodException(
    deliveryMethod: String,
    corridorId: String,
    availableMethods: List<String>
) : BusinessException(
    errorType = "https://api.transferhub.com/errors/unsupported-delivery-method",
    title = "Unsupported Delivery Method",
    statusCode = 422,
    message = "$deliveryMethod is not available for corridor $corridorId. Available methods: $availableMethods"
)
