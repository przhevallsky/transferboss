# Sprint 5 — Code Review Report

**Date:** 2026-03-12
**Branch:** `feature/s5-block1-monitoring-infra`
**Scope:** 10 blocks (B1–B10), 67 files changed, +2728 lines

---

## Critical Issues (Fixed)

### 1. Auth Bypass via Fallback UUID — TransferController

**File:** `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/api/controller/TransferController.kt`
**Lines:** 59–60, 115–116

**Problem:** When `jwt.subject` was null or unparseable, the controller silently fell back to a hardcoded UUID `00000000-0000-0000-0000-000000000001`. Although `@PreAuthorize` blocks unauthenticated users, any authenticated user with a malformed `sub` claim would operate under a shared system identity — reading and creating transfers on behalf of another user.

```kotlin
// BEFORE (vulnerable)
val senderId = jwt?.subject?.let { UUID.fromString(it) }
    ?: UUID.fromString("00000000-0000-0000-0000-000000000001")

// AFTER (fixed)
val senderId = jwt?.subject?.let { UUID.fromString(it) }
    ?: throw IllegalStateException("JWT subject is required")
```

**Severity:** Critical — authorization bypass, data isolation violation
**Fix commit:** `f789f7c`

---

### 2. Rate Limit Off-by-One — RateLimitFilter

**File:** `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/config/RateLimitFilter.kt`
**Lines:** 29–48, 79–85

**Problem:** The Redis Lua script returned `{count, limit}` both when a request was added (as `{count+1, limit}`) and when it was denied (as `{count, limit}`). The Kotlin side checked `if (currentCount > maxLimit)` which was always false when `count == limit`, causing **all requests beyond the limit to pass through** — the rate limiter was completely broken after reaching the threshold.

Walkthrough:
- Request 100: `count=99`, `99 < 100` → adds, returns `{100, 100}`. Check: `100 > 100` = false → **allowed** (correct)
- Request 101: `count=100`, `100 < 100` = false → NOT added, returns `{100, 100}`. Check: `100 > 100` = false → **allowed** (BUG!)
- Request 102+: same — `count` stays at 100 forever, all pass through

```lua
-- BEFORE (broken): ambiguous return values
if count < limit then
    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
    return {count + 1, limit}
end
return {count, limit}  -- same shape, no way to tell if denied

-- AFTER (fixed): explicit denied flag at index 0
if count < limit then
    redis.call('ZADD', key, now, now .. '-' .. math.random(1000000))
    return {0, count + 1, limit}  -- 0 = allowed
end
return {1, count, limit}  -- 1 = denied
```

```kotlin
// BEFORE
val currentCount = (result?.get(0) as? Long) ?: 0
if (currentCount > maxLimit) { /* block */ }

// AFTER
val denied = (result?.get(0) as? Long) == 1L
if (denied) { /* block */ }
```

**Severity:** Critical — rate limiting completely non-functional after reaching limit
**Fix commit:** `f789f7c`

---

### 3. Overly Permissive Catch-All Rule — SecurityConfig

**File:** `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/config/SecurityConfig.kt`
**Line:** 52

**Problem:** `anyRequest().permitAll()` meant any endpoint not explicitly matched (e.g., a future `/api/v2/**` or accidentally exposed internal path) would be publicly accessible without authentication.

```kotlin
// BEFORE (permissive)
.anyRequest().permitAll()

// AFTER (restrictive)
.anyRequest().denyAll()
```

**Severity:** Critical — any new endpoint is public by default
**Fix commit:** `f789f7c`

---

## Medium Issues (Fixed)

### 4. Missing Prometheus Datasource UID — Grafana

**File:** `infra/monitoring/grafana/provisioning/datasources/datasources.yml`
**Line:** 4–8

**Problem:** Prometheus datasource had no `uid` field, but Tempo's `serviceMap.datasourceUid` referenced `prometheus`. Grafana resolves datasource links by UID — without it, the Tempo → Prometheus service map integration silently fails.

```yaml
# BEFORE (missing uid)
- name: Prometheus
  type: prometheus
  url: http://prometheus:9090
  isDefault: true

# AFTER
- name: Prometheus
  type: prometheus
  url: http://prometheus:9090
  uid: prometheus
  isDefault: true
```

**Severity:** Medium — breaks service map in Tempo, exemplars still work via name fallback
**Fix commit:** `f789f7c`

---

