package com.swiftpay.mockpayout

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MockPayoutServiceApplication

fun main(args: Array<String>) {
    runApplication<MockPayoutServiceApplication>(*args)
}
