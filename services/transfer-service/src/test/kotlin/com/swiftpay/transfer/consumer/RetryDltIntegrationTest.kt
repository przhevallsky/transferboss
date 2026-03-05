package com.swiftpay.transfer.consumer

import com.swiftpay.transfer.IntegrationTestBase
import com.swiftpay.transfer.domain.model.Transfer
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.domain.vo.DeliveryMethod
import com.swiftpay.transfer.repository.TransferRepository
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class RetryDltIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, String>

    @Autowired
    private lateinit var transferRepository: TransferRepository

    @Autowired
    private lateinit var meterRegistry: MeterRegistry

    @Autowired
    private lateinit var embeddedKafka: EmbeddedKafkaBroker

    private val senderId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val recipientId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `non-retriable error should route directly to DLT`() {
        // Send malformed JSON — NonRetriableConsumerException skips retry, goes straight to DLT
        val dltTopic = "payments.payment.captured-dlt"

        val consumer = createDltConsumer(dltTopic)
        consumer.subscribe(listOf(dltTopic))

        kafkaTemplate.send("payments.payment.captured", "bad-key", "not valid json {{{").get(5, TimeUnit.SECONDS)

        val records = pollUntilRecord(consumer, timeoutMs = 30_000)
        assertTrue(records.count() > 0, "Expected at least one message in DLT")

        val dltMessage = records.first()
        assertEquals("not valid json {{{", dltMessage.value())

        consumer.close()
    }

    @Test
    fun `non-existent transfer should retry and eventually reach DLT`() {
        // TransientConsumerException: transfer not found → retried 3 times → DLT
        val nonExistentTransferId = UUID.randomUUID()
        val dltTopic = "payments.payment.captured-dlt"

        val consumer = createDltConsumer(dltTopic)
        consumer.subscribe(listOf(dltTopic))

        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "$nonExistentTransferId",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payments.payment.captured", nonExistentTransferId.toString(), event).get(5, TimeUnit.SECONDS)

        val records = pollUntilRecord(consumer, timeoutMs = 30_000)
        assertTrue(records.count() > 0, "Expected message in DLT after retry exhaustion")

        consumer.close()
    }

    @Test
    fun `valid event should be processed without reaching DLT`() {
        val transfer = createTransferWithStatus(TransferStatus.PaymentPending)

        val event = """
            {
                "event_id": "${UUID.randomUUID()}",
                "transfer_id": "${transfer.id}",
                "event_type": "PAYMENT_CAPTURED",
                "payment_id": "${UUID.randomUUID()}",
                "timestamp": "${Instant.now()}"
            }
        """.trimIndent()

        kafkaTemplate.send("payments.payment.captured", transfer.id.toString(), event).get(5, TimeUnit.SECONDS)

        // Wait for successful processing
        awaitCondition {
            val updated = transferRepository.findTransferById(transfer.id)
            updated?.status == TransferStatus.PayoutPending
        }

        val updated = transferRepository.findTransferById(transfer.id)!!
        assertEquals(TransferStatus.PayoutPending, updated.status)
    }

    private fun createDltConsumer(topic: String): KafkaConsumer<String, String> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to embeddedKafka.brokersAsString,
            ConsumerConfig.GROUP_ID_CONFIG to "test-dlt-consumer-${UUID.randomUUID()}",
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java.name
        )
        return KafkaConsumer(props)
    }

    private fun pollUntilRecord(
        consumer: KafkaConsumer<String, String>,
        timeoutMs: Long = 15_000
    ): ConsumerRecords<String, String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val records = consumer.poll(Duration.ofMillis(500))
            if (records.count() > 0) return records
        }
        throw AssertionError("No records received within ${timeoutMs}ms")
    }

    private fun createTransferWithStatus(status: TransferStatus): Transfer {
        val transfer = Transfer(
            idempotencyKey = UUID.randomUUID(),
            senderId = senderId,
            quoteId = UUID.randomUUID(),
            sendAmount = BigDecimal("200.00"),
            sendCurrency = "USD",
            receiveAmount = BigDecimal("11000.00"),
            receiveCurrency = "PHP",
            exchangeRate = BigDecimal("55.000000"),
            feeAmount = BigDecimal("5.00"),
            feeCurrency = "USD",
            sourceCountry = "US",
            destCountry = "PH",
            deliveryMethod = DeliveryMethod.BANK_DEPOSIT,
            recipientId = recipientId,
            status = status
        )
        return transferRepository.save(transfer)
    }

    private fun awaitCondition(timeoutMs: Long = 10_000, intervalMs: Long = 200, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        fail<Unit>("Condition not met within ${timeoutMs}ms")
    }
}
