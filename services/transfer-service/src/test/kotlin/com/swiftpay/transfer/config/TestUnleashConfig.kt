package com.swiftpay.transfer.config

import io.getunleash.FakeUnleash
import io.getunleash.Unleash
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration
class TestUnleashConfig {

    @Bean
    @Primary
    fun unleash(): Unleash = FakeUnleash()
}
