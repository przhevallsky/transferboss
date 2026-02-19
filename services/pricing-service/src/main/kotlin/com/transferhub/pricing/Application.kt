package com.transferhub.pricing

import com.transferhub.pricing.config.AppConfig
import com.transferhub.pricing.grpc.GrpcServer
import com.transferhub.pricing.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.transferhub.pricing.Application")

fun main() {
    val config = AppConfig.load()

    val grpcServer = GrpcServer(config)

    embeddedServer(
        factory = Netty,
        port = config.server.port,
        module = { module(config, grpcServer) }
    ).start(wait = true)
}

fun Application.module(config: AppConfig, grpcServer: GrpcServer) {
    configureSerialization()
    configureMonitoring()
    configureErrorHandling()
    configureRouting()

    environment.monitor.subscribe(ApplicationStarted) {
        logger.info("Starting gRPC server on port ${config.grpc.port}")
        grpcServer.start()
    }

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("Shutting down gRPC server")
        grpcServer.stop()
    }
}
