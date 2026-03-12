# Level 3: Flow Diagrams — Key Scenarios

> Пошаговые сценарии для whiteboard-интервью. Четыре ключевых flow, которые спрашивают на собеседованиях.

---

## Flow 1: Create Transfer — Happy Path

```mermaid
sequenceDiagram
    actor Client
    participant TS as Transfer Service
    participant Consul
    participant IS as Identity Service
    participant PS as Pricing Service<br/>(gRPC)
    participant PG as PostgreSQL
    participant Redis
    participant OB as Outbox Service
    participant Kafka
    participant MP as Mock Payment
    participant MPO as Mock Payout

    Note over Client,TS: 1. API Request (synchronous)
    Client->>+TS: POST /api/v1/transfers<br/>Authorization: Bearer JWT<br/>X-Idempotency-Key: uuid-123

    Note over TS: SecurityConfig: JWT RS256 verify<br/>RateLimitFilter: Redis sliding window

    TS->>+Consul: PUT /kv/locks/transfer/sender/{id}/create<br/>?acquire={session}
    Consul-->>-TS: true (lock acquired)

    TS->>PG: findByIdempotencyKey(uuid-123)
    PG-->>TS: null (first request)

    Note over TS: validateTransfer(): corridor + deliveryMethod + minAmount

    TS->>+IS: GET /kyc-status/{senderId}<br/>[Circuit Breaker: identity-service]
    IS-->>-TS: 200 OK (KYC verified)

    TS->>+PS: gRPC ValidateQuote(quoteId)<br/>[Circuit Breaker: pricing-service]
    PS-->>-TS: QuoteResponse (rate, fee, amounts)

    Note over TS: FeeService.applyFeeStrategy()<br/>Unleash: new-pricing-algorithm check

    Note over TS,PG: 2. Atomic Write (@Transactional)
    TS->>PG: BEGIN
    TS->>PG: INSERT transfers (status=CREATED)
    TS->>PG: INSERT outbox (TRANSFER_CREATED)
    TS->>PG: INSERT outbox (PAYMENT_REQUESTED)
    TS->>PG: UPDATE transfers (status=PAYMENT_PENDING)
    TS->>PG: COMMIT

    TS->>Redis: PUBLISH transfer-status:{id}<br/>status=PAYMENT_PENDING
    TS->>Consul: PUT /kv/...?release={session}

    TS-->>-Client: 201 Created<br/>{transfer_id, status: PAYMENT_PENDING}

    Note over OB,Kafka: 3. Async: Outbox → Kafka (500ms poll)
    OB->>PG: SELECT FROM outbox<br/>WHERE status=PENDING<br/>FOR UPDATE SKIP LOCKED<br/>LIMIT 100
    PG-->>OB: [TRANSFER_CREATED, PAYMENT_REQUESTED]
    OB->>Kafka: publish transfers.payment.requested<br/>key=transfer_id
    OB->>PG: UPDATE outbox SET status=SENT

    Note over Kafka,MPO: 4. Saga: Payment → Payout
    Kafka->>MP: transfers.payment.requested
    MP->>Kafka: payments.payment.captured

    Kafka->>TS: payments.payment.captured
    Note over TS: PaymentEventConsumer:<br/>check consumed_events (idempotency)<br/>transitionTo(PAYMENT_CAPTURED)
    TS->>PG: INSERT consumed_events + UPDATE transfer
    TS->>PG: INSERT outbox (PAYOUT_REQUESTED)

    OB->>Kafka: transfers.payout.requested
    Kafka->>MPO: transfers.payout.requested
    MPO->>Kafka: payouts.payout.completed

    Kafka->>TS: payouts.payout.completed
    Note over TS: PayoutEventConsumer:<br/>transitionTo(COMPLETED)
    TS->>PG: UPDATE transfers status=COMPLETED
    TS->>Redis: PUBLISH transfer-status:{id}<br/>status=COMPLETED
```

---

## Flow 2: Saga Compensation — Payout Failed

```mermaid
sequenceDiagram
    participant Kafka
    participant TS as Transfer Service
    participant PG as PostgreSQL
    participant Redis
    participant OB as Outbox Service
    participant MP as Mock Payment
    participant MPO as Mock Payout

    Note over MPO,Kafka: Payout provider rejects
    MPO->>Kafka: payouts.payout.failed<br/>{reason: INVALID_ACCOUNT}

    Kafka->>TS: payouts.payout.failed
    Note over TS: PayoutEventConsumer
    TS->>PG: check consumed_events → new event
    TS->>PG: BEGIN
    Note over TS,PG: transitionTo(PAYOUT_FAILED)<br/>INSERT consumed_events<br/>INSERT outbox (REFUND_REQUESTED)
    TS->>PG: COMMIT
    TS->>Redis: PUBLISH status=PAYOUT_FAILED

    Note over OB,MP: Compensation: Refund Payment
    OB->>PG: SELECT outbox PENDING
    OB->>Kafka: transfers.payment.refund.requested
    OB->>PG: UPDATE outbox SENT

    Kafka->>MP: transfers.payment.refund.requested
    Note over MP: Process refund
    MP->>Kafka: payments.payment.refunded

    Kafka->>TS: payments.payment.refunded
    Note over TS: PaymentEventConsumer
    TS->>PG: BEGIN
    Note over TS,PG: transitionTo(REFUNDED)<br/>INSERT consumed_events<br/>INSERT outbox (STATUS_CHANGED)
    TS->>PG: COMMIT
    TS->>Redis: PUBLISH status=REFUNDED

    Note over TS: Transfer lifecycle complete:<br/>CREATED → PAYMENT_PENDING →<br/>PAYMENT_CAPTURED → PAYOUT_PENDING →<br/>PAYOUT_FAILED → REFUND_PENDING → REFUNDED
```

