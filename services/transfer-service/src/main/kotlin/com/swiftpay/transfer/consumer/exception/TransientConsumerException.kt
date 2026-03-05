package com.swiftpay.transfer.consumer.exception

/**
 * Transient error — retry is expected to succeed (network timeout, temporary unavailability).
 */
class TransientConsumerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
