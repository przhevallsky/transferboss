CREATE TABLE idempotency_keys (
    key               UUID            PRIMARY KEY,
    transfer_id       UUID            NOT NULL REFERENCES transfers(id),
    response_status   INTEGER         NOT NULL,   -- HTTP status code (201, 200)
    response_body     JSONB           NOT NULL,   -- Cached API response
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ     NOT NULL DEFAULT now() + INTERVAL '24 hours'
);

-- Для scheduled cleanup: удаление expired keys
CREATE INDEX idx_idempotency_expires
    ON idempotency_keys (expires_at);
