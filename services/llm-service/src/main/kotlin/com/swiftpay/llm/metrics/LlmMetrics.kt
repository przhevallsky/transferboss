package com.swiftpay.llm.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Component
class LlmMetrics(private val meterRegistry: MeterRegistry) {

    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    init {
        meterRegistry.gauge("llm.cache.hit.ratio", this) { metrics ->
            val hits = metrics.cacheHits.get().toDouble()
            val total = hits + metrics.cacheMisses.get().toDouble()
            if (total == 0.0) 0.0 else hits / total
        }
    }

    fun incrementRequestCount(type: String, status: String) {
        Counter.builder("llm.requests.total")
            .tag("type", type)
            .tag("status", status)
            .register(meterRegistry)
            .increment()
    }

    fun startTimer(): Timer.Sample {
        return Timer.start(meterRegistry)
    }

    fun recordDuration(sample: Timer.Sample) {
        sample.stop(
            Timer.builder("llm.request.duration")
                .register(meterRegistry)
        )
    }

    fun incrementTokensUsed(promptTokens: Int, completionTokens: Int) {
        Counter.builder("llm.tokens.used")
            .tag("type", "prompt")
            .register(meterRegistry)
            .increment(promptTokens.toDouble())

        Counter.builder("llm.tokens.used")
            .tag("type", "completion")
            .register(meterRegistry)
            .increment(completionTokens.toDouble())
    }

    fun incrementVectorSearchCount() {
        Counter.builder("llm.vector.search.total")
            .register(meterRegistry)
            .increment()
    }

    fun recordCacheHit() {
        cacheHits.incrementAndGet()
    }

    fun recordCacheMiss() {
        cacheMisses.incrementAndGet()
    }
}
