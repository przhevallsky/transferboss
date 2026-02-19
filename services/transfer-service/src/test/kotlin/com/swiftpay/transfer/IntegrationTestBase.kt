package com.swiftpay.transfer

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
abstract class IntegrationTestBase {

    companion object {

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("transfer_db")
                .withUsername("test")
                .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)

            // Disable Redis health check — no Redis container in base tests
            registry.add("management.health.redis.enabled") { "false" }

            // Disable Redis auto-configuration in tests
            registry.add("spring.data.redis.host") { "localhost" }
            registry.add("spring.data.redis.port") { "16379" }

            // Disable Kafka in base integration tests
            registry.add("spring.kafka.bootstrap-servers") { "localhost:19092" }

            // Disable Consul
            registry.add("spring.cloud.consul.enabled") { "false" }
        }
    }
}
