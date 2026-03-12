# ADR-011: Prometheus + Grafana + Loki + Tempo for Observability

**Status:** Accepted
**Date:** 2025-12-01

## Decision

Unified observability stack: Prometheus (metrics), Grafana (visualization), Loki (logs), Tempo (traces).

## Options

1. **ELK (Elasticsearch + Logstash + Kibana)** — full-text log search
2. **Datadog/New Relic** — managed SaaS, expensive
3. **Prometheus + Grafana + Loki + Tempo** — open-source, unified UI

## Rationale

- **Single UI:** Grafana for metrics, logs, and traces. Click metric exemplar → Tempo trace → Loki logs. One tool, one learning curve.
- **Cost:** Open-source. Loki indexes only labels (not full text) → 10x less storage vs Elasticsearch.
- **W3C Trace Context:** End-to-end tracing through REST, gRPC, and Kafka (observation-enabled).
- **Alerting:** Prometheus alerting rules → Alertmanager → PagerDuty/Slack routing.

## Consequences

- **Positive:** Unified workflow (metric → trace → log), cost-effective, open-source
- **Negative:** Loki full-text search weaker than Elasticsearch. Acceptable for our log volume.
- **8+ alert rules** covering error rate, latency, circuit breaker, consumer lag, DLT, memory
