-- Transfer records
CREATE TABLE transfers (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id           UUID            NOT NULL,
    recipient_id        UUID            NOT NULL,
    source_currency     VARCHAR(3)      NOT NULL,
    target_currency     VARCHAR(3)      NOT NULL,
    source_amount       NUMERIC(19,4)   NOT NULL,
    target_amount       NUMERIC(19,4),
    exchange_rate       NUMERIC(19,8),
    fee_amount          NUMERIC(19,4)   NOT NULL DEFAULT 0,
    fee_currency        VARCHAR(3),
    status              VARCHAR(32)     NOT NULL DEFAULT 'CREATED',
    status_reason       TEXT,
    reference_id        VARCHAR(64)     NOT NULL,
    idempotency_key     VARCHAR(128)    NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_transfers_sender_id       ON transfers (sender_id);
CREATE INDEX idx_transfers_recipient_id    ON transfers (recipient_id);
CREATE INDEX idx_transfers_status          ON transfers (status);
CREATE INDEX idx_transfers_reference_id    ON transfers (reference_id);
CREATE INDEX idx_transfers_created_at      ON transfers (created_at);
