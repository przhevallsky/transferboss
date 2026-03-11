-- V007: Add target_topic column to outbox table for per-event topic routing.
-- The Outbox Publisher will use this column to determine which Kafka topic
-- each event should be published to (saga choreography support).
-- NULL means use the default topic (backward compatibility).

ALTER TABLE outbox ADD COLUMN target_topic VARCHAR(200);
