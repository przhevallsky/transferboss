package com.swiftpay.llm.controller

import com.swiftpay.llm.model.dto.AskRequest
import com.swiftpay.llm.model.dto.AssistantResponse
import com.swiftpay.llm.service.RagService
import jakarta.validation.Valid
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.runBlocking
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux

@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController(
    private val ragService: RagService
) {

    @PostMapping("/ask")
    fun ask(@Valid @RequestBody request: AskRequest): AssistantResponse {
        return runBlocking {
            ragService.ask(request.question, request.category)
        }
    }

    @GetMapping("/ask/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun askStream(@RequestParam question: String): Flux<ServerSentEvent<String>> {
        return runBlocking {
            ragService.askStream(question)
                .map { chunk ->
                    ServerSentEvent.builder(chunk).build()
                }
                .asFlux()
        }
    }
}
