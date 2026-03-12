# ADR-006: MongoDB for Configuration Data

**Status:** Accepted
**Date:** 2025-10-15

## Context

Pricing Service needs to store corridor configurations (fee tiers, delivery methods, exchange rate sources). Configs change frequently and have nested structure.

## Decision

MongoDB 7 for Pricing Service corridor configs.

## Rationale

- **Schema-less:** Adding new country corridors = insert document (no ALTER TABLE + migration)
- **Nested documents:** Fee tiers `[{min: 0, max: 500, fee: 4.99}, ...]` stored natively in BSON
- **Kotlin Coroutine Driver:** mongodb-driver-kotlin-coroutine v5.2.1 integrates with Ktor coroutines
- **Separation of concerns:** Pricing Service owns its data store independently from Transfer Service (PostgreSQL)

## Consequences

- No ACID transactions (not needed for read-heavy config data)
- Additional operational overhead (one more database to manage)
- Migration tool built for MongoDB → PostgreSQL if consolidation needed later
