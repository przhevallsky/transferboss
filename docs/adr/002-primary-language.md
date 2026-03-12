# ADR-002: Kotlin as Primary Language

**Status:** Accepted
**Date:** 2025-10-15
**Deciders:** Daniel (Tech Lead), team

## Context

Need a primary language for backend services running on JVM with Spring Boot and Ktor.

## Options

1. **Java 21** — standard, largest ecosystem, virtual threads
2. **Kotlin 1.9** — null safety, data classes, coroutines, sealed classes
3. **Scala 3** — functional programming, type system

## Decision

Kotlin 1.9.25 on Java 21.

## Rationale

- **Null safety:** Compile-time null checks eliminate NPE. Critical for financial code where null amount = silent bug
- **Data classes:** Perfect for DTOs and value objects (`CreateTransferCommand`, `TransferResponse`)
- **Sealed classes:** State machine (`TransferStatus`) with exhaustive when-expressions — compiler enforces handling all states
- **Coroutines:** Native async for Ktor (Pricing Service), gRPC streaming, MongoDB driver
- **Spring compatibility:** First-class Spring Boot support (`kotlin-spring` plugin, `kotlin-jpa` plugin)

## Consequences

- **Positive:** Concise code, fewer runtime errors, excellent Spring/Ktor integration
- **Negative:** Smaller talent pool vs Java, some Hibernate quirks (need `kotlin-jpa` for no-arg constructors)
