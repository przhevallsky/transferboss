package com.swiftpay.mockpayment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

@Component
class RefundCommandConsumer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(RefundCommandConsumer::class.java)

    companion object {
        private const val TOPIC_REFUNDED = "payments.payment.refunded"
    }

    @KafkaListener(topics = ["transfers.payment.refund.requested"], groupId = "mock-payment-service")
    fun consume(message: String) {
        val request = try {
            objectMapper.readValue(message, RefundRequestEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize refund request: {}", e.message)
            return
        }

        log.info("Processing refund request: transferId={}, paymentId={}, amount={} {}",
            request.transferId, request.paymentId, request.refundAmount, request.refundCurrency)

        // Simulate processing delay
        Thread.sleep(Random.nextLong(100, 500))

        val refundId = UUID.randomUUID().toString()
        val payload = objectMapper.writeValueAsString(mapOf(
            "event_id" to UUID.randomUUID().toString(),
            "event_type" to "PAYMENT_REFUNDED",
            "transfer_id" to request.transferId,
            "refund_id" to refundId,
            "refunded_amount" to request.refundAmount,
            "refunded_currency" to request.refundCurrency,
            "refunded_at" to Instant.now().toString()
        ))
        kafkaTemplate.send(TOPIC_REFUNDED, request.transferId, payload).get()
        log.info("Refund completed: transferId={}, refundId={}", request.transferId, refundId)
    }
}
