# Block 1 — Flyway миграции PostgreSQL для Transfer Service

## Контекст проекта

Ты работаешь над **TransferHub** — платформой международных денежных переводов. Это микросервисная архитектура на Kotlin + Spring Boot.

Сейчас мы в **Sprint 1**. Sprint 0 завершён: Gradle-проект Transfer Service уже существует (Kotlin DSL, Spring Boot 3.3.x, JDK 21). Docker Compose поднимает PostgreSQL 16 на `localhost:5432`, база `transferhub`, пользователь `transferhub` / пароль `transferhub`.

## Задача

Создать Flyway-миграции для 4 таблиц Transfer Service в PostgreSQL. Миграции должны запускаться автоматически при старте Spring Boot приложения.

## Что нужно сделать

### 1. Добавить зависимость Flyway в Gradle (если ещё нет)

В `build.gradle.kts` Transfer Service убедиться, что есть:
```kotlin
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

В `application.yml` добавить (если нет):
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/transferhub
    username: transferhub
    password: transferhub
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

### 2. Создать 4 миграции

Путь: `services/transfer-service/src/main/resources/db/migration/`

#### V001__create_transfers_table.sql

```sql
CREATE TABLE transfers (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Идемпотентность
    idempotency_key   UUID            NOT NULL,

    -- Отправитель
    sender_id         UUID            NOT NULL,

    -- Котировка
    quote_id          UUID            NOT NULL,

    -- Финансовые данные
    send_amount       NUMERIC(15,2)   NOT NULL,
    send_currency     CHAR(3)         NOT NULL,
    receive_amount    NUMERIC(15,2)   NOT NULL,
    receive_currency  CHAR(3)         NOT NULL,
    exchange_rate     NUMERIC(12,6)   NOT NULL,
    fee_amount        NUMERIC(10,2)   NOT NULL,
    fee_currency      CHAR(3)         NOT NULL,

    -- Маршрут
    source_country    CHAR(2)         NOT NULL,
    dest_country      CHAR(2)         NOT NULL,
    delivery_method   VARCHAR(30)     NOT NULL,

    -- Получатель
    recipient_id      UUID            NOT NULL,

    -- Состояние
    status            VARCHAR(30)     NOT NULL DEFAULT 'CREATED',
    status_reason     TEXT,

    -- Saga tracking
    payment_id        UUID,
    payout_id         UUID,

    -- Optimistic locking
    version           INTEGER         NOT NULL DEFAULT 0,

    -- Аудит
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,

    -- Constraints
    CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_transfers_status CHECK (status IN (
        'CREATED', 'PAYMENT_PENDING', 'PAYMENT_CAPTURED', 'PAYMENT_FAILED',
        'COMPLIANCE_CHECK', 'COMPLIANCE_HOLD', 'COMPLIANCE_REJECTED',
        'PAYOUT_PENDING', 'DELIVERING', 'COMPLETED',
        'FAILED', 'CANCELLED', 'REFUND_PENDING', 'REFUNDED'
    )),
    CONSTRAINT chk_transfers_send_amount CHECK (send_amount > 0),
    CONSTRAINT chk_transfers_receive_amount CHECK (receive_amount > 0),
    CONSTRAINT chk_transfers_fee_amount CHECK (fee_amount >= 0)
);

-- Индексы

-- Поиск переводов пользователя (cursor-based pagination)
-- Составной индекс покрывает фильтрацию по sender_id + сортировку по created_at DESC → Index Only Scan
CREATE INDEX idx_transfers_sender_created
    ON transfers (sender_id, created_at DESC);

