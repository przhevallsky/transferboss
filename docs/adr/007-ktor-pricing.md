# ADR-007: Ktor for Pricing Service

**Status:** Accepted
**Date:** 2025-10-20
**Deciders:** Daniel (Tech Lead), team

## Context

Pricing Service is stateless, compute-heavy (fee calculation, exchange rates). Needs gRPC + REST endpoints. No transactions, no JPA.

## Options

1. **Spring Boot** — consistency with Transfer Service
2. **Ktor** — lightweight, native coroutines, DSL routing
3. **Micronaut** — compile-time DI, fast startup

## Decision

Ktor 2.3.12.

## Rationale

- **Lightweight:** ~40MB Docker image vs ~200MB Spring Boot. Startup ~2s vs ~8s.
- **Native coroutines:** Ktor built on coroutines. MongoDB Kotlin Coroutine Driver + Ktor = natural integration.
- **DSL routing:** `routing { get("/quotes") { ... } }` — declarative, compact for 3-4 endpoints.
- **gRPC co-hosting:** gRPC server on Netty + HTTP server in same process.

## Consequences

- **Positive:** Small footprint, fast startup (HPA scaling), native async
- **Negative:** No Spring Data (direct MongoDB driver), no Spring Security (internal service, not needed), smaller community
- **Trade-off accepted:** Polyglot stack, but each service on best tool for its job
