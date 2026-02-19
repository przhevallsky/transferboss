package com.transferhub.pricing.grpc

import com.transferhub.pricing.config.AppConfig
import io.grpc.Server
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class GrpcServer(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger(GrpcServer::class.java)

    private val pricingService = PricingGrpcService()

    private val server: Server = ServerBuilder
        .forPort(config.grpc.port)
        .addService(pricingService)
        .build()

    fun start() {
        server.start()
        logger.info("gRPC server started on port ${config.grpc.port}")
    }

    fun stop() {
        logger.info("Shutting down gRPC server...")
        server.shutdown()
        if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
            logger.warn("gRPC server did not terminate gracefully, forcing shutdown")
            server.shutdownNow()
        }
        logger.info("gRPC server stopped")
    }
}
