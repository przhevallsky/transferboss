package com.swiftpay.transfer.config

import io.getunleash.DefaultUnleash
import io.getunleash.Unleash
import io.getunleash.util.UnleashConfig as UnleashSdkConfig
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class UnleashConfig(
    @Value("\${unleash.api-url}") private val apiUrl: String,
    @Value("\${unleash.api-token}") private val apiToken: String,
    @Value("\${unleash.app-name}") private val appName: String,
    @Value("\${unleash.environment}") private val environment: String,
    @Value("\${unleash.fetch-toggles-interval:10}") private val fetchInterval: Long
) {

    @Bean(destroyMethod = "shutdown")
    fun unleash(): Unleash {
        val config = UnleashSdkConfig.builder()
            .unleashAPI("$apiUrl/")
            .apiKey(apiToken)
            .appName(appName)
            .environment(environment)
            .fetchTogglesInterval(fetchInterval)
            .build()
        return DefaultUnleash(config)
    }
}
