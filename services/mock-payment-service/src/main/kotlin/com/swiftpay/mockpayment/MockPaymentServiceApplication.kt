package com.swiftpay.mockpayment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MockPaymentServiceApplication

fun main(args: Array<String>) {
    runApplication<MockPaymentServiceApplication>(*args)
}
