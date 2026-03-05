package com.swiftpay.mockpayout

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

@Component
class PayoutCommandConsumer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PayoutCommandConsumer::class.java)

    companion object {
        private const val TOPIC_COMPLETED = "payouts.payout.completed"
        private const val TOPIC_FAILED = "payouts.payout.failed"
    }

    @KafkaListener(topics = ["transfers.payout.requested"], groupId = "mock-payout-service")
    fun consume(message: String) {
        val request = try {
            objectMapper.readValue(message, PayoutRequestEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize payout request: {}", e.message)
            return
        }

        log.info("Processing payout request: transferId={}, amount={} {}",
            request.transferId, request.receiveAmount, request.receiveCurrency)

        // Simulate processing delay
        Thread.sleep(Random.nextLong(200, 800))

        if (request.receiveCurrency == "JPY") {
            val payload = objectMapper.writeValueAsString(mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_type" to "PAYOUT_FAILED",
                "transfer_id" to request.transferId,
                "reason" to "PARTNER_UNAVAILABLE",
                "timestamp" to Instant.now().toString()
            ))
            kafkaTemplate.send(TOPIC_FAILED, request.transferId, payload).get()
            log.info("Payout failed: transferId={}, reason=PARTNER_UNAVAILABLE", request.transferId)
        } else {
            val payoutId = UUID.randomUUID().toString()
            val payload = objectMapper.writeValueAsString(mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_type" to "PAYOUT_COMPLETED",
                "transfer_id" to request.transferId,
                "payout_id" to payoutId,
                "completed_at" to Instant.now().toString(),
                "reference_number" to "REF-${UUID.randomUUID().toString().take(8).uppercase()}"
            ))
            kafkaTemplate.send(TOPIC_COMPLETED, request.transferId, payload).get()
            log.info("Payout completed: transferId={}, payoutId={}", request.transferId, payoutId)
        }
    }
}
