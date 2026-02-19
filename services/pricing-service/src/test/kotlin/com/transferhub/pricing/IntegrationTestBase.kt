package com.transferhub.pricing

import com.transferhub.pricing.config.AppConfig
import com.transferhub.pricing.config.GrpcConfig
import com.transferhub.pricing.config.MongoDbConfig
import com.transferhub.pricing.config.RedisConfig
import com.transferhub.pricing.config.ServerConfig
import com.transferhub.pricing.plugins.*
import io.ktor.server.testing.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
abstract class IntegrationTestBase {

    companion object {
        @Container
        @JvmStatic
        val mongodb: MongoDBContainer = MongoDBContainer("mongo:7.0")
            .withReuse(true)

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7.2-alpine")
            .withExposedPorts(6379)
            .withReuse(true)
    }

    protected fun testConfig(): AppConfig = AppConfig(
        server = ServerConfig(port = 0),
        grpc = GrpcConfig(port = 0),
        redis = RedisConfig(
            host = redis.host,
            port = redis.getMappedPort(6379),
        ),
        mongodb = MongoDbConfig(
            uri = mongodb.connectionString,
            database = "pricing-test",
        ),
    )

    protected fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            configureSerialization()
            configureMonitoring()
            configureErrorHandling()
            configureRouting()
        }
        block()
    }
}
