package com.transferhub.pricing.routes

import com.transferhub.pricing.api.dto.QuoteRequest
import com.transferhub.pricing.api.dto.QuoteRestResponse
import com.transferhub.pricing.model.Quote
import com.transferhub.pricing.plugins.ProblemDetail
import com.transferhub.pricing.service.PricingService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant

fun Route.quoteRoutes(pricingService: PricingService) {
    route("/api/v1") {
        get("/quotes") {
            val sourceCountry = call.request.queryParameters["source_country"]
            val destinationCountry = call.request.queryParameters["destination_country"]
            val sendCurrency = call.request.queryParameters["send_currency"]
            val receiveCurrency = call.request.queryParameters["receive_currency"]
            val sendAmount = call.request.queryParameters["send_amount"]
            val deliveryMethod = call.request.queryParameters["delivery_method"]
            val senderId = call.request.queryParameters["sender_id"]

            val missing = buildList {
                if (sourceCountry.isNullOrBlank()) add("source_country")
                if (destinationCountry.isNullOrBlank()) add("destination_country")
                if (sendCurrency.isNullOrBlank()) add("send_currency")
                if (receiveCurrency.isNullOrBlank()) add("receive_currency")
                if (sendAmount.isNullOrBlank()) add("send_amount")
                if (deliveryMethod.isNullOrBlank()) add("delivery_method")
                if (senderId.isNullOrBlank()) add("sender_id")
            }

            if (missing.isNotEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        title = "Bad Request",
                        status = 400,
                        detail = "Missing required parameters: ${missing.joinToString(", ")}",
                        instance = call.request.local.uri,
                    )
                )
                return@get
            }

            val request = QuoteRequest(
                sourceCountry = sourceCountry!!,
                destinationCountry = destinationCountry!!,
                sendCurrency = sendCurrency!!,
                receiveCurrency = receiveCurrency!!,
                sendAmount = sendAmount!!,
                deliveryMethod = deliveryMethod!!,
                senderId = senderId!!,
            )

            val quote = pricingService.calculateQuote(request)
            call.respond(HttpStatusCode.OK, quote.toRestResponse())
        }

        get("/quotes/{quoteId}/validate") {
            val quoteId = call.parameters["quoteId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ProblemDetail(
                        title = "Bad Request",
                        status = 400,
                        detail = "Missing quoteId path parameter",
                        instance = call.request.local.uri,
                    )
                )

            val result = pricingService.validateQuote(quoteId)

            if (result.isValid && result.quote != null) {
                call.respond(HttpStatusCode.OK, result.quote.toRestResponse())
            } else {
                call.respond(
                    HttpStatusCode.Gone,
                    ProblemDetail(
                        title = "Gone",
                        status = 410,
                        detail = "Quote $quoteId is expired or not found",
                        instance = call.request.local.uri,
                    )
                )
            }
        }
    }
}

private fun Quote.toRestResponse(): QuoteRestResponse = QuoteRestResponse(
    quoteId = quoteId,
    sendAmount = sendAmount.toPlainString(),
    receiveAmount = receiveAmount.toPlainString(),
    exchangeRate = exchangeRate.toPlainString(),
    feeAmount = feeAmount.toPlainString(),
    feeCurrency = feeCurrency,
    sendCurrency = sendCurrency,
    receiveCurrency = receiveCurrency,
    deliveryMethod = deliveryMethod,
    expiresAtEpochMs = expiresAt.toEpochMilli(),
    ttlSeconds = maxOf(0, (expiresAt.epochSecond - Instant.now().epochSecond).toInt()),
)
