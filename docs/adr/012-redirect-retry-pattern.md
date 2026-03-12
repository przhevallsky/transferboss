# ADR-012: Redirect & Retry Pattern for Notification Ordering

**Status:** Accepted
**Date:** 2025-11-15
**Supersedes:** Previous use of @RetryableTopic for notification consumer

## Context

@RetryableTopic in Spring Kafka does not preserve event ordering during retries. When Event A fails and goes to retry topic, Event B is processed immediately from main topic. For financial notifications, ordering is critical: "Payment captured" must arrive before "Transfer completed".

## Decision

Custom Redirect & Retry pattern with per-transfer redirect set and dedicated retry/DLT topics.

## How It Works

1. On failure for transfer_123: add to redirect set, send Event A to retry topic
2. Subsequent events for transfer_123: check redirect set → redirect to retry topic (not processed)
3. Retry consumer processes sequentially: A → B → clear redirect
4. After 5 retries: move to DLT, clear redirect, alert

## Consequences

- **Positive:** Strict per-transfer ordering, configurable progressive backoff (30s → 2min → 10min → 30min → 1h)
- **Negative:** More code than @RetryableTopic annotation, in-memory redirect set (ConcurrentHashMap) not persistent across restarts
- **Reused:** Pattern applied to Payout consumer as well
