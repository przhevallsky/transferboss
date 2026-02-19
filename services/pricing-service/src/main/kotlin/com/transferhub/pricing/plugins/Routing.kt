package com.transferhub.pricing.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*

fun Application.configureRouting() {
    routing {
        get("/health/live") {
            call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
        }

        get("/health/ready") {
            call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
        }

        get("/metrics") {
            call.respondText(
                appMeterRegistry.scrape(),
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
                HttpStatusCode.OK
            )
        }

        route("/api/v1") {
            get("/corridors") {
                val body = buildJsonObject {
                    putJsonArray("corridors") {
                        addJsonObject {
                            put("source_country", "GB")
                            put("destination_country", "PL")
                            put("send_currency", "GBP")
                            put("receive_currency", "PLN")
                            putJsonArray("delivery_methods") { add("BANK_TRANSFER") }
                            put("active", true)
                        }
                    }
                }
                call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            }
        }
    }
}
