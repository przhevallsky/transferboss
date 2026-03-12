# TransferHub — End-to-End Demo Scenario

> 15–20 minute walkthrough demonstrating all aspects of the system. For interviews, presentations, or self-verification.

---

## Preparation

```bash
cd infra/docker

# Start all infrastructure + monitoring
docker compose --profile monitoring up -d

# Verify all services healthy
docker compose ps

# Start application services (separate terminals or docker compose)
# Terminal 1: Transfer Service (port 8080)
# Terminal 2: Outbox Service (port 8081)
# Terminal 3: Pricing Service (port 8082 + 9090)
# Terminal 4: Mock Payment (8083) + Mock Payout (8084)
```

**Open tabs:**
- Swagger UI: http://localhost:8080/swagger-ui
- Grafana: http://localhost:3000 (admin/admin)
- Unleash: http://localhost:4242 (admin/unleash4all)
- Consul: http://localhost:8500

---

## Act 1: Create Transfer (Happy Path) — 3 min

**Goal:** Show the synchronous path: REST → validation → gRPC → Outbox.

```bash
# 1. Get JWT token
curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"userId": "usr_demo", "roles": ["SENDER"]}' | jq .

# 2. Get a quote from Pricing Service
curl -s -X POST http://localhost:8082/quotes \
  -H "Content-Type: application/json" \
  -d '{
    "sourceCountry": "US",
    "destCountry": "PH",
    "sendAmount": 500.00,
    "sendCurrency": "USD",
    "receiveCurrency": "PHP",
    "deliveryMethod": "BANK_DEPOSIT"
  }' | jq .

# 3. Create transfer with idempotency key
IDEM_KEY=$(uuidgen)
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "quoteId": "<quote_id_from_step_2>",
    "recipientId": "<recipient_uuid>",
    "sourceCountry": "US",
    "destCountry": "PH",
    "sendAmount": 500.00,
    "sendCurrency": "USD",
    "receiveCurrency": "PHP",
    "deliveryMethod": "BANK_DEPOSIT"
  }' | jq .

# Expected: 201 Created, status: PAYMENT_PENDING
```

**Show:**
- Transfer created with status `PAYMENT_PENDING`
- Outbox table: `SELECT * FROM outbox WHERE status = 'PENDING';`
- Wait 1s → outbox status = `SENT`, Kafka message in topic

---

## Act 2: Idempotency — 1 min

**Goal:** Show double-submit protection.

```bash
# Same idempotency key as Act 1
curl -s -X POST http://localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d '{ ... same body ... }' | jq .

# Expected: 200 OK (not 201), same transfer_id returned
```

---

## Act 3: Saga Lifecycle — 3 min

**Goal:** Show async choreography: Payment → Payout → Completed.

```bash
# Check transfer status progression
curl -s http://localhost:8080/api/v1/transfers/$TRANSFER_ID \
  -H "Authorization: Bearer $TOKEN" | jq .status

# Expected sequence (check every few seconds):
# PAYMENT_PENDING → PAYMENT_CAPTURED → PAYOUT_PENDING → COMPLETED
```

**Show:**
- Kafka topics: `payments.payment.captured`, `payouts.payout.completed`
- `consumed_events` table: events processed with idempotency
- Final status: `COMPLETED`

---

## Act 4: Saga Compensation (Failure + Refund) — 3 min

**Goal:** Show compensation flow when payout fails.

```bash
# Create another transfer (this time, Mock Payout will fail)
# Configure mock-payout to return failure for specific corridor/amount

# Watch the compensation flow:
# PAYMENT_CAPTURED → PAYOUT_FAILED → REFUND_PENDING → REFUNDED
```

**Show:**
- `payouts.payout.failed` event in Kafka
- Outbox: `transfers.payment.refund.requested` event created
- Mock Payment processes refund → `payments.payment.refunded`
- Final status: `REFUNDED`

---

## Act 5: Resilience — Circuit Breaker — 2 min

**Goal:** Show graceful degradation when Pricing Service is down.

```bash
# Stop Pricing Service
docker stop pricing-service

# Try to create transfer
curl -s -X POST http://localhost:8080/api/v1/transfers ... | jq .

# Expected: 503 Service Unavailable (Circuit Breaker OPEN)
```

**Show in Grafana:**
- Circuit breaker state metric = OPEN
- Alert `CircuitBreakerOpen` fires

```bash
# Restart Pricing Service
docker start pricing-service

# Wait 30s (waitDurationInOpenState) → HALF_OPEN → CLOSED
# Transfers work again
```

---

## Act 6: Observability — Metric → Trace → Log — 3 min

**Goal:** Show the full observability workflow.

1. **Grafana → Transfer Service dashboard:**
   - Request Rate, Error Rate, Latency p50/p95/p99
   - Transfers Created by Corridor

2. **Click exemplar on latency graph → Tempo:**
   - Full trace: REST → gRPC (Pricing) → PostgreSQL
   - Spans with timing for each step

3. **Click span → Loki:**
   - Logs filtered by traceId
   - Structured JSON logs with level, message, service

4. **Kafka dashboard:**
   - Consumer lag by group
   - DLT messages count (should be 0)

---

## Act 7: Feature Flags — 2 min

**Goal:** Show safe rollout of new pricing algorithm.

1. **Unleash UI** (http://localhost:4242):
   - Find `new-pricing-algorithm` flag
   - Toggle ON for 50% of users (Gradual Rollout strategy)

2. **Create two transfers:**
   - One may use legacy pricing (flat fee)
   - Another may use tiered pricing (progressive rate)
   - Show different fee amounts in response

3. **Grafana:**
   - `pricing.fee.calculation.total` metric with `algorithm` tag
   - ~50/50 split between `tiered` and `legacy`

---

## Act 8: AI Assistant (RAG) — 2 min

**Goal:** Show LLM-powered support assistant.

```bash
# Ask a question
curl -s -X POST http://localhost:8087/api/v1/assistant/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "What are the transfer limits to Philippines?"}' | jq .

# Expected: Answer with source references and similarity scores
```

**Show:**
- Response includes `answer` + `sources` (title, category, similarity)
- Sources are from FAQ knowledge base (pgvector similarity search)

```bash
# SSE Streaming
curl -N http://localhost:8087/api/v1/assistant/ask/stream?question=How+do+fees+work

# Token-by-token streaming response
```

---

## Act 9: Analytics (ClickHouse) — 1 min

**Goal:** Show OLAP analytics from ClickHouse.

**Grafana → Analytics dashboard:**
- Transfer volume by corridor
- Revenue by corridor (fee amounts)
- Success rate trend

```bash
# Direct ClickHouse query
curl 'http://localhost:8123/?query=SELECT+corridor,count(),sum(send_amount)+FROM+transfers_analytics+GROUP+BY+corridor+FORMAT+Pretty'
```

---

## Summary Slide

| Aspect | Demonstrated |
|--------|-------------|
| **API** | REST + JWT + Idempotency + Cursor pagination |
| **Saga** | Happy path (COMPLETED) + Compensation (REFUNDED) |
| **Outbox** | Transactional event publishing, at-least-once delivery |
| **Resilience** | Circuit Breaker (OPEN → HALF_OPEN → CLOSED) |
| **Observability** | Metric → Exemplar → Trace → Logs |
| **Feature Flags** | Gradual rollout with live A/B metrics |
| **AI/RAG** | Vector search + LLM response + SSE streaming |
| **Analytics** | ClickHouse OLAP with materialized views |
| **Security** | JWT RS256, RBAC, Rate Limiting |
