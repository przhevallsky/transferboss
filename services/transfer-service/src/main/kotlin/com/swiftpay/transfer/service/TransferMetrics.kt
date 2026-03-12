package com.swiftpay.transfer.service

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class TransferMetrics(private val meterRegistry: MeterRegistry) {

    fun recordTransferCreated(corridor: String, deliveryMethod: String) {
        meterRegistry.counter(
            "transfers_created_total",
            "corridor", corridor,
            "delivery_method", deliveryMethod
        ).increment()
    }

    fun recordTransferCompleted(corridor: String) {
        meterRegistry.counter("transfers_completed_total", "corridor", corridor).increment()
    }

    fun recordTransferFailed(corridor: String, reason: String) {
        meterRegistry.counter(
            "transfers_failed_total",
            "corridor", corridor,
            "reason", reason
        ).increment()
    }

    fun recordTransferCompletionTime(durationSeconds: Double, corridor: String) {
        meterRegistry.timer("transfer_completion_time_seconds", "corridor", corridor)
            .record(Duration.ofMillis((durationSeconds * 1000).toLong()))
    }

    fun recordQuoteCreated() {
        meterRegistry.counter("quotes_created_total").increment()
    }
}
