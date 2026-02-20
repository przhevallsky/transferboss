package com.swiftpay.transfer.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.swiftpay.transfer.api.dto.response.TransferResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class TransferCacheService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(TransferCacheService::class.java)

    companion object {
        private const val KEY_PREFIX = "transfer:status:"
        private val CACHE_TTL = Duration.ofSeconds(30)
    }

    fun getCached(transferId: UUID): TransferResponse? {
        return try {
            val key = "$KEY_PREFIX$transferId"
            val json = redisTemplate.opsForValue().get(key)
            if (json != null) {
                log.debug("Cache HIT: transferId={}", transferId)
                objectMapper.readValue(json, TransferResponse::class.java)
            } else {
                log.debug("Cache MISS: transferId={}", transferId)
                null
            }
        } catch (e: Exception) {
            log.warn("Redis GET failed for transferId={}: {}", transferId, e.message)
            null
        }
    }

    fun put(transferId: UUID, response: TransferResponse) {
        try {
            val key = "$KEY_PREFIX$transferId"
            val json = objectMapper.writeValueAsString(response)
            redisTemplate.opsForValue().set(key, json, CACHE_TTL)
            log.debug("Cache PUT: transferId={}, ttl={}s", transferId, CACHE_TTL.seconds)
        } catch (e: Exception) {
            log.warn("Redis SET failed for transferId={}: {}", transferId, e.message)
        }
    }

    fun evict(transferId: UUID) {
        try {
            val key = "$KEY_PREFIX$transferId"
            val deleted = redisTemplate.delete(key)
            log.debug("Cache EVICT: transferId={}, deleted={}", transferId, deleted)
        } catch (e: Exception) {
            log.warn("Redis DELETE failed for transferId={}: {}", transferId, e.message)
        }
    }
}
