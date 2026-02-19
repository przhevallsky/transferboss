-- Idempotency keys for safe request retries
CREATE TABLE idempotency_keys (
    key                 VARCHAR(128)    PRIMARY KEY,
    response_status     INT             NOT NULL,
    response_body       JSONB,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ     NOT NULL DEFAULT now() + INTERVAL '24 hours'
);

CREATE INDEX idx_idempotency_keys_expires_at ON idempotency_keys (expires_at);
