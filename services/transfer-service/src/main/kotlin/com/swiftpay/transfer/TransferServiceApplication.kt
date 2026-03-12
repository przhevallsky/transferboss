package com.swiftpay.transfer

import com.swiftpay.transfer.config.CorridorProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CorridorProperties::class)
class TransferServiceApplication

fun main(args: Array<String>) {
    runApplication<TransferServiceApplication>(*args)
}
