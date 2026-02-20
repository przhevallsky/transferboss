package com.swiftpay.transfer.lock

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "consul.lock")
data class ConsulLockProperties(
    val enabled: Boolean = false,
    val sessionTtlSeconds: Int = 15,
    val acquireTimeoutMs: Long = 5000,
    val retryIntervalMs: Long = 50,
    val keyPrefix: String = "locks/transfer"
)
