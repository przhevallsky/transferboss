package com.swiftpay.transfer.sse

import com.swiftpay.transfer.repository.TransferRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.ReactiveSubscription
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.time.Duration
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transfers")
class TransferSseController(
    private val transferRepository: TransferRepository,
    private val listenerContainer: ReactiveRedisMessageListenerContainer
) {

    private val log = LoggerFactory.getLogger(TransferSseController::class.java)

    @GetMapping("/{id}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamTransferEvents(@PathVariable id: UUID): Flux<ServerSentEvent<String>> {
        val transfer = transferRepository.findTransferById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found: $id")

        val initialEvent = Flux.just(
            ServerSentEvent.builder<String>()
                .event("status")
                .data("""{"transferId":"$id","status":"${transfer.status.value}","timestamp":"${Instant.now()}"}""")
                .build()
        )

        val redisEvents = listenerContainer
            .receive(ChannelTopic.of("transfer-status:$id"))
            .map { message: ReactiveSubscription.Message<String, String> ->
                ServerSentEvent.builder<String>()
                    .event("status")
                    .data(message.message)
                    .build()
            }

        val heartbeat = Flux.interval(Duration.ofSeconds(30))
            .map {
                ServerSentEvent.builder<String>()
                    .comment("heartbeat")
                    .build()
            }

        return initialEvent
            .concatWith(Flux.merge(redisEvents, heartbeat))
            .doOnSubscribe { log.info("SSE client connected for transfer {}", id) }
            .doFinally { log.info("SSE client disconnected for transfer {}", id) }
    }
}
