package com.transferhub.pricing.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.transferhub.pricing.ErrorHandling")

@Serializable
data class ProblemDetail(
    val type: String = "about:blank",
    val title: String,
    val status: Int,
    val detail: String? = null,
    val instance: String? = null,
)

fun Application.configureErrorHandling() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(
                HttpStatusCode.NotFound,
                ProblemDetail(
                    title = "Not Found",
                    status = 404,
                    detail = "Requested resource not found",
                    instance = call.request.local.uri,
                )
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            logger.warn("Validation error: ${cause.message}")
            call.respond(
                HttpStatusCode.BadRequest,
                ProblemDetail(
                    title = "Bad Request",
                    status = 400,
                    detail = cause.message,
                    instance = call.request.local.uri,
                )
            )
        }

        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ProblemDetail(
                    title = "Internal Server Error",
                    status = 500,
                    detail = "An unexpected error occurred",
                    instance = call.request.local.uri,
                )
            )
        }
    }
}
