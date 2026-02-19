package com.swiftpay.outbox.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(OutboxProperties::class)
class OutboxConfig
