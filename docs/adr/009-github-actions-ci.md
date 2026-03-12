# ADR-009: GitHub Actions for CI/CD

**Status:** Accepted
**Date:** 2025-10-15

## Decision

GitHub Actions with per-service change detection (dorny/paths-filter) and CI Gate pattern.

## Rationale

- **Integrated:** No external CI server. Pipeline defined in `.github/workflows/ci.yml`.
- **Change detection:** Only build/test changed services (saves ~60% CI time on average)
- **CI Gate:** Single required status check for branch protection. Handles skipped jobs gracefully.
- **Caching:** Gradle dependency cache shared across runs
- **Parallelism:** Per-service jobs run in parallel (compile → test → build)

## Consequences

- GitHub-specific (vendor lock-in). Mitigated: pipeline is simple, portable to GitLab CI.
- Limited build minutes on free tier. Not a concern for this project scale.
