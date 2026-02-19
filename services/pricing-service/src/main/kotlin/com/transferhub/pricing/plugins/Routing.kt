package com.transferhub.pricing.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

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
                call.respond(
                    HttpStatusCode.OK,
                    mapOf(
                        "corridors" to listOf(
                            mapOf(
                                "source_country" to "GB",
                                "destination_country" to "PL",
                                "send_currency" to "GBP",
                                "receive_currency" to "PLN",
                                "delivery_methods" to listOf("BANK_TRANSFER"),
                                "active" to true
                            )
                        )
                    )
                )
            }
        }
    }
}