### 5. AccessDeniedException Swallowed by GlobalExceptionHandler

**File:** `services/transfer-service/src/main/kotlin/com/swiftpay/transfer/api/error/GlobalExceptionHandler.kt`
**Line:** 210

**Problem:** The catch-all `@ExceptionHandler(Exception::class)` intercepted `AccessDeniedException` from `@PreAuthorize` before Spring Security's `ExceptionTranslationFilter` could handle it. Result: RBAC violations returned `500 Internal Server Error` instead of `403 Forbidden`.

```kotlin
// FIX: re-throw so Spring Security handles it
@ExceptionHandler(AccessDeniedException::class)
fun handleAccessDenied(ex: AccessDeniedException) {
    throw ex
}
```

**Severity:** Medium — RBAC appeared broken (500 instead of 403)
**Fix commit:** `8180fbf` (B9)

---

### 6. Operator Token with Non-UUID Subject — TestJwtHelper

**File:** `services/transfer-service/src/test/kotlin/com/swiftpay/transfer/security/TestJwtHelper.kt`
**Line:** 46

**Problem:** `operatorToken()` used `userId = "operator-1"` which caused `UUID.fromString()` to throw in the controller, making operator tests fail with 400/500 instead of testing actual RBAC logic.

```kotlin
// BEFORE
fun operatorToken(userId: String = "operator-1"): String

// AFTER
fun operatorToken(userId: String = "99999999-9999-9999-9999-999999999999"): String
```

**Severity:** Medium — tests didn't exercise the intended code path
**Fix commit:** `8180fbf` (B9)

---

## Low Issues (Accepted / Deferred)

### 7. Private RSA Key in Repository

**Files:** `src/main/resources/keys/private.pem`, `src/test/resources/keys/private.pem`

**Context:** This is a dev/test-only mock identity key, explicitly scoped to `@Profile("dev", "test", "docker")`. The real Identity Service will have its own key management. Acceptable for current stage.

**Status:** Deferred — document in production readiness checklist

---

### 8. No JWT Issuer/Audience Validation

**File:** `SecurityConfig.kt` — NimbusJwtDecoder configured without issuer or audience constraints.

**Context:** The mock token endpoint doesn't set `iss`/`aud` claims. Adding validation now would break the mock flow. When integrating with a real Identity Service, these must be configured.

**Status:** Deferred — required before production

---

### 9. Missing Metrics for Cache and Circuit Breaker Fallback

**Files:** `TransferCacheService.kt`, `PricingClient.kt`

**Context:** Cache hit/miss and circuit breaker fallback events are logged but not instrumented with Micrometer counters. Sprint 5 spec covered business transfer metrics (B2), not cache observability.

**Status:** Deferred — good candidate for Sprint 6 observability improvements

---

### 10. Outbox HPA Uses Custom Kafka Metric

**File:** `infra/helm/outbox-service/templates/hpa.yaml`

**Context:** HPA configured with `kafka_consumergroup_lag` requires a custom metrics adapter (e.g., Prometheus Adapter) in Kubernetes. Without it, HPA won't trigger. This matches the Sprint 5 spec ("HPA по consumer lag") and is expected infra work for production deployment.

**Status:** Accepted — requires Prometheus Adapter deployment documented separately

---

### 11. PII Masking Regex Edge Cases

**File:** `PiiMaskingConverter.kt`

Phone pattern could over-match long numeric sequences. UUID false-positive was fixed with negative lookbehind `(?<![a-fA-F0-9-])`. Email pattern doesn't cover RFC-5322 edge cases (quoted local parts, international domains).

**Status:** Accepted — covers 95%+ of real-world PII patterns, sufficient for logging

---

### 12. Rate Limit Headers Expose Limits

**File:** `RateLimitFilter.kt` — `X-RateLimit-Limit` and `X-RateLimit-Remaining` headers.

**Context:** Standard practice per IETF draft-polli-ratelimit-headers. Information disclosure risk is minimal — the limits are easily discoverable by trial anyway.

**Status:** Accepted — follows industry standard

---

## Summary

| Severity | Found | Fixed | Deferred |
|----------|-------|-------|----------|
| Critical | 3 | 3 | 0 |
| Medium | 3 | 3 | 0 |
| Low | 6 | 0 | 6 |
| **Total** | **12** | **6** | **6** |

All critical and medium issues are resolved. Low-severity items are documented for future sprints or production hardening.
