package com.swiftpay.transfer.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Transfer status cache using Caffeine with bounded size and TTL.
 *
 * Previously used an unbounded ConcurrentHashMap which caused a memory leak
 * under high throughput — entries were never evicted, leading to OOM.
 *
 * Fix applied in Sprint 6 Block 9:
 * - Replaced ConcurrentHashMap with Caffeine cache
 * - maxSize = 10,000 entries
 * - TTL = 5 minutes (expire after write)
 * - Micrometer metrics for monitoring cache performance
 *
 * @see docs/investigations/memory-leak-transfer-service.md
 */
@Component
class TransferStatusCache(meterRegistry: MeterRegistry) {

    private val log = LoggerFactory.getLogger(TransferStatusCache::class.java)

    private val cache: Cache<UUID, String> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .recordStats()
        .build()

    init {
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "transfer-status-cache")
        log.info("TransferStatusCache initialized: maxSize=10000, TTL=5min (Caffeine)")
    }

    fun put(transferId: UUID, status: String) {
        cache.put(transferId, status)
    }

    fun get(transferId: UUID): String? {
        return cache.getIfPresent(transferId)
    }

    fun evict(transferId: UUID) {
        cache.invalidate(transferId)
    }

    fun size(): Long {
        return cache.estimatedSize()
    }
}
