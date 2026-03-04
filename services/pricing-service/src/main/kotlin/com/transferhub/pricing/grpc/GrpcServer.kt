package com.transferhub.pricing.grpc

import com.transferhub.pricing.config.AppConfig
import com.transferhub.pricing.service.PricingService
import io.grpc.Server
import io.grpc.ServerBuilder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

class GrpcServer(private val config: AppConfig, private val pricingService: PricingService) {

    private val logger = LoggerFactory.getLogger(GrpcServer::class.java)

    private val pricingGrpcService = PricingGrpcService(pricingService)

    private val server: Server = ServerBuilder
        .forPort(config.grpc.port)
        .addService(pricingGrpcService)
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
