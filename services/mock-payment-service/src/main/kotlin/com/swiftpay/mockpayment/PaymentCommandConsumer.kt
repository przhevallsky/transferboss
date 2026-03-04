package com.swiftpay.mockpayment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

@Component
class PaymentCommandConsumer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PaymentCommandConsumer::class.java)

    companion object {
        private val AMOUNT_LIMIT = BigDecimal("10000")
        private const val TOPIC_CAPTURED = "payments.payment.captured"
        private const val TOPIC_FAILED = "payments.payment.failed"
    }

    @KafkaListener(topics = ["transfers.payment.requested"], groupId = "mock-payment-service")
    fun consume(message: String) {
        val request = try {
            objectMapper.readValue(message, PaymentRequestEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize payment request: {}", e.message)
            return
        }

        log.info("Processing payment request: transferId={}, amount={} {}",
            request.transferId, request.sendAmount, request.sendCurrency)

        // Simulate processing delay
        Thread.sleep(Random.nextLong(100, 500))

        val amount = BigDecimal(request.sendAmount)

        if (amount <= AMOUNT_LIMIT) {
            val paymentId = UUID.randomUUID().toString()
            val payload = objectMapper.writeValueAsString(mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_type" to "PAYMENT_CAPTURED",
                "transfer_id" to request.transferId,
                "payment_id" to paymentId,
                "captured_amount" to request.sendAmount,
                "captured_currency" to request.sendCurrency,
                "captured_at" to Instant.now().toString()
            ))
            kafkaTemplate.send(TOPIC_CAPTURED, request.transferId, payload).get()
            log.info("Payment captured: transferId={}, paymentId={}", request.transferId, paymentId)
        } else {
            val payload = objectMapper.writeValueAsString(mapOf(
                "event_id" to UUID.randomUUID().toString(),
                "event_type" to "PAYMENT_FAILED",
                "transfer_id" to request.transferId,
                "reason" to "AMOUNT_LIMIT_EXCEEDED",
                "timestamp" to Instant.now().toString()
            ))
            kafkaTemplate.send(TOPIC_FAILED, request.transferId, payload).get()
            log.info("Payment failed: transferId={}, reason=AMOUNT_LIMIT_EXCEEDED", request.transferId)
        }
    }
}
