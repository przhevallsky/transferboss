package com.swiftpay.analytics

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AnalyticsEtlApplication

fun main(args: Array<String>) {
    runApplication<AnalyticsEtlApplication>(*args)
}
