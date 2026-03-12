# ADR-004: Go for Notification Gateway

**Status:** Accepted
**Date:** 2025-11-01
**Deciders:** Daniel (Tech Lead), Sergey (Go developer)

## Context

Notification Gateway needs to consume Kafka events and fan-out to multiple delivery channels (SMS, Push, Email). High throughput, minimal resource footprint.

## Options

1. **Kotlin/Spring Boot** — consistency with rest of stack
2. **Go** — minimal footprint, goroutines, fast startup

## Decision

Go 1.23 with segmentio/kafka-go.

## Rationale

- **Docker image:** ~15MB (scratch) vs ~200MB (JRE-alpine). 13x smaller.
- **Memory:** 64Mi request vs 512Mi for JVM services. 8x less.
- **Startup:** <1s vs 5-8s for Spring Boot. Critical for HPA autoscaling.
- **Goroutines:** Lightweight concurrent processing (100K+ goroutines) vs JVM thread pools.
- **No JVM GC pauses:** Predictable latency for high-throughput fan-out.

## Consequences

- **Positive:** Minimal resource usage, fast scaling, predictable latency
- **Negative:** No CooperativeStickyAssignor support (segmentio/kafka-go limitation), separate build toolchain, no Spring ecosystem
