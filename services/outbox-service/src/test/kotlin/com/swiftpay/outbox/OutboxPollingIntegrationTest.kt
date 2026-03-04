package com.swiftpay.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.outbox.polling.OutboxPollingScheduler
import com.swiftpay.outbox.repository.OutboxEventRepository
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID

class OutboxPollingIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var scheduler: OutboxPollingScheduler

    @Autowired
    lateinit var repository: OutboxEventRepository

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var embeddedKafka: EmbeddedKafkaBroker

    private val objectMapper = ObjectMapper()

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.execute("DELETE FROM outbox")
    }

    private fun createConsumer(groupId: String): Consumer<String, String> {
        val props = KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka)
        props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
        props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "latest"
        val consumer = DefaultKafkaConsumerFactory<String, String>(props).createConsumer()
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "transfer.events")
        return consumer
    }

    @Test
    fun `poll publishes PENDING events to Kafka and marks them SENT`() {
        val entityId = UUID.randomUUID()
        val payload = """{"transfer_id":"$entityId","status":"CREATED"}"""

        // Subscribe consumer BEFORE inserting data so it captures new messages
        val consumer = createConsumer("test-single-${UUID.randomUUID()}")

        jdbcTemplate.update(
            """
            INSERT INTO outbox (id, entity_type, entity_id, event_type, payload, status)
            VALUES (?, 'TRANSFER', ?, 'TRANSFER_CREATED', ?::jsonb, 'PENDING')
            """,
            UUID.randomUUID(), entityId, payload
        )

        // Trigger poll manually (auto-polling disabled in test profile)
        scheduler.poll()

        // Verify DB status updated to SENT
        val events = repository.findAll()
        events shouldHaveSize 1
        val event = events[0]
        event.status shouldBe "SENT"
        event.processedAt shouldNotBe null
        event.kafkaTopic shouldBe "transfer.events"
        event.kafkaOffset shouldNotBe null

        // Verify Kafka message received
        val records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
        val messages = records.records("transfer.events").toList()
            .filter { it.key() == entityId.toString() }
        messages shouldHaveSize 1
        objectMapper.readTree(messages[0].value()) shouldBe objectMapper.readTree(payload)

        consumer.close()
    }

    @Test
    fun `poll with no PENDING events does nothing`() {
        scheduler.poll()

        val events = repository.findAll()
        events shouldHaveSize 0
    }

    @Test
    fun `poll processes multiple events grouped by entityId`() {
        val entityId1 = UUID.randomUUID()
        val entityId2 = UUID.randomUUID()
        val expectedKeys = setOf(entityId1.toString(), entityId2.toString())

        val consumer = createConsumer("test-multi-${UUID.randomUUID()}")

        listOf(
            Triple(entityId1, "TRANSFER_CREATED", """{"id":"$entityId1","event":"created"}"""),
            Triple(entityId1, "TRANSFER_STATUS_CHANGED", """{"id":"$entityId1","event":"changed"}"""),
            Triple(entityId2, "TRANSFER_CREATED", """{"id":"$entityId2","event":"created"}"""),
        ).forEach { (entId, evtType, payload) ->
            jdbcTemplate.update(
                """
                INSERT INTO outbox (id, entity_type, entity_id, event_type, payload, status)
                VALUES (?, 'TRANSFER', ?, ?, ?::jsonb, 'PENDING')
                """,
                UUID.randomUUID(), entId, evtType, payload
            )
        }

        scheduler.poll()

        val events = repository.findAll()
        events shouldHaveSize 3
        events.forEach { it.status shouldBe "SENT" }

        // Verify Kafka — filter by expected keys to avoid cross-test contamination
        val records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
        val messages = records.records("transfer.events").toList()
            .filter { it.key() in expectedKeys }
        messages shouldHaveSize 3

        consumer.close()
    }
}
