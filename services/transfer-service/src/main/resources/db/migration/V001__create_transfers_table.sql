CREATE TABLE transfers (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Идемпотентность
    idempotency_key   UUID            NOT NULL,

    -- Отправитель
    sender_id         UUID            NOT NULL,

    -- Котировка
    quote_id          UUID            NOT NULL,

    -- Финансовые данные
    send_amount       NUMERIC(15,2)   NOT NULL,
    send_currency     CHAR(3)         NOT NULL,
    receive_amount    NUMERIC(15,2)   NOT NULL,
    receive_currency  CHAR(3)         NOT NULL,
    exchange_rate     NUMERIC(12,6)   NOT NULL,
    fee_amount        NUMERIC(10,2)   NOT NULL,
    fee_currency      CHAR(3)         NOT NULL,

    -- Маршрут
    source_country    CHAR(2)         NOT NULL,
    dest_country      CHAR(2)         NOT NULL,
    delivery_method   VARCHAR(30)     NOT NULL,

    -- Получатель
    recipient_id      UUID            NOT NULL,

    -- Состояние
    status            VARCHAR(30)     NOT NULL DEFAULT 'CREATED',
    status_reason     TEXT,

    -- Saga tracking
    payment_id        UUID,
    payout_id         UUID,

    -- Optimistic locking
    version           INTEGER         NOT NULL DEFAULT 0,

    -- Аудит
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    -- Constraints
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transfers_status CHECK (status IN (
        'CREATED', 'PAYMENT_PENDING', 'PAYMENT_CAPTURED', 'PAYMENT_FAILED',
        'COMPLIANCE_CHECK', 'COMPLIANCE_HOLD', 'COMPLIANCE_REJECTED',
        'PAYOUT_PENDING', 'DELIVERING', 'COMPLETED',
        'FAILED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'
    )),
    CONSTRAINT chk_transfers_send_amount CHECK (send_amount > 0),
    CONSTRAINT chk_transfers_receive_amount CHECK (receive_amount > 0),
    CONSTRAINT chk_transfers_fee_amount CHECK (fee_amount >= 0)
);

-- Индексы

-- Поиск переводов пользователя (cursor-based pagination)
-- Составной индекс покрывает фильтрацию по sender_id + сортировку по created_at DESC → Index Only Scan
CREATE INDEX idx_transfers_sender_created
    ON transfers (sender_id, created_at DESC);

-- Мониторинг зависших переводов — partial index: только активные (~5% от общего числа)
CREATE INDEX idx_transfers_status
    ON transfers (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED');

-- Корреляция с Payment Service событиями
CREATE INDEX idx_transfers_payment_id
    ON transfers (payment_id)
    WHERE payment_id IS NOT NULL;

-- Корреляция с Payout Service событиями
CREATE INDEX idx_transfers_payout_id
    ON transfers (payout_id)
    WHERE payout_id IS NOT NULL;

-- Аналитика: переводы по коридору за период
CREATE INDEX idx_transfers_corridor_date
    ON transfers (source_country, dest_country, created_at DESC);