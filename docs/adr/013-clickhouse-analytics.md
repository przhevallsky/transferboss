# ADR-013: ClickHouse for OLAP Analytics

**Status:** Accepted
**Date:** 2026-01-15

## Context

Product team needs analytics dashboards: transfer volume by corridor, revenue trends, success rates. PostgreSQL aggregation queries on transfers table: 2-5 seconds on 1M rows.

## Decision

ClickHouse 24.1 as OLAP store with Kafka ETL pipeline (CQRS-lite).

## Rationale

- **Columnar storage:** Reads only required columns → 10-50x faster for GROUP BY queries
- **ReplacingMergeTree:** Deduplication for Kafka at-least-once delivery
- **LowCardinality:** Dictionary encoding for enum-like columns (corridor, currency, status) → 10x compression
- **Materialized Views:** Pre-aggregated daily corridor volumes via SummingMergeTree → sub-second dashboard queries

## Data Pipeline

PostgreSQL → Outbox → Kafka → AnalyticsEtlConsumer (batch 100, flush 10s) → ClickHouse

## Consequences

- Additional infrastructure component (ClickHouse server)
- Eventual consistency: data arrives in ClickHouse seconds after PostgreSQL write
- Not suitable for point queries or updates (use PostgreSQL for that)
