package com.swiftpay.transfer.config

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.TimeUnit

@Configuration
class GrpcConfig(
    @Value("\${grpc.pricing.host:localhost}") private val host: String,
    @Value("\${grpc.pricing.port:50051}") private val port: Int
) {
    private var channel: ManagedChannel? = null

    @Bean
    fun pricingChannel(): ManagedChannel {
        val ch = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .build()
        channel = ch
        return ch
    }

    @PreDestroy
    fun shutdown() {
        channel?.shutdown()?.awaitTermination(5, TimeUnit.SECONDS)
    }
}
