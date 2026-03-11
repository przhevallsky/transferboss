package com.transferhub.pricing.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureLogging() {
    install(CallLogging) {
        level = Level.INFO
        mdc("traceId") { call ->
            call.request.headers["X-Trace-Id"] ?: UUID.randomUUID().toString()
        }
    }

    intercept(ApplicationCallPipeline.Plugins) {
        val traceId = org.slf4j.MDC.get("traceId") ?: UUID.randomUUID().toString()
        call.response.header("X-Trace-Id", traceId)
    }
}
