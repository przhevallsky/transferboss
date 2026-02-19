# Contributing Guide — TransferHub

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

| Type | Когда использовать | Пример |
|------|--------------------|--------|
| `feat` | Новая функциональность | `feat(transfer): add cancel transfer endpoint` |
| `fix` | Исправление бага | `fix(outbox): fix duplicate event publishing on retry` |
| `refactor` | Рефакторинг без изменения поведения | `refactor(pricing): extract fee calculation to separate service` |
| `test` | Добавление/изменение тестов | `test(transfer): add integration test for idempotency` |
| `docs` | Документация | `docs: add ADR-013 for feature flags` |
| `ci` | CI/CD изменения | `ci: add integration test stage to GitHub Actions` |
| `chore` | Рутина (обновление зависимостей и т.п.) | `chore: update Spring Boot to 3.3.1` |
| `perf` | Оптимизация производительности | `perf(transfer): add index on transfers(user_id, created_at)` |
| `infra` | Инфраструктура (Docker, Helm, Terraform) | `infra: add Redis to Docker Compose` |

### Scope (опционально)

Указывает затронутый сервис или компонент: `transfer`, `pricing`, `outbox`, `notification`, `kafka`, `infra`, `helm`, `ci`, `docker`.

### Примеры хороших коммитов

```
feat(transfer): implement cursor-based pagination for transfers list

- Add CursorPageRequest and CursorPage DTOs
- Implement keyset pagination using WHERE id > :cursor
- Add integration test with 50 test records

Closes #42
```

```
fix(outbox): prevent message loss on polling failure

SELECT FOR UPDATE SKIP LOCKED was not releasing lock on exception.
Added explicit rollback in catch block.

Closes #67
```

## Branch Naming

Format: `<type>/<issue-number>-<short-description>`

| Пример | Описание |
|--------|----------|
| `feat/42-cursor-pagination` | Фича, привязана к Issue #42 |
| `fix/67-outbox-lock-release` | Баг-фикс |
| `refactor/89-extract-pricing-logic` | Рефакторинг |
| `infra/12-docker-compose-kafka` | Инфраструктурная задача |
| `test/100-transfer-saga-integration` | Тесты |

## Pull Request Process

1. Создай branch от `main` по конвенции выше
2. Реализуй изменения, напиши тесты
3. Запуш и создай PR — шаблон подставится автоматически
4. CI pipeline должен пройти (lint, test, build)
5. Все дискуссии resolved
6. Squash and merge → branch удаляется автоматически

## Definition of Done

Задача считается завершённой, когда:

- [ ] Код написан и компилируется без ошибок
- [ ] Unit tests написаны и проходят (coverage > 70% для нового кода)
- [ ] Integration tests написаны для API/Kafka endpoints (где применимо)
- [ ] Code review пройден
- [ ] Structured logging добавлен для ключевых операций
- [ ] Error handling: все ошибки обработаны, Problem Details для API
- [ ] OpenAPI документация обновлена (для REST endpoints)
- [ ] CI pipeline зелёный
- [ ] ADR создан (для архитектурных решений)
