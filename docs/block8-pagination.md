# Block 8 — Cursor-Based Pagination: доработка и верификация

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 8.** Blocks 1–7 завершены. Cursor-based pagination уже работает (заложена в Block 3 repository, Block 4 service, Block 5 controller). Этот блок — доработка, seed data для проверки, и верификация через EXPLAIN ANALYZE.

## Задача

1. Создать Flyway-миграцию с seed data (50+ переводов) для тестирования пагинации
2. Убедиться что composite index работает (EXPLAIN ANALYZE → Index Scan)
3. Доработать edge cases в pagination (пустой результат, невалидный cursor, size boundaries)
4. Добавить фильтрацию по статусу (опционально)

## Структура файлов

```
Создать:
  resources/db/migration/V003__seed_test_data.sql    — seed data (только для dev profile)

Изменить (при необходимости):
  repository/TransferRepository.kt     — доработка query если нужно
  service/TransferService.kt           — edge cases
  api/controller/TransferController.kt — валидация параметров
```

---

## 1. Seed Data Migration

### V003__seed_test_data.sql

Создай Flyway-миграцию которая вставляет тестовые данные. Это нужно для:
- Проверки пагинации на реальных данных
- EXPLAIN ANALYZE на таблице с записями (на пустой таблице план всегда Seq Scan)
- Ручного тестирования через curl

```sql
-- ================================================
-- SEED DATA: тестовые данные для разработки
-- Только для dev/test. В production не применяется.
-- ================================================

-- Тестовый отправитель
-- (senderId = 00000000-0000-0000-0000-000000000001, используется в контроллере как default)

-- Тестовый получатель
INSERT INTO recipients (id, sender_id, first_name, last_name, country, created_at, updated_at)
VALUES (
    '11111111-1111-1111-1111-111111111111',
    '00000000-0000-0000-0000-000000000001',
    'Maria', 'Santos', 'PH',
    now(), now()
) ON CONFLICT (id) DO NOTHING;

-- 50 тестовых переводов с разными статусами и датами
-- Генерируем через generate_series для разнообразия created_at
DO $$
DECLARE
    i INTEGER;
    statuses TEXT[] := ARRAY['CREATED', 'COMPLIANCE_CHECK', 'PAYMENT_CAPTURING', 'PROCESSING', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED'];
    delivery_methods TEXT[] := ARRAY['BANK_DEPOSIT', 'CASH_PICKUP', 'MOBILE_WALLET'];
    corridors TEXT[][] := ARRAY[ARRAY['US','PH','USD','PHP'], ARRAY['US','MX','USD','MXN'], ARRAY['GB','IN','GBP','INR']];
    corridor TEXT[];
    transfer_id UUID;
    status TEXT;
    dm TEXT;
    amount NUMERIC;
    created TIMESTAMPTZ;
BEGIN
    FOR i IN 1..50 LOOP
        transfer_id := gen_random_uuid();
        status := statuses[1 + (i % array_length(statuses, 1))];
        dm := delivery_methods[1 + (i % array_length(delivery_methods, 1))];
        corridor := corridors[1 + (i % array_length(corridors, 1))];
        amount := 50.00 + (i * 17.50);  -- разные суммы
        created := now() - (i || ' hours')::INTERVAL;  -- разные даты, от свежих к старым

        INSERT INTO transfers (
            id, idempotency_key, sender_id, quote_id,
            send_amount, send_currency, receive_amount, receive_currency,
            exchange_rate, fee_amount, fee_currency,
            source_country, dest_country, delivery_method,
            recipient_id, status, version,
            created_at, updated_at
        ) VALUES (
            transfer_id,
            gen_random_uuid(),  -- unique idempotency key
            '00000000-0000-0000-0000-000000000001',  -- тестовый sender
            gen_random_uuid(),  -- fake quote_id
            amount,
            corridor[3],    -- send_currency
            amount * 56.20, -- receive_amount (примерный курс)
            corridor[4],    -- receive_currency
            56.2000,        -- exchange_rate
            4.99,           -- fee
            corridor[3],    -- fee_currency
            corridor[1],    -- source_country
            corridor[2],    -- dest_country
            dm,
            '11111111-1111-1111-1111-111111111111',  -- тестовый recipient
            status,
            0,
            created,
            created
        );
    END LOOP;
END $$;
```

**Важно:** имена колонок должны совпадать с миграцией из Block 1 (V001/V002). Если в Block 1 колонки назывались иначе (например, `user_id` вместо `sender_id`) — адаптируй. Проверь реальную схему перед запуском.

**Примечание по Flyway:** seed data в Flyway миграции — допустимо для dev. В production — seed data обычно через отдельный механизм. Если хочешь разделить — используй Flyway callbacks или отдельную директорию `db/migration/dev/`.

---

## 2. Верификация Index: EXPLAIN ANALYZE

После применения seed data, подключись к PostgreSQL и выполни:

```sql
-- Проверка: первая страница (без cursor)
EXPLAIN ANALYZE
SELECT * FROM transfers
WHERE sender_id = '00000000-0000-0000-0000-000000000001'
ORDER BY created_at DESC, id DESC
LIMIT 21;

-- Ожидаемый план:
-- Limit  (cost=... rows=21)
--   -> Index Scan [Backward] using idx_transfers_sender_created on transfers
--        Index Cond: (sender_id = '...')
-- Execution Time: < 1ms
```