---

## Flow 3: Redirect & Retry — Ordering Preserved

```mermaid
sequenceDiagram
    participant Kafka as Kafka<br/>notification.delivery
    participant NDC as NotificationDelivery<br/>Consumer
    participant RS as Redirect Set<br/>(ConcurrentHashMap)
    participant KR as Kafka<br/>notification.delivery.retry
    participant NRC as NotificationRetry<br/>Consumer
    participant DLT as Kafka<br/>notification.delivery.dlt
    participant NS as NotificationSender

    Note over Kafka,NDC: Event A: transfer_123 PAYMENT_CAPTURED
    Kafka->>NDC: Event A (transfer_123)
    NDC->>RS: check redirect: transfer_123?
    RS-->>NDC: false (not redirected)
    NDC->>NS: send notification
    NS-->>NDC: FAILURE (timeout)
    NDC->>RS: ADD transfer_123 to redirect set
    NDC->>KR: redirect Event A → retry topic

    Note over Kafka,NDC: Event B: transfer_123 COMPLETED
    Kafka->>NDC: Event B (transfer_123)
    NDC->>RS: check redirect: transfer_123?
    RS-->>NDC: true (redirected!)
    Note over NDC: Skip direct delivery<br/>Redirect to retry topic
    NDC->>KR: redirect Event B → retry topic

    Note over Kafka,NDC: Event C: transfer_456 COMPLETED (different transfer)
    Kafka->>NDC: Event C (transfer_456)
    NDC->>RS: check redirect: transfer_456?
    RS-->>NDC: false (not redirected)
    NDC->>NS: send notification
    NS-->>NDC: SUCCESS ✓
    Note over NDC: transfer_456 unaffected<br/>per-transfer isolation

    Note over KR,NRC: Retry Consumer processes sequentially
    KR->>NRC: Event A (retry-count: 0)
    NRC->>NS: send notification
    NS-->>NRC: SUCCESS ✓

    KR->>NRC: Event B (retry-count: 0)
    NRC->>NS: send notification
    NS-->>NRC: SUCCESS ✓
    NRC->>RS: CLEAR redirect: transfer_123

    Note over NRC: Result: User receives<br/>A (PAYMENT_CAPTURED) before<br/>B (COMPLETED) ✓

    Note over KR,DLT: After 5 failed retries
    rect rgb(255, 230, 230)
        KR->>NRC: Event X (retry-count: 5)
        NRC->>NS: send notification
        NS-->>NRC: FAILURE
        NRC->>DLT: move to DLT<br/>headers: retry-count=5, failure-reason
        NRC->>RS: CLEAR redirect
        Note over DLT: NotificationDltConsumer<br/>logs + metrics alert
    end
```

---

## Flow 4: Circuit Breaker State Transitions

```mermaid
stateDiagram-v2
    [*] --> CLOSED

    CLOSED --> OPEN: failureRate >= 50%<br/>(5+ failures / 10 calls)
    CLOSED --> CLOSED: failureRate < 50%<br/>(normal operation)

    OPEN --> HALF_OPEN: after waitDuration (30s)<br/>(automatic transition)

    HALF_OPEN --> CLOSED: 2/3 test calls succeed<br/>(service recovered)
    HALF_OPEN --> OPEN: 2/3 test calls fail<br/>(still broken)

    note right of CLOSED
        All requests go to target service.
        Sliding window: last 10 calls.
        Metrics: success/failure counters.
    end note

    note right of OPEN
        All requests fail-fast (no network call).
        Pricing: 503 Service Unavailable.
        Identity: 503 + fast-fail.
        Prometheus alert: CircuitBreakerOpen.
    end note

    note right of HALF_OPEN
        3 permitted test calls.
        If majority succeed → CLOSED.
        If majority fail → OPEN again.
    end note
```

### Circuit Breaker Configuration (application.yml)

| Parameter | pricing-service | identity-service |
|-----------|----------------|-----------------|
| slidingWindowSize | 10 | 10 |
| failureRateThreshold | 50% | 50% |
| slowCallDurationThreshold | 2s | 1s |
| waitDurationInOpenState | 30s | 30s |
| permittedCallsInHalfOpen | 3 | 3 |
| minimumNumberOfCalls | 5 | 5 |

### Fallback Strategies

| Service | CB Open Behavior | Rationale |
|---------|-----------------|-----------|
| **Pricing Service** | 503 → client retries with exponential backoff | Cannot create transfer without valid quote |
| **Identity Service** | 503 → fast-fail, no KYC = no transfer | Compliance requirement, cannot skip |
| **OpenAI API** (LLM) | Vector search fallback → return top document | Graceful degradation, answer without LLM |
| **Redis** (Rate Limit) | Fail-open → allow request | Availability > strict limiting |
