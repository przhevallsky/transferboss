# ADR-003: Apache Kafka as Message Broker

**Status:** Accepted
**Date:** 2025-10-15
**Deciders:** Daniel (Tech Lead), team

## Context

Need a message broker for async inter-service communication. Requirements: ordering guarantees, durability, replay capability, high throughput.

## Options

1. **RabbitMQ** — AMQP, flexible routing, push-based
2. **Apache Kafka** — log-based, pull-based, partitioned, high throughput
3. **AWS SQS/SNS** — managed, simple, no ordering guarantees (standard queues)

## Decision

Apache Kafka 7.6 (KRaft mode, no ZooKeeper).

## Rationale

- **Ordering:** Key = transfer_id → same partition → strict ordering within transfer lifecycle
- **Durability:** 7-day retention, replication factor 3 — events survive broker failures
- **Replay:** Consumer can reset offset to reprocess events (e.g., when adding ClickHouse ETL consumer)
- **Multi-consumer:** Same topic consumed by Transfer Service, Notification Gateway, Analytics ETL independently
- **KRaft mode:** Eliminates ZooKeeper dependency, simpler operations

## Consequences

- **Positive:** Strong ordering, durability, replay, multi-consumer
- **Negative:** Higher operational complexity vs RabbitMQ, no built-in delayed delivery (used @RetryableTopic or manual retry topics)
- **Topic count:** 23 topics (including retry and DLT auto-generated topics)
