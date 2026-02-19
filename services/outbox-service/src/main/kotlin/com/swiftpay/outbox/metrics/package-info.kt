/**
 * Custom Outbox Service metrics:
 * - outbox.poll.duration: time for one polling cycle
 * - outbox.publish.duration: time to send a batch to Kafka
 * - outbox.events.published: counter of published events
 * - outbox.events.failed: counter of failed sends
 */
package com.swiftpay.outbox.metrics
