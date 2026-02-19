# Block 3 — Repository Layer для Transfer Service

## Контекст проекта

**TransferHub** — платформа международных денежных переводов. Kotlin + Spring Boot 3.3.x, JDK 21.

**Sprint 1, Block 3.** Предыдущие блоки завершены:
- Block 1: Flyway-миграции создали таблицы `transfers`, `outbox_events`, `recipients`, `idempotency_keys`
- Block 2: Domain model создана: `Transfer`, `TransferStatus` (sealed class), `OutboxEvent`, `Recipient`, `IdempotencyRecord`, value objects (`Money`, `Corridor`, `DeliveryMethod`, enums)

## Задача

Создать Spring Data JPA репозитории для всех entity. Включая custom query для cursor-based pagination.

## Структура файлов

Создать в `services/transfer-service/src/main/kotlin/com/transferhub/transfer/`:

```
repository/
  TransferRepository.kt
  OutboxEventRepository.kt
  RecipientRepository.kt
  IdempotencyKeyRepository.kt
```

> Как и в Block 2: если пакетная структура уже другая — следуй ей.

---

## Что создать

### 1. TransferRepository.kt

```kotlin
package com.transferhub.transfer.repository

import com.transferhub.transfer.domain.model.Transfer
import com.transferhub.transfer.domain.model.TransferStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface TransferRepository : JpaRepository<Transfer, UUID> {

    /**
     * Найти перевод по ID. Kotlin nullable return — если нет, вернёт null.
     * Spring Data автоматически обрабатывает Optional → null для Kotlin.
     */
    fun findTransferById(id: UUID): Transfer?

    /** Все переводы конкретного отправителя */
    fun findBySenderIdOrderByCreatedAtDesc(senderId: UUID): List<Transfer>

    /** Проверить существование по idempotency key (быстрая проверка без загрузки entity) */
    fun existsByIdempotencyKey(idempotencyKey: UUID): Boolean

    /** Найти по idempotency key (для возврата cached response) */
    fun findByIdempotencyKey(idempotencyKey: UUID): Transfer?

    /**
     * Cursor-based pagination: первая страница (без курсора).
     *
     * Возвращает size+1 записей — если пришло size+1, значит есть следующая страница.
     * +1 trick избавляет от отдельного COUNT запроса.
     *
     * Использует index: idx_transfers_sender_created (sender_id, created_at DESC)
     */
    @Query("""
        SELECT t FROM Transfer t
        WHERE t.senderId = :senderId
        ORDER BY t.createdAt DESC, t.id DESC
    """)
    fun findBySenderIdFirstPage(
        @Param("senderId") senderId: UUID,
        @Param("limit") limit: org.springframework.data.domain.Pageable
    ): List<Transfer>

    /**
     * Cursor-based pagination: последующие страницы (с курсором).
     *
     * Cursor = (createdAt, id) последнего элемента предыдущей страницы.
     * Row-value comparison: (created_at, id) < (:cursorCreatedAt, :cursorId)
     * обеспечивает стабильную пагинацию даже при одинаковых created_at.
     *
     * EXPLAIN ANALYZE должен показать Index Scan по idx_transfers_sender_created.
     */
    @Query("""
        SELECT t FROM Transfer t
        WHERE t.senderId = :senderId
          AND (t.createdAt < :cursorCreatedAt
               OR (t.createdAt = :cursorCreatedAt AND t.id < :cursorId))
        ORDER BY t.createdAt DESC, t.id DESC
    """)
    fun findBySenderIdAfterCursor(
        @Param("senderId") senderId: UUID,
        @Param("cursorCreatedAt") cursorCreatedAt: Instant,
        @Param("cursorId") cursorId: UUID,
        @Param("limit") limit: org.springframework.data.domain.Pageable
    ): List<Transfer>

    /** Подсчёт активных переводов пользователя (для лимитов, если понадобится) */
    fun countBySenderIdAndStatusNotIn(senderId: UUID, statuses: List<TransferStatus>): Long
}
```

**Примечание по Pageable:** Spring Data JPA не поддерживает `LIMIT :size` в JPQL напрямую. Вместо этого передаём `Pageable.ofSize(size+1)` (PageRequest.of(0, size+1)). Это стандартный Spring Data способ ограничить результат.

**Альтернативный подход с native query (если JPQL row-value comparison не работает):**

Если Hibernate не поддерживает row-value comparison `(t.createdAt, t.id) < (...)` в JPQL, используй native SQL:

