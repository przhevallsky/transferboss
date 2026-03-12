package com.swiftpay.transfer

import com.swiftpay.transfer.config.TestUnleashConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestUnleashConfig::class)
@EmbeddedKafka(partitions = 1, topics = ["payments.payment.captured", "payments.payment.failed", "payments.payment.refunded", "payouts.payout.completed", "payouts.payout.failed", "notification.delivery", "notification.delivery.retry", "notification.delivery.dlt"])
abstract class IntegrationTestBase {

    companion object {

        private val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("transfer_db")
            withUsername("test")
            withPassword("test")
            start()
        }

        private val redis = GenericContainer("redis:7-alpine").apply {
            withExposedPorts(6379)
            start()
        }

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }

            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.firstMappedPort }

            registry.add("spring.cloud.consul.enabled") { "false" }
        }
    }
}
