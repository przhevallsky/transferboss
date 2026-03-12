# Memory Leak Investigation: transfer-service

**Date:** 2026-03-12
**Service:** transfer-service
**Severity:** P2 — Gradual memory exhaustion under sustained load
**Status:** RESOLVED

## Summary

The transfer-service experienced gradual heap memory growth under sustained load, eventually leading to OOM kills in production after ~48 hours of continuous traffic. Root cause was an unbounded `ConcurrentHashMap` used as an in-memory status cache.

## Timeline

| Time | Event |
|------|-------|
| T+0h | Service deployed with status cache feature |
| T+12h | Grafana alert: heap usage at 70% (threshold: 65%) |
| T+24h | Heap at 85%, GC pause times increasing (>500ms) |
| T+36h | First OOM kill, pod restarted by Kubernetes |
| T+42h | Second OOM kill, investigation initiated |
| T+48h | Root cause identified, fix deployed |

## Detection

### Symptoms
- Monotonically increasing heap memory on Grafana dashboard
- GC pause times growing from ~50ms to >500ms
- Prometheus alert: `jvm_memory_used_bytes{area="heap"}` exceeding 85% of max
- Kubernetes pod restarts due to OOM kills

### Monitoring Queries

Prometheus query that detected the leak:
```promql
rate(jvm_memory_used_bytes{area="heap", service="transfer-service"}[1h]) > 0
```

GC pressure query:
```promql
rate(jvm_gc_pause_seconds_sum{service="transfer-service"}[5m])
```

## Diagnosis

### Tools Used
1. **Grafana dashboards** — initial detection via heap memory and GC panels
2. **Prometheus alerts** — automated notification at 65% heap threshold
3. **Heap dump analysis** — `jcmd <pid> GC.heap_dump /tmp/heapdump.hprof`
4. **Eclipse MAT (Memory Analyzer Tool)** — heap dump analysis
5. **JFR (Java Flight Recorder)** — allocation profiling

### Heap Dump Analysis

Top retained objects from Eclipse MAT:

| Class | Retained Heap | Count |
|-------|--------------|-------|
| `ConcurrentHashMap$Node` | 1.2 GB | 4,200,000 |
| `java.util.UUID` | 340 MB | 4,200,000 |
| `java.lang.String` | 180 MB | 4,200,000 |

### Root Cause

`TransferStatusCache` used `ConcurrentHashMap<UUID, String>` with no eviction policy:

```kotlin
// BEFORE (leaky implementation)
@Component
class TransferStatusCache {
    // INTENTIONAL MEMORY LEAK — entries never evicted
    private val cache = ConcurrentHashMap<UUID, String>()

    fun put(transferId: UUID, status: String) {
        cache[transferId] = status  // grows unbounded
    }
}
```

Every status transition added an entry. With ~100K transfers/day, the map grew by ~100K entries daily. Each entry consumed ~350 bytes (UUID key + String value + Node overhead), leading to ~35 MB/day of uncollectable heap growth.

At 4.2M entries (after ~6 weeks of accumulated data across restarts with warm-up), the cache consumed ~1.47 GB of heap.

## Resolution

Replaced `ConcurrentHashMap` with Caffeine cache:

```kotlin
// AFTER (fixed implementation)
@Component
class TransferStatusCache(meterRegistry: MeterRegistry) {
    private val cache: Cache<UUID, String> = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofMinutes(5))
        .recordStats()
        .build()

    init {
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "transfer-status-cache")
    }
}
```

### Key Changes
1. **Bounded size**: Maximum 10,000 entries (LRU eviction)
2. **TTL**: Entries expire after 5 minutes (status lookups are time-sensitive)
3. **Metrics**: Caffeine stats exposed via Micrometer for monitoring hit ratio, evictions, size

## Before/After Metrics

| Metric | Before (leaked) | After (Caffeine) |
|--------|-----------------|-------------------|
| Heap after 24h | 2.1 GB (growing) | 450 MB (stable) |
| GC pause p99 | 520 ms | 45 ms |
| Cache entries | Unbounded (>1M) | ≤10,000 |
| Cache memory | ~350 MB+ | ~3.5 MB |
| Hit ratio | 100% (never evicted) | ~82% (acceptable) |

## Lessons Learned

1. **Never use unbounded collections as caches.** Always set max size and TTL.
2. **Monitor cache size as a metric.** Add cache.size gauges to all in-memory caches.
3. **Use Caffeine for JVM caches.** It provides eviction, TTL, and Micrometer integration out of the box.
4. **Set heap usage alerts.** Our 65% threshold alert gave us 24h of runway before OOM.
5. **Load test with realistic duration.** Short load tests miss slow leaks — run soak tests for 24h+.

## Prevention

- Added Caffeine cache metrics to Grafana transfer-service dashboard
- Added Prometheus alert for `caffeine_cache_estimated_size` approaching max
- Code review checklist updated: "Are all in-memory collections bounded?"
- Soak test added to CI pipeline: 4h sustained load test verifying stable heap
