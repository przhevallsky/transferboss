package com.swiftpay.transfer.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.consumer.exception.NonRetriableConsumerException
import com.swiftpay.transfer.consumer.exception.TransientConsumerException
import com.swiftpay.transfer.domain.model.ConsumedEvent
import com.swiftpay.transfer.domain.model.OutboxEvent
import com.swiftpay.transfer.domain.model.TransferStatus
import com.swiftpay.transfer.domain.vo.OutboxEventStatus
import com.swiftpay.transfer.domain.vo.OutboxEventType
import com.swiftpay.transfer.repository.ConsumedEventRepository
import com.swiftpay.transfer.repository.OutboxEventRepository
import com.swiftpay.transfer.repository.TransferRepository
import com.swiftpay.transfer.service.TransferCacheService
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.kafka.retrytopic.DltStrategy
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.messaging.handler.annotation.Header
import org.springframework.retry.annotation.Backoff
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Component
class PaymentEventConsumer(
    private val transferRepository: TransferRepository,
    private val transferCacheService: TransferCacheService,
    private val consumedEventRepository: ConsumedEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(PaymentEventConsumer::class.java)

    private val dltCounter: Counter = Counter.builder("kafka.dlt.messages.total")
        .tag("topic", "payments.payment")
        .register(meterRegistry)

    @RetryableTopic(
        attempts = "4",
        backoff = Backoff(delayExpression = "\${kafka.retry.delay:30000}", multiplierExpression = "\${kafka.retry.multiplier:10.0}", maxDelayExpression = "\${kafka.retry.max-delay:3600000}"),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        exclude = [NonRetriableConsumerException::class]
    )
    @KafkaListener(topics = ["payments.payment.captured", "payments.payment.failed", "payments.payment.refunded"], groupId = "transfer-service")
    fun consume(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) receivedTopic: String) {
        val event = try {
            objectMapper.readValue(message, PaymentEvent::class.java)
        } catch (e: Exception) {
            throw NonRetriableConsumerException("Failed to deserialize payment event", e)
        }

        try {
            MDC.put("traceId", event.eventId)

            log.info("Received payment event: eventId={}, transferId={}, type={}",
                event.eventId, event.transferId, event.eventType)

            val newStatus = when (event.eventType) {
                "PAYMENT_CAPTURED" -> TransferStatus.PaymentCaptured
                "PAYMENT_FAILED" -> TransferStatus.PaymentFailed
                "PAYMENT_REFUNDED" -> TransferStatus.Refunded
                else -> throw NonRetriableConsumerException("Unknown payment event type: ${event.eventType}")
            }

            val transferId = try {
                UUID.fromString(event.transferId)
            } catch (e: IllegalArgumentException) {
                throw NonRetriableConsumerException("Invalid transferId format: ${event.transferId}", e)
            }

            val updated = transactionTemplate.execute {
                if (consumedEventRepository.existsByEventId(event.eventId)) {
                    log.info("Duplicate event {}, skipping", event.eventId)
                    return@execute false
                }

                val transfer = transferRepository.findTransferById(transferId)
                    ?: throw TransientConsumerException("Transfer not found: $transferId")

                transfer.transitionTo(newStatus, event.reason)
                if (event.paymentId != null) {
                    transfer.paymentId = try {
                        UUID.fromString(event.paymentId)
                    } catch (e: IllegalArgumentException) {
                        throw NonRetriableConsumerException("Invalid paymentId format: ${event.paymentId}", e)
                    }
                }
                transferRepository.save(transfer)
                log.info("Transfer {} status updated to {}", transferId, newStatus.value)

                if (newStatus == TransferStatus.PaymentCaptured) {
                    val payoutPayload = objectMapper.writeValueAsString(mapOf(
                        "event_id" to UUID.randomUUID().toString(),
                        "event_type" to "PAYOUT_REQUESTED",
                        "transfer_id" to transfer.id.toString(),
                        "recipient_id" to transfer.recipientId.toString(),
                        "receive_amount" to transfer.receiveAmount.toPlainString(),
                        "receive_currency" to transfer.receiveCurrency,
                        "delivery_method" to transfer.deliveryMethod.name,
                        "idempotency_key" to transfer.idempotencyKey.toString()
                    ))
                    outboxEventRepository.save(OutboxEvent(
                        entityId = transfer.id,
                        entityType = "TRANSFER",
                        eventType = OutboxEventType.PAYOUT_REQUESTED,
                        payload = payoutPayload,
                        status = OutboxEventStatus.PENDING,
                        targetTopic = "transfers.payout.requested"
                    ))
                    transfer.transitionTo(TransferStatus.PayoutPending)
                    transferRepository.save(transfer)
                    log.info("Transfer {} transitioned to PAYOUT_PENDING, outbox event saved", transferId)
                }

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

    @DltHandler
    fun handleDlt(
        message: String,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Header(KafkaHeaders.EXCEPTION_MESSAGE) exceptionMsg: String?
    ) {
        dltCounter.increment()
        log.error("Payment event sent to DLT: topic={}, exception={}, message={}", topic, exceptionMsg, message)
    }
}
