package com.swiftpay.outbox

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class OutboxServiceApplication

fun main(args: Array<String>) {
    runApplication<OutboxServiceApplication>(*args)
}
