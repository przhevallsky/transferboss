-- Transactional outbox for reliable event publishing
CREATE TABLE outbox (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type      VARCHAR(128)    NOT NULL,
    aggregate_id        VARCHAR(128)    NOT NULL,
    event_type          VARCHAR(128)    NOT NULL,
    payload             JSONB           NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate   ON outbox (aggregate_type, aggregate_id);
