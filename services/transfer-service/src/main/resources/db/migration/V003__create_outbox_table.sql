CREATE TABLE outbox (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Какой агрегат породил событие
    entity_type       VARCHAR(50)     NOT NULL,   -- 'TRANSFER'
    entity_id         UUID            NOT NULL,   -- transfer_id (используется как Kafka key)

    -- Событие
    event_type        VARCHAR(100)    NOT NULL,   -- 'transfer.created', 'transfer.status_changed'
    payload           JSONB           NOT NULL,

    -- Обработка
    status            VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- Метаданные
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    kafka_topic       VARCHAR(200),
    kafka_offset      BIGINT,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- Основной индекс для Outbox polling: PENDING события в порядке создания
-- Используется Outbox Service: SELECT ... WHERE status='PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED
CREATE INDEX idx_outbox_pending
    ON outbox (created_at ASC)
    WHERE status = 'PENDING';

-- Для scheduled cleanup job: DELETE обработанных событий старше 7 дней
CREATE INDEX idx_outbox_processed
    ON outbox (processed_at)
    WHERE status = 'SENT';