-- Мониторинг зависших переводов — partial index: только активные (~5% от общего числа)
CREATE INDEX idx_transfers_status
    ON transfers (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED');

-- Корреляция с Payment Service событиями
CREATE INDEX idx_transfers_payment_id
    ON transfers (payment_id)
    WHERE payment_id IS NOT NULL;

-- Корреляция с Payout Service событиями
CREATE INDEX idx_transfers_payout_id
    ON transfers (payout_id)
    WHERE payout_id IS NOT NULL;

-- Аналитика: переводы по коридору за период
CREATE INDEX idx_transfers_corridor_date
    ON transfers (source_country, dest_country, created_at DESC);
```

#### V002__create_recipients_table.sql

```sql
CREATE TABLE recipients (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id         UUID            NOT NULL,

    -- Данные получателя
    first_name        VARCHAR(100)    NOT NULL,
    last_name         VARCHAR(100)    NOT NULL,
    country           CHAR(2)         NOT NULL,

    -- Реквизиты доставки — JSONB т.к. разные delivery methods имеют разный набор полей:
    -- bank_deposit: {"bank_name": "BDO", "account_number": "123", "branch_code": "001"}
    -- mobile_wallet: {"provider": "GCASH", "phone_number": "+639171234567"}
    -- cash_pickup: {"pickup_network": "CEBUANA", "id_type": "PASSPORT"}
    delivery_details  JSONB           NOT NULL,

    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    is_active         BOOLEAN         NOT NULL DEFAULT true
);

-- Partial index: только активных получателей (мягкое удаление через is_active=false)
CREATE INDEX idx_recipients_sender
    ON recipients (sender_id)
    WHERE is_active = true;
```

#### V003__create_outbox_table.sql

```sql
CREATE TABLE outbox (
    id                UUID            PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Какой агрегат породил событие
    entity_type       VARCHAR(50)     NOT NULL,   -- 'TRANSFER'
    entity_id         UUID            NOT NULL,   -- transfer_id (используется как Kafka key)

    -- Событие
    event_type        VARCHAR(100)    NOT NULL,   -- 'transfer.created', 'transfer.status_changed'
    payload           JSONB           NOT NULL,

    -- Обработка
    status            VARCHAR(20)     NOT NULL DEFAULT 'PENDING',

    -- Метаданные
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    kafka_topic       VARCHAR(200),
    kafka_offset      BIGINT,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

-- Основной индекс для Outbox polling: PENDING события в порядке создания
-- Используется Outbox Service: SELECT ... WHERE status='PENDING' ORDER BY created_at FOR UPDATE SKIP LOCKED
CREATE INDEX idx_outbox_pending
    ON outbox (created_at ASC)
    WHERE status = 'PENDING';

-- Для scheduled cleanup job: DELETE обработанных событий старше 7 дней
CREATE INDEX idx_outbox_processed
    ON outbox (processed_at)
    WHERE status = 'SENT';
```

#### V004__create_idempotency_keys_table.sql

```sql
CREATE TABLE idempotency_keys (
    key               UUID            PRIMARY KEY,
    transfer_id       UUID            NOT NULL REFERENCES transfers(id),
    response_status   INTEGER         NOT NULL,   -- HTTP status code (201, 200)
    response_body     JSONB           NOT NULL,   -- Cached API response
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ     NOT NULL DEFAULT now() + INTERVAL '24 hours'
);

-- Для scheduled cleanup: удаление expired keys
CREATE INDEX idx_idempotency_expires
    ON idempotency_keys (expires_at);
```

### 3. Проверка

После создания файлов:
1. Запусти Transfer Service (или `./gradlew bootRun`)
2. В логах должно быть:
   ```
   Flyway Community Edition ...
   Successfully applied 4 migrations to schema "public"
   ```
3. Подключись к PostgreSQL и проверь:
   ```sql
   \dt         -- должны быть: transfers, recipients, outbox, idempotency_keys, flyway_schema_history
   \di         -- проверить наличие всех индексов
   ```

## Важные замечания

- **Naming convention**: файлы `V001__description.sql` — два подчёркивания между версией и описанием. Flyway строг к этому.
- **Forward-only**: мы не пишем rollback-скриптов. Каждая миграция должна быть безопасна и идемпотентна по смыслу.
- **NUMERIC для денег**: НИКОГДА не FLOAT/DOUBLE. NUMERIC(15,2) — 15 знаков всего, 2 после запятой. Для exchange_rate — NUMERIC(12,6).
- **UUID для PK**: gen_random_uuid() — встроенная функция PostgreSQL 13+. Не нужно расширение pgcrypto.
- **TIMESTAMPTZ**: всегда с timezone. Не TIMESTAMP WITHOUT TIME ZONE.
- **Partial indexes**: idx_transfers_status индексирует только ~5% записей (активные переводы), экономя место и ускоряя запросы мониторинга.
