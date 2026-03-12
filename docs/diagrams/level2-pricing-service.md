# Level 2: Pricing Service — Internal Architecture

> Детальная внутренняя структура Pricing Service (Ktor). Для whiteboard-интервью: нарисовать за 3–5 минут.

## Component Diagram

```mermaid
graph TB
    subgraph "Pricing Service (Ktor 2.3.12, HTTP:8082 + gRPC:9090)"
        direction TB

        subgraph "API Layer"
            REST["REST Endpoints<br/><i>Ktor DSL Routing</i><br/>GET /quotes, POST /quotes"]
            GRPC["gRPC Server<br/><i>protoc-gen-grpc-kotlin</i><br/>GetQuote, ValidateQuote"]
            HEALTH["Health Endpoint<br/>/health, /ready"]
        end

        subgraph "Service Layer"
            QS["QuoteService<br/><i>suspend fun</i><br/>calculateQuote, validateQuote"]
            FC["FeeCalculator<br/><i>Interface</i><br/>Legacy (flat) / Tiered"]
            ERC["ExchangeRateClient<br/><i>Rate provider</i>"]
        end

        subgraph "Cache Layer"
            QCS["QuoteCacheService<br/><i>Redis</i><br/>quote:{id}, configurable TTL"]
            CCC["CorridorConfigCache<br/><i>Caffeine L1</i><br/>+ Redis L2"]
        end

        subgraph "Data Access"
            MR["CorridorRepository<br/><i>MongoDB Coroutine Driver</i><br/>v5.2.1"]
        end

        subgraph "Observability"
            MET["Micrometer Metrics<br/><i>/metrics endpoint</i><br/>Prometheus format"]
        end
    end

    %% External Systems
    MONGO[(MongoDB 7<br/>corridor_configs<br/>fee tiers, delivery methods)]
    REDIS[(Redis 7<br/>quote cache TTL=30s<br/>rate lock TTL=30s)]
    TS_EXT[Transfer Service<br/>gRPC Client]
    BFF[BFF / Client Apps<br/>REST Client]

    %% Connections — Inbound
    TS_EXT -->|"gRPC<br/>ValidateQuote<br/>~5ms"| GRPC
    BFF -->|"REST/JSON<br/>GET /quotes"| REST

    %% Connections — Service
    GRPC --> QS
    REST --> QS
    QS --> FC
    QS --> ERC

    %% Connections — Cache
    QS --> QCS
    QCS -->|"SETEX / GET"| REDIS
    QS --> CCC

    %% Connections — Data
    CCC -->|"Cache miss"| MR
    MR -->|"Coroutine Driver<br/>find/aggregate"| MONGO

    %% Styling
    classDef green fill:#c8e6c9,stroke:#388e3c,color:#1b5e20
    classDef blue fill:#bbdefb,stroke:#1976d2,color:#0d47a1
    classDef gray fill:#e0e0e0,stroke:#616161,color:#212121
    classDef red fill:#ffcdd2,stroke:#d32f2f,color:#b71c1c

    class REST,GRPC,HEALTH,QS,FC,ERC,QCS,CCC,MR,MET green
    class TS_EXT,BFF blue
    class MONGO,REDIS gray
```

## Calculation Pipeline

```mermaid
graph LR
    REQ["GetQuote Request<br/>sendAmount, corridor,<br/>deliveryMethod"] --> CORRIDOR["Load Corridor Config<br/><i>Caffeine → Redis → MongoDB</i>"]
    CORRIDOR --> RATE["Get Exchange Rate<br/><i>ExchangeRateClient</i><br/>Redis cache TTL=30s"]
    RATE --> FEE["Calculate Fee<br/><i>Legacy: flat rate</i><br/><i>Tiered: progressive tiers</i>"]
    FEE --> CALC["Calculate Receive Amount<br/><i>(sendAmount - fee) × rate</i>"]
    CALC --> LOCK["Lock Quote<br/><i>Redis SETEX</i><br/>quote:{id} TTL=30s"]
    LOCK --> RESP["QuoteResponse<br/>quoteId, sendAmount,<br/>receiveAmount, rate, fee"]

    classDef step fill:#e8f5e9,stroke:#4caf50
    class REQ,CORRIDOR,RATE,FEE,CALC,LOCK,RESP step
```

## Tiered Fee Calculation

```
Fee Tiers (new-pricing-algorithm flag):
┌──────────────────┬──────────┬──────────────────────┐
│ Send Amount      │ Rate     │ Example ($300)       │
├──────────────────┼──────────┼──────────────────────┤
│ $0 – $100        │ 1.0%     │ $100 × 1.0% = $1.00 │
│ $100.01 – $500   │ 0.8%     │ $200 × 0.8% = $1.60 │
│ $500.01+         │ 0.5%     │ —                    │
├──────────────────┼──────────┼──────────────────────┤
│ Minimum fee      │ $0.99    │ Total: $2.60         │
└──────────────────┴──────────┴──────────────────────┘
```

## Ключевые решения

| Решение | Обоснование |
|---------|-------------|
| **Ktor (не Spring Boot)** | Stateless compute, ~40MB image vs ~200MB. Native coroutines. DSL routing. |
| **gRPC (не REST)** | Hot path: ~5ms vs ~15ms. Type-safe proto contract. HTTP/2 multiplexing. |
| **MongoDB (не PostgreSQL)** | Schema-less corridor configs. Nested fee tiers. Frequent changes without migrations. |
| **Caffeine L1 + Redis L2** | L1: in-process, ~0.1ms. L2: distributed, ~1ms. MongoDB: ~5-10ms. |
| **Quote Lock (Redis TTL=30s)** | Гарантирует что exchange rate не изменится между получением котировки и созданием перевода. |
| **Kotlinx.serialization** | Ktor ecosystem (не Jackson). Compile-time, no reflection. |
