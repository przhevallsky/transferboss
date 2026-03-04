package com.swiftpay.outbox.polling

import com.swiftpay.outbox.config.OutboxProperties
import com.swiftpay.outbox.publisher.OutboxPublisher
import com.swiftpay.outbox.repository.OutboxEventRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class OutboxPollingScheduler(
    private val repository: OutboxEventRepository,
    private val publisher: OutboxPublisher,
    private val properties: OutboxProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedRateString = "\${outbox.polling.interval-ms}",
        initialDelayString = "\${outbox.polling.initial-delay-ms:\${outbox.polling.interval-ms}}"
    )
    @Transactional
    fun poll() {
        try {
            MDC.put("traceId", UUID.randomUUID().toString())

            val events = repository.findPendingForUpdate(properties.batchSize)
            if (events.isEmpty()) return

            logger.info("Polled {} pending outbox events", events.size)
            publisher.publish(events)
        } catch (e: Exception) {
            logger.error("Outbox polling failed: {}", e.message, e)
        } finally {
            MDC.clear()
        }
    }
}
