# ADR-005: PostgreSQL for Transactional Data

**Status:** Accepted
**Date:** 2025-10-15

## Context

Need a primary database for financial transfer data. Requirements: ACID transactions, strong typing for monetary amounts, JSON support, extensions (pgvector).

## Decision

PostgreSQL 16 with pgvector extension.

## Rationale

- **ACID:** Transfer + Outbox Event in single transaction (Outbox Pattern)
- **NUMERIC(15,2):** Exact arithmetic for monetary amounts (no float rounding)
- **CHECK constraints:** Status validation at DB level (14 allowed values)
- **pgvector:** Vector similarity search for RAG pipeline (no separate vector DB needed)
- **Flyway:** 7 migration versions, `ddl-auto: validate` ensures entity-schema consistency
- **Ecosystem:** HikariCP, Spring Data JPA, Testcontainers support

## Consequences

- Not suitable for OLAP queries (addressed by ClickHouse via CQRS-lite)
- Schema changes require Flyway migrations (vs MongoDB schema-less)
