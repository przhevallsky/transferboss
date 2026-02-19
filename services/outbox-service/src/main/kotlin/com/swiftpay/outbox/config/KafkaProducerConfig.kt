package com.swiftpay.outbox.config

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.core.ProducerFactory

/**
 * Kafka Producer configuration.
 *
 * Core settings come from application.yml (spring.kafka.producer.*).
 * Here we configure additional KafkaTemplate behavior.
 *
 * Key producer parameters (from application.yml):
 * - acks=all: message acknowledged by all ISR (maximum durability)
 * - enable.idempotence=true: protection against duplication on retry
 * - retries=3: number of retry attempts on transient errors
 */
@Configuration
class KafkaProducerConfig {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun kafkaTemplate(producerFactory: ProducerFactory<String, String>): KafkaTemplate<String, String> {
        logger.info("Initializing Kafka producer template")
        val template = KafkaTemplate(producerFactory)
        return template
    }
}
