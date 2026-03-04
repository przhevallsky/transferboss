package com.swiftpay.transfer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.domain.model.ConsumedEvent
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.repository.ConsumedEventRepository
import com.swiftpay.transfer.repository.TransferRepository
import com.swiftpay.transfer.service.TransferCacheService
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Component
class PaymentEventConsumer(
    private val transferRepository: TransferRepository,
    private val transferCacheService: TransferCacheService,
    private val consumedEventRepository: ConsumedEventRepository,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(PaymentEventConsumer::class.java)

    @KafkaListener(topics = ["payments.payment.captured", "payments.payment.failed"], groupId = "transfer-service")
    fun consume(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) receivedTopic: String) {
        val event = try {
            objectMapper.readValue(message, PaymentEvent::class.java)
        } catch (e: Exception) {
            log.error("Failed to deserialize payment event: {}", e.message)
            return
        }

        try {
            MDC.put("traceId", event.eventId)

            log.info("Received payment event: eventId={}, transferId={}, type={}",
                event.eventId, event.transferId, event.eventType)

            val newStatus = when (event.eventType) {
                "PAYMENT_CAPTURED" -> TransferStatus.PaymentCaptured
                "PAYMENT_FAILED" -> TransferStatus.PaymentFailed
                else -> {
                    log.warn("Unknown payment event type: {}", event.eventType)
                    return
                }
            }

            val transferId = try {
                UUID.fromString(event.transferId)
            } catch (e: IllegalArgumentException) {
                log.error("Invalid transferId format: {}", event.transferId)
                return
            }

            val updated = transactionTemplate.execute {
                if (consumedEventRepository.existsByEventId(event.eventId)) {
                    log.info("Duplicate event {}, skipping", event.eventId)
                    return@execute false
                }

                val transfer = transferRepository.findTransferById(transferId)
                if (transfer == null) {
                    log.error("Transfer not found: {}", transferId)
                    return@execute false
                }

                transfer.transitionTo(newStatus, event.reason)
                if (event.paymentId != null) {
                    transfer.paymentId = UUID.fromString(event.paymentId)
                }
                transferRepository.save(transfer)
                log.info("Transfer {} status updated to {}", transferId, newStatus.value)

                consumedEventRepository.save(
                    ConsumedEvent(
                        eventId = event.eventId,
                        consumerGroup = "transfer-service",
                        topic = receivedTopic
                    )
                )
                true
            } ?: false

            if (updated) {
                transferCacheService.evict(transferId)
            }
        } finally {
            MDC.clear()
        }
    }
}
