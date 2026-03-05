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
class PayoutEventConsumer(
    private val transferRepository: TransferRepository,
    private val transferCacheService: TransferCacheService,
    private val consumedEventRepository: ConsumedEventRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(PayoutEventConsumer::class.java)

    private val dltCounter: Counter = Counter.builder("kafka.dlt.messages.total")
        .tag("topic", "payouts.payout")
        .register(meterRegistry)

    @RetryableTopic(
        attempts = "4",
        backoff = Backoff(delay = 30_000, multiplier = 10.0, maxDelay = 3_600_000),
        topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
        dltStrategy = DltStrategy.FAIL_ON_ERROR,
        exclude = [NonRetriableConsumerException::class]
    )
    @KafkaListener(topics = ["payouts.payout.completed", "payouts.payout.failed"], groupId = "transfer-service")
    fun consume(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) receivedTopic: String) {
        val event = try {
            objectMapper.readValue(message, PayoutEvent::class.java)
        } catch (e: Exception) {
            throw NonRetriableConsumerException("Failed to deserialize payout event", e)
        }

        try {
            MDC.put("traceId", event.eventId)

            log.info("Received payout event: eventId={}, transferId={}, type={}",
                event.eventId, event.transferId, event.eventType)

            val newStatus = when (event.eventType) {
                "PAYOUT_COMPLETED" -> TransferStatus.Completed
                "PAYOUT_FAILED" -> TransferStatus.Failed
                else -> throw NonRetriableConsumerException("Unknown payout event type: ${event.eventType}")
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
                if (event.payoutId != null) {
                    transfer.payoutId = UUID.fromString(event.payoutId)
                }
                transferRepository.save(transfer)
                log.info("Transfer {} status updated to {}", transferId, newStatus.value)

                if (newStatus == TransferStatus.Failed) {
                    val refundPayload = objectMapper.writeValueAsString(mapOf(
                        "event_id" to UUID.randomUUID().toString(),
                        "event_type" to "REFUND_REQUESTED",
                        "transfer_id" to transfer.id.toString(),
                        "payment_id" to transfer.paymentId.toString(),
                        "refund_amount" to transfer.sendAmount.toPlainString(),
                        "refund_currency" to transfer.sendCurrency,
                        "idempotency_key" to transfer.idempotencyKey.toString()
                    ))
                    outboxEventRepository.save(OutboxEvent(
                        entityId = transfer.id,
                        entityType = "TRANSFER",
                        eventType = OutboxEventType.REFUND_REQUESTED,
                        payload = refundPayload,
                        status = OutboxEventStatus.PENDING,
                        targetTopic = "transfers.payment.refund.requested"
                    ))
                    transfer.transitionTo(TransferStatus.RefundPending)
                    transferRepository.save(transfer)
                    log.info("Transfer {} transitioned to REFUND_PENDING, refund outbox event saved", transferId)
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
    fun handleDlt(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
        dltCounter.increment()
        log.error("Payout event sent to DLT: topic={}, message={}", topic, message)
    }
}
