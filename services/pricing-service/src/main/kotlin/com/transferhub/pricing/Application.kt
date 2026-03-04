package com.transferhub.pricing

import com.transferhub.pricing.config.AppConfig
import com.transferhub.pricing.config.RedisClientFactory
import com.transferhub.pricing.grpc.GrpcServer
import com.transferhub.pricing.plugins.*
import com.transferhub.pricing.service.DEFAULT_CORRIDORS
import com.transferhub.pricing.service.PricingService
import com.transferhub.pricing.service.QuoteCacheService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.transferhub.pricing.Application")

fun main() {
    val config = AppConfig.load()

    val redisClientFactory = RedisClientFactory(config.redis)
    redisClientFactory.connect()

    val quoteCacheService = QuoteCacheService(redisClientFactory, config.redis.quoteTtlSeconds)
    val pricingService = PricingService(DEFAULT_CORRIDORS, quoteCacheService, config.redis.quoteTtlSeconds)

    val grpcServer = GrpcServer(config, pricingService)

    embeddedServer(
        factory = Netty,
        port = config.server.port,
        module = { module(config, grpcServer, pricingService, redisClientFactory) }
    ).start(wait = true)
}

fun Application.module(
    config: AppConfig,
    grpcServer: GrpcServer,
    pricingService: PricingService? = null,
    redisClientFactory: RedisClientFactory? = null,
) {
    configureSerialization()
    configureMonitoring()
    configureErrorHandling()
    configureRouting(pricingService)

    environment.monitor.subscribe(ApplicationStarted) {
        logger.info("Starting gRPC server on port ${config.grpc.port}")
        grpcServer.start()
    }

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Shutting down gRPC server")
        grpcServer.stop()
        redisClientFactory?.close()
    }
}
