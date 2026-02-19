/**
 * Polling logic: reading PENDING records from the outbox table via JdbcTemplate,
 * grouping by entity_id, coordinating with Kafka publisher.
 * The @Scheduled method lives here.
 */
package com.swiftpay.outbox.polling
