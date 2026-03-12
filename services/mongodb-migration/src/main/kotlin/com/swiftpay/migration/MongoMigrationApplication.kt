package com.swiftpay.migration

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MongoMigrationApplication

fun main(args: Array<String>) {
    runApplication<MongoMigrationApplication>(*args)
}
