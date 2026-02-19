-- Copy of outbox table structure from Transfer Service (V003__create_outbox_table.sql)
-- Used ONLY for Outbox Service integration tests.
-- Canonical source of truth — Transfer Service migration.

CREATE TABLE outbox (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(50)     NOT NULL,
    entity_id       UUID            NOT NULL,
    event_type      VARCHAR(100)    NOT NULL,
    payload         JSONB           NOT NULL,
    status          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    kafka_topic     VARCHAR(200),
    kafka_offset    BIGINT,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_outbox_pending
    ON outbox (created_at ASC)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_processed
    ON outbox (processed_at)
    WHERE status = 'SENT';
