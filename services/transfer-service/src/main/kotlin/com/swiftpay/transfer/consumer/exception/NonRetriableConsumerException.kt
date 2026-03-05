package com.swiftpay.transfer.consumer.exception

/**
 * Non-retriable error — retrying will not help (validation failure, corrupt data).
 * Routes directly to DLT, skipping retry topics.
 */
class NonRetriableConsumerException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
