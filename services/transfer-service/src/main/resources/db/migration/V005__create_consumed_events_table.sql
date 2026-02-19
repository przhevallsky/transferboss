-- Consumed events for idempotent Kafka consumer processing
CREATE TABLE consumed_events (
    event_id            VARCHAR(128)    PRIMARY KEY,
    consumer_group      VARCHAR(128)    NOT NULL,
    topic               VARCHAR(256)    NOT NULL,
    processed_at        TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_consumed_events_processed_at ON consumed_events (processed_at);