```sql
-- Проверка: страница с cursor (row-value comparison)
EXPLAIN ANALYZE
SELECT * FROM transfers
WHERE sender_id = '00000000-0000-0000-0000-000000000001'
  AND (created_at, id) < ('2025-01-14T10:00:00Z', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa')
ORDER BY created_at DESC, id DESC
LIMIT 21;

-- Ожидаемый план:
-- Limit
--   -> Index Scan [Backward] using idx_transfers_sender_created on transfers
--        Index Cond: (sender_id = '...' AND created_at <= '...')
--        Filter: (ROW(created_at, id) < ROW('...', '...'))
-- Execution Time: < 1ms
```

**Что искать:**
- ✅ `Index Scan` или `Index Scan Backward` — хорошо
- ❌ `Seq Scan` — плохо, индекс не используется (проверь имя индекса и колонки)
- ✅ `rows=21` в Limit — +1 trick работает
- ✅ `Execution Time: < 5ms` на 50 записях — нормально

**Если Seq Scan:** проверь что индекс создан в миграции Block 1:
```sql
-- Должен существовать:
SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'transfers';
-- Ищи: idx_transfers_sender_created ON transfers (sender_id, created_at DESC)
```

Если индекса нет — создай миграцию:
```sql
CREATE INDEX IF NOT EXISTS idx_transfers_sender_created
ON transfers (sender_id, created_at DESC, id DESC);
```

---

## 3. Edge Cases — доработка controller/service

Проверь и при необходимости доработай:

### В TransferController:

```kotlin
@GetMapping
fun listTransfers(
    @RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?,
    @RequestParam(required = false) cursor: String?,
    @RequestParam(defaultValue = "20") limit: Int,
    @RequestParam(required = false) status: String?   // опциональный фильтр по статусу
): ResponseEntity<PaginatedResponse<TransferResponse>> {

    // Валидация size
    if (limit < 1 || limit > 100) {
        throw IllegalArgumentException("limit must be between 1 and 100, got: $limit")
    }

    val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")
    val (transfers, nextCursor) = transferService.listTransfers(senderId, cursor, limit)

    val items = transfers.map { transfer ->
        val recipient = recipientRepository.findRecipientById(transfer.recipientId)
        transfer.toResponse(recipient)
    }

    return ResponseEntity.ok(
        PaginatedResponse(
            items = items,
            pagination = PaginationMeta(
                nextCursor = nextCursor,
                hasMore = nextCursor != null
            )
        )
    )
}
```

### Edge cases для проверки:

```bash
# Первая страница (без cursor)
curl "http://localhost:8080/api/v1/transfers?limit=5" \
  -H "X-Sender-Id: 00000000-0000-0000-0000-000000000001"
# → 5 items + has_more=true + next_cursor

# Вторая страница (с cursor)
curl "http://localhost:8080/api/v1/transfers?limit=5&cursor={next_cursor_from_above}" \
  -H "X-Sender-Id: 00000000-0000-0000-0000-000000000001"
# → следующие 5 items

# Последняя страница
# → items < limit, has_more=false, next_cursor=null

# Пустой результат (несуществующий sender)
curl "http://localhost:8080/api/v1/transfers?limit=5" \
  -H "X-Sender-Id: 99999999-9999-9999-9999-999999999999"
# → items=[], has_more=false, next_cursor=null

# Невалидный cursor
curl "http://localhost:8080/api/v1/transfers?limit=5&cursor=not-base64"
# → 400 Problem Details "Invalid cursor format"

# limit out of range
curl "http://localhost:8080/api/v1/transfers?limit=500"
# → 400 "limit must be between 1 and 100"

# limit=1 (минимальная страница)
curl "http://localhost:8080/api/v1/transfers?limit=1"
# → 1 item + has_more=true
```

---

## 4. N+1 проблема — заметка

Текущая реализация загружает recipient для каждого transfer в цикле:

```kotlin
val items = transfers.map { transfer ->
    val recipient = recipientRepository.findRecipientById(transfer.recipientId)
    transfer.toResponse(recipient)
}
```

Это **N+1 query** — для 20 transfers будет 1 query за transfers + 20 queries за recipients. На 50 записях незаметно, на production — проблема.

**Не исправляй сейчас.** Это осознанный технический долг для Sprint 1 MVP. Решение (batch load recipients):

```kotlin
// Будущий fix (Sprint 2+):
val recipientIds = transfers.map { it.recipientId }.toSet()
val recipientsMap = recipientRepository.findAllById(recipientIds).associateBy { it.id }
val items = transfers.map { it.toResponse(recipientsMap[it.recipientId]) }
```

Зафиксируй как TODO или tech debt item — на собеседовании это хороший пример: «Мы знали про N+1, заложили в tech debt, исправили в Sprint 2 через batch load — latency списка снизилась с 150ms до 30ms».

---

## Проверка результата

1. `./gradlew :services:transfer-service:flywayMigrate` (или запуск приложения) — seed data применён
2. EXPLAIN ANALYZE показывает Index Scan для обоих запросов (с cursor и без)
3. Пагинация работает через curl — все edge cases из раздела 3
4. Невалидный cursor → 400 Problem Details
5. Пустой результат → items=[], has_more=false

## Чего НЕ делать

- Не исправляй N+1 — tech debt для Sprint 2
- Не добавляй фильтр по статусу в SQL — отдельная задача, если нужна
- Не пиши тесты — Block 9, 10
