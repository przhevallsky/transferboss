package com.swiftpay.transfer.config

import com.swiftpay.transfer.lock.ConsulLockProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ConsulLockProperties::class)
class ConsulLockConfig
