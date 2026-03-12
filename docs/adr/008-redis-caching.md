# ADR-008: Redis as Caching and Coordination Layer

**Status:** Accepted
**Date:** 2025-10-15

## Decision

Redis 7 for: distributed cache (Cache-Aside), rate limiting (Sliding Window), Pub/Sub (SSE), idempotency key cache.

## Rationale

- **Sub-millisecond latency:** Exchange rate cache TTL=30s, transfer status cache TTL=30s
- **Sliding Window rate limiting:** Lua script for atomic check-and-increment. 100 req/min per user.
- **Pub/Sub:** Transfer status changes published via Redis → consumed by SSE endpoint. Lightweight alternative to dedicated message broker for real-time UI updates.
- **Versatile:** Single Redis instance serves 4 different use cases

## Consequences

- Single point of failure for rate limiting (mitigated: fail-open on Redis unavailability)
- Memory limited (256MB LRU eviction in Docker Compose)
- In production: ElastiCache with cluster mode for HA
