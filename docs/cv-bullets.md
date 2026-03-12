# CV Bullet Points — TransferHub Platform

> Ready-to-use formulations for resume/CV. Each bullet follows the pattern: Action verb + What + Impact/Scale.

## Summary (for CV header)

Designed and implemented event-driven microservices platform for cross-border remittances processing thousands of daily transfers across 10+ corridors. Kotlin/Spring Boot, Ktor, Go, Kafka, PostgreSQL, Redis, ClickHouse, Kubernetes.

---

## Architecture & Design

- Designed event-driven microservices architecture (8 services) with Apache Kafka as central backbone, handling 100+ events/sec with guaranteed delivery via Transactional Outbox Pattern
- Implemented choreography-based Saga pattern with compensation flows (payment → payout → completion/refund), ensuring data consistency across services without distributed transactions
- Architected polyglot persistence strategy: PostgreSQL (ACID financial transactions), MongoDB (flexible corridor configs), Redis (sub-ms caching + rate limiting), ClickHouse (columnar OLAP analytics)
- Designed Redirect & Retry Topic pattern preserving strict event ordering during consumer failures — replaced Spring Kafka @RetryableTopic which violated notification ordering

## Backend Development

- Developed Transfer Service (Kotlin/Spring Boot) — core transfer lifecycle with 14-state sealed class state machine, cursor-based pagination, idempotent API (X-Idempotency-Key)
- Built Pricing Service (Kotlin/Ktor) with gRPC API achieving 3x latency reduction vs REST/JSON (5ms vs 15ms per call) using Protobuf binary serialization and HTTP/2 multiplexing
- Implemented real-time status updates via SSE (Server-Sent Events) using Spring WebFlux + Redis Pub/Sub, delivering sub-second status changes to connected clients
- Built RAG-based AI assistant: document chunking → OpenAI embeddings → pgvector similarity search → LLM response with SSE token-by-token streaming and circuit breaker fallback

## Data & Analytics

- Designed ClickHouse analytics pipeline: Kafka ETL consumer with buffered batch INSERT, ReplacingMergeTree for at-least-once deduplication, LowCardinality for 10x compression on enum columns
- Implemented cursor-based pagination replacing OFFSET/LIMIT, reducing deep pagination queries from 2.8s to stable 15ms regardless of page depth
- Built MongoDB → PostgreSQL migration tool with distributed advisory locks, batch processing, dry-run mode, and checkpoint-based resume capability

## Resilience & Reliability

- Implemented Circuit Breaker pattern (Resilience4j) with differentiated fallback strategies: cached data for Pricing, fast-fail for Identity, vector-search-only for LLM API
- Designed distributed locking via Consul KV with session TTL auto-release, exponential backoff retry, and per-entity lock granularity — preventing race conditions across 2-8 service replicas
- Achieved idempotent processing across REST API (X-Idempotency-Key + Consul lock + UNIQUE constraint) and Kafka consumers (consumed_events table in same transaction)
- Investigated and resolved production memory leak: identified unbounded ConcurrentHashMap (4.2M entries, 1.2GB) via heap dump analysis (Eclipse MAT), replaced with Caffeine cache (maxSize 10K, TTL 5min), stabilized heap from 2.1GB to 450MB

## Security

- Implemented JWT authentication (RS256) with RBAC: SENDER/OPERATOR/ADMIN roles, stateless sessions, RFC 9457 error responses
- Built Redis-based rate limiting with sliding window algorithm (Lua script for atomicity): 100 req/min authenticated, 20 req/min anonymous, fail-open on Redis unavailability
- Automated PII masking in logs via custom Logback converter — email, phone, SSN, card numbers masked before reaching Loki/Grafana

## Observability & Operations

- Built full observability stack: Prometheus (metrics) + Grafana (3 dashboards) + Loki (logs) + Tempo (distributed tracing) with end-to-end trace correlation through REST, gRPC, and Kafka
- Configured 8+ alert rules with Alertmanager routing: P1 (>5% error rate) → PagerDuty, P2 (circuit breaker open, high consumer lag) → Slack, with runbooks for each alert
- Enabled distributed tracing across Kafka using W3C Trace Context propagation (observation-enabled) — single traceId follows request from REST API through 3-4 services

## Infrastructure & DevOps

- Containerized all services with multi-stage Docker builds: JRE-alpine for JVM (~200MB), scratch for Go (~15MB), non-root users, layer-optimized caching
- Created Helm charts for Kubernetes with rolling updates (maxUnavailable: 0), HPA auto-scaling (CPU + Kafka consumer lag), liveness/readiness/startup probes
- Described AWS infrastructure via Terraform modules: VPC (3 AZs, public/private subnets), EKS (managed node groups), RDS (PostgreSQL 16, Multi-AZ, encrypted), S3 (lifecycle policies)
- Configured GitHub Actions CI pipeline with per-service change detection, Testcontainers integration tests, and CI Gate pattern for branch protection

## Feature Management

- Implemented feature flag system (Unleash) enabling trunk-based development with gradual rollout (5% → 25% → 100%) and instant rollback via UI toggle
- Built tiered pricing algorithm behind feature flag with per-user targeting and Micrometer metrics tracking algorithm distribution (tiered vs legacy)
