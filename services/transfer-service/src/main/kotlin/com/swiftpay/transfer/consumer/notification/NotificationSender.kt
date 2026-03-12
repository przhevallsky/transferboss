package com.swiftpay.transfer.consumer.notification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

interface NotificationSender {
    fun send(event: NotificationEvent)
}

@Component
class LoggingNotificationSender : NotificationSender {

    private val log = LoggerFactory.getLogger(LoggingNotificationSender::class.java)

    override fun send(event: NotificationEvent) {
        log.info(
            "Notification delivered: transferId={}, eventType={}, channels={}",
            event.transferId, event.eventType, event.channels
        )
    }
}
