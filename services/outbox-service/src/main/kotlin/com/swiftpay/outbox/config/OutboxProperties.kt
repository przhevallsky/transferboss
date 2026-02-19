package com.swiftpay.outbox.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Type-safe configuration for Outbox polling.
 * Values come from application.yml -> outbox.polling.*
 */
@ConfigurationProperties(prefix = "outbox.polling")
data class OutboxProperties(
    /** Polling interval in milliseconds */
    val intervalMs: Long = 500,
    /** Number of records per poll */
    val batchSize: Int = 100,
    /** Kafka topic for transfer events */
    val targetTopic: String = "transfer.events"
)
