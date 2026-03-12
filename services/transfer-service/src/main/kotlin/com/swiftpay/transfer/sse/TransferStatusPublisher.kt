package com.swiftpay.transfer.sse

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class TransferStatusPublisher(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(TransferStatusPublisher::class.java)

    fun publishStatusChange(transferId: UUID, newStatus: String, previousStatus: String) {
        try {
            val channel = "transfer-status:$transferId"
            val payload = objectMapper.writeValueAsString(
                mapOf(
                    "transferId" to transferId.toString(),
                    "status" to newStatus,
                    "previousStatus" to previousStatus,
                    "timestamp" to Instant.now().toString()
                )
            )
            redisTemplate.convertAndSend(channel, payload)
            log.debug("Published status change to {}: {} -> {}", channel, previousStatus, newStatus)
        } catch (e: Exception) {
            log.warn("Failed to publish status change for transfer {}: {}", transferId, e.message)
        }
    }
}