```kotlin
    @Query(
        value = """
            SELECT * FROM transfers t
            WHERE t.sender_id = :senderId
              AND (t.created_at, t.id) < (:cursorCreatedAt, CAST(:cursorId AS uuid))
            ORDER BY t.created_at DESC, t.id DESC
            LIMIT :limit
        """,
        nativeQuery = true
    )
    fun findBySenderIdAfterCursorNative(
        @Param("senderId") senderId: UUID,
        @Param("cursorCreatedAt") cursorCreatedAt: Instant,
        @Param("cursorId") cursorId: UUID,
        @Param("limit") limit: Int
    ): List<Transfer>
```

**Выбери один из двух подходов.** Native query надёжнее для PostgreSQL row-value comparison, JPQL — чище. Попробуй сначала JPQL, если не компилируется — переключись на native.

---

### 2. OutboxEventRepository.kt

```kotlin
package com.transferhub.transfer.repository

import com.transferhub.transfer.domain.model.OutboxEvent
import com.transferhub.transfer.domain.vo.OutboxEventStatus
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * Найти pending события для polling (Outbox Service в Sprint 2).
     * Пока нужен только для проверки — что событие записалось в outbox при создании перевода.
     */
    fun findByAggregateIdAndStatus(aggregateId: UUID, status: OutboxEventStatus): List<OutboxEvent>

    /** Все события конкретного перевода (для отладки и тестов) */
    fun findByAggregateIdOrderByCreatedAtAsc(aggregateId: UUID): List<OutboxEvent>
}
```

> **Примечание:** SELECT FOR UPDATE SKIP LOCKED для batch polling будет добавлен в Sprint 2 (Outbox Service). Сейчас нам нужен только базовый репозиторий.

---

### 3. RecipientRepository.kt

```kotlin
package com.transferhub.transfer.repository

import com.transferhub.transfer.domain.model.Recipient
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface RecipientRepository : JpaRepository<Recipient, UUID> {

    /** Найти получателя по ID */
    fun findRecipientById(id: UUID): Recipient?

    /** Все получатели конкретного отправителя */
    fun findBySenderIdOrderByCreatedAtDesc(senderId: UUID): List<Recipient>

    /** Проверка принадлежности: получатель принадлежит данному отправителю */
    fun existsByIdAndSenderId(id: UUID, senderId: UUID): Boolean
}
```

---

### 4. IdempotencyKeyRepository.kt

```kotlin
package com.transferhub.transfer.repository

import com.transferhub.transfer.domain.model.IdempotencyRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface IdempotencyKeyRepository : JpaRepository<IdempotencyRecord, UUID> {

    /**
     * Найти запись идемпотентности по ключу.
     * Если есть — возвращаем cached response, не выполняем операцию повторно.
     */
    fun findByIdempotencyKey(idempotencyKey: UUID): IdempotencyRecord?

    /** Проверка существования (быстрее чем загрузка entity) */
    fun existsByIdempotencyKey(idempotencyKey: UUID): Boolean

    /**
     * Удаление expired записей (cleanup job, вызывается по расписанию).
     * В Sprint 1 — не обязательно, но готовим интерфейс.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    fun deleteExpired(@Param("now") now: Instant): Int
}
```

> **Важно:** имя поля `idempotencyKey` должно совпадать с тем, что определено в `IdempotencyRecord` entity из Block 2. Также имя таблицы `idempotency_keys` — проверь entity @Table. Если в Block 1 миграция создала другое имя таблицы или колонки — Spring Data автоматически сгенерирует запрос по имени property entity, и он должен совпасть с @Column mapping.

---

## Проверка зависимостей в Gradle

В `build.gradle.kts` должны быть:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
runtimeOnly("org.postgresql:postgresql")
```

Если их нет — добавь. Без `spring-boot-starter-data-jpa` Spring Data JPA интерфейсы не будут auto-discovered.

---

## Проверка результата

1. Проект компилируется: `./gradlew :services:transfer-service:compileKotlin`
2. При старте приложения Spring Data находит все репозитории — в логах:
   ```
   Bootstrapping Spring Data JPA repositories in DEFAULT mode
   Finished Spring Data repository scanning in XXms. Found 4 JPA repository interfaces
   ```
3. Нет ошибок вроде `Not a managed type` (это значит entity не найден JPA — проверь @Entity и component scan).

## Чего НЕ делать

- Не создавай сервисный слой — Block 4
- Не пиши тесты репозиториев — Block 10 (integration tests)
- Не добавляй Redis — Block 7
- Не реализуй Outbox polling (SELECT FOR UPDATE SKIP LOCKED) — Sprint 2
