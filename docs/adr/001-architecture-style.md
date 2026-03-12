# ADR-001: Event-Driven Microservices Architecture

**Status:** Accepted
**Date:** 2025-10-15
**Deciders:** Daniel (Tech Lead), team

## Context

TransferHub needs to handle cross-border money transfers with:
- Multiple service teams (Transfers, Payments, Payouts, Identity, Notifications)
- Different scaling profiles (Transfer: write-heavy, Pricing: compute-heavy, Notification: fan-out)
- Strict reliability requirements for financial data
- Polyglot stack requirement (Kotlin + Go)

## Options

1. **Monolith** — single deployable, shared database
2. **Microservices + REST** — synchronous inter-service calls
3. **Microservices + Event-Driven (Kafka)** — asynchronous communication backbone

## Decision

Option 3: Event-driven microservices with Apache Kafka as the central backbone.

## Rationale

- **Independent scaling:** Transfer Service (2-8 pods) vs Notification Gateway (2-16 pods)
- **Decoupling:** Producers don't know consumers. Adding audit-consumer = no code change in Transfer Service
- **Durability:** Kafka 7-day retention — events survive service outages
- **Replay:** Can reprocess events when adding new consumers or fixing bugs
- **Polyglot:** Each service chooses best language/framework (Kotlin/Spring Boot, Kotlin/Ktor, Go)

## Consequences

- **Positive:** Independent deployment, resilience to partial failures, event replay
- **Negative:** Eventual consistency (no distributed transactions), operational complexity, debugging requires distributed tracing
- **Mitigations:** Outbox Pattern for guaranteed delivery, Saga for consistency, Tempo for tracing
