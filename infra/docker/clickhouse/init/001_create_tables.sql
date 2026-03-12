-- ClickHouse Analytics Schema
-- ReplacingMergeTree deduplicates by (transfer_id) using event_time as version

CREATE TABLE IF NOT EXISTS transfers_analytics
(
    transfer_id       UUID,
    sender_id         UUID,
    source_country    LowCardinality(String),
    dest_country      LowCardinality(String),
    corridor          LowCardinality(String),
    send_amount       Decimal64(4),
    send_currency     LowCardinality(String),
    receive_amount    Decimal64(4),
    receive_currency  LowCardinality(String),
    exchange_rate     Decimal64(8),
    fee_amount        Decimal64(4),
    fee_currency      LowCardinality(String),
    delivery_method   LowCardinality(String),
    status            LowCardinality(String),
    created_at        DateTime64(3, 'UTC'),
    updated_at        DateTime64(3, 'UTC'),
    event_time        DateTime64(3, 'UTC') DEFAULT now64(3)
)
ENGINE = ReplacingMergeTree(event_time)
PARTITION BY toYYYYMM(created_at)
ORDER BY (corridor, created_at, transfer_id)
SETTINGS index_granularity = 8192;

-- Materialized view: daily volume per corridor
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_daily_corridor_volume
ENGINE = SummingMergeTree()
PARTITION BY toYYYYMM(day)
ORDER BY (corridor, day)
AS
SELECT
    toDate(created_at) AS day,
    corridor,
    count() AS transfer_count,
    sum(send_amount) AS total_send_amount,
    sum(fee_amount) AS total_fee_amount
FROM transfers_analytics
GROUP BY corridor, toDate(created_at);
