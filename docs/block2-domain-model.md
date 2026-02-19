# Block 2 — Domain Model (Kotlin) для Transfer Service

## Контекст проекта

Ты работаешь над **TransferHub** — платформой международных денежных переводов. Микросервисная архитектура на Kotlin + Spring Boot.

**Sprint 1, Block 2.** Block 1 завершён: Flyway-миграции создали 4 таблицы в PostgreSQL — `transfers`, `outbox_events`, `recipients`, `idempotency_keys`. Таблицы уже существуют в БД.

Проект: `services/transfer-service/`, Kotlin 2.0, Spring Boot 3.3.x, JDK 21, Gradle Kotlin DSL.

## Задача

Создать полную доменную модель Transfer Service: JPA-entities, sealed class для state machine, value objects, enums. Код должен быть идиоматическим Kotlin — не "Java с другим синтаксисом".

## Структура пакетов

Все файлы создавать в `services/transfer-service/src/main/kotlin/com/transferhub/transfer/`:

```
domain/
  model/
    Transfer.kt              — JPA entity
    TransferStatus.kt         — sealed class (state machine)
    OutboxEvent.kt            — JPA entity
    Recipient.kt              — JPA entity
    IdempotencyRecord.kt      — JPA entity
  vo/
    Money.kt                  — value object
    Corridor.kt               — value object
    DeliveryMethod.kt         — enum
    OutboxEventStatus.kt      — enum
    OutboxEventType.kt        — enum
```

> **Важно:** если пакетная структура в проекте уже отличается от предложенной (например, `com.transferhub.transfers` вместо `com.transferhub.transfer`), следуй существующей структуре. Проверь, что уже есть в `src/main/kotlin/`.

## Что нужно создать

---

### 1. Value Objects (создать первыми — от них зависят entities)

#### Money.kt

```kotlin
package com.transferhub.transfer.domain.vo

import java.math.BigDecimal

/**
 * Value object для денежных сумм.
 * КРИТИЧЕСКИ ВАЖНО: в финтехе деньги — ТОЛЬКО BigDecimal.
 * Double/Float запрещены: 0.1 + 0.2 != 0.3 в floating point.
 */
data class Money(
    val amount: BigDecimal,
    val currency: String   // ISO 4217: USD, EUR, GBP, PHP, MXN, INR
) {
    init {
        require(currency.length == 3) { "Currency must be ISO 4217 (3 chars), got: $currency" }
        // amount может быть 0 (для fee = 0) но не отрицательным
        require(amount >= BigDecimal.ZERO) { "Amount must be non-negative, got: $amount" }
    }

    /** Проверка что сумма строго положительная (для send amount) */
    fun isPositive(): Boolean = amount > BigDecimal.ZERO

    override fun toString(): String = "$amount $currency"
}
```

#### Corridor.kt

```kotlin
package com.transferhub.transfer.domain.vo

/**
 * Коридор перевода: откуда → куда.
 * Определяет доступные delivery methods, fee structure, compliance rules.
 */
data class Corridor(
    val sourceCountry: String,  // ISO 3166-1 alpha-2: US, GB
    val destCountry: String     // ISO 3166-1 alpha-2: PH, MX, IN
) {
    init {
        require(sourceCountry.length == 2) { "Source country must be ISO 3166-1 alpha-2, got: $sourceCountry" }
        require(destCountry.length == 2) { "Dest country must be ISO 3166-1 alpha-2, got: $destCountry" }
    }

    /** Строковый идентификатор коридора: "US_PH", "GB_IN" */
    val id: String get() = "${sourceCountry}_${destCountry}"

    override fun toString(): String = "$sourceCountry → $destCountry"
}
```

#### DeliveryMethod.kt

```kotlin
package com.transferhub.transfer.domain.vo

/**
 * Способ получения денег.
 * Разные коридоры поддерживают разные delivery methods.
 */
enum class DeliveryMethod {
    BANK_DEPOSIT,     // На банковский счёт
    CASH_PICKUP,      // Наличные в пункте выдачи
    MOBILE_WALLET;    // На мобильный кошелёк (GCash, M-Pesa)

    companion object {
        fun fromString(value: String): DeliveryMethod =
            entries.find { it.name == value.uppercase() }
                ?: throw IllegalArgumentException("Unknown delivery method: $value. Allowed: ${entries.map { it.name }}")
    }
}
```

#### OutboxEventStatus.kt

```kotlin
package com.transferhub.transfer.domain.vo

/** Статус события в outbox-таблице */
enum class OutboxEventStatus {
    PENDING,    // Ожидает отправки в Kafka
    SENT,       // Успешно отправлено
    FAILED      // Ошибка отправки (будет retry)
}
```

#### OutboxEventType.kt

```kotlin
package com.transferhub.transfer.domain.vo

/** Типы событий, которые публикует Transfer Service */
enum class OutboxEventType {
    TRANSFER_CREATED,
    TRANSFER_STATUS_CHANGED,
    PAYMENT_REQUESTED,
    COMPLIANCE_REQUESTED,
    PAYOUT_REQUESTED,
    REFUND_REQUESTED
}
```

---

### 2. TransferStatus — sealed class (State Machine)

Это ключевой элемент доменной модели. Sealed class в Kotlin даёт compile-time safety: компилятор заставляет обработать все варианты в `when`, что предотвращает баги с забытыми статусами.

#### TransferStatus.kt

```kotlin
package com.transferhub.transfer.domain.model

/**
 * State machine перевода. Sealed class гарантирует:
 * 1. Exhaustive when — компилятор проверяет, что все статусы обработаны
 * 2. Допустимые переходы зашиты в модель — невозможно перейти из COMPLETED в CREATED
 *
 * Жизненный цикл:
 * CREATED → COMPLIANCE_CHECK → COMPLIANCE_HOLD? → PAYMENT_CAPTURING → PROCESSING →
 * → IN_TRANSIT → AVAILABLE_FOR_PICKUP? → COMPLETED
 *
 * Ошибочные пути: PAYMENT_FAILED, PAYOUT_FAILED → REFUNDED, REJECTED, CANCELLED
 */
sealed class TransferStatus(val value: String) {

    // --- Happy path ---
    data object Created : TransferStatus("CREATED")
    data object ComplianceCheck : TransferStatus("COMPLIANCE_CHECK")
    data object ComplianceHold : TransferStatus("COMPLIANCE_HOLD")
    data object PaymentCapturing : TransferStatus("PAYMENT_CAPTURING")
    data object PaymentFailed : TransferStatus("PAYMENT_FAILED")
    data object Processing : TransferStatus("PROCESSING")
    data object InTransit : TransferStatus("IN_TRANSIT")
    data object AvailableForPickup : TransferStatus("AVAILABLE_FOR_PICKUP")
    data object Completed : TransferStatus("COMPLETED")

    // --- Error / Compensation ---
    data object PayoutFailed : TransferStatus("PAYOUT_FAILED")
    data object Rejected : TransferStatus("COMPLIANCE_REJECTED")
    data object Cancelled : TransferStatus("CANCELLED")
    data object RefundPending : TransferStatus("REFUND_PENDING")
    data object Refunded : TransferStatus("REFUNDED")

    /**
     * Допустимые переходы из текущего статуса.
     * Если статус терминальный (COMPLETED, REFUNDED, etc.) — пустой set.
     */
    fun allowedTransitions(): Set<TransferStatus> = when (this) {
        Created -> setOf(ComplianceCheck, Cancelled)
        ComplianceCheck -> setOf(ComplianceHold, PaymentCapturing, Rejected)
        ComplianceHold -> setOf(PaymentCapturing, Rejected)
        PaymentCapturing -> setOf(Processing, PaymentFailed)
        PaymentFailed -> emptySet()  // Terminal
        Processing -> setOf(InTransit)
        InTransit -> setOf(AvailableForPickup, Completed, PayoutFailed)
        AvailableForPickup -> setOf(Completed)
        PayoutFailed -> setOf(RefundPending)
        RefundPending -> setOf(Refunded)

        // Terminal states — нет допустимых переходов
        Completed -> emptySet()
        Rejected -> emptySet()
        Cancelled -> emptySet()
        Refunded -> emptySet()
    }

    /** Можно ли перейти в указанный статус? */
    fun canTransitionTo(target: TransferStatus): Boolean =
        target in allowedTransitions()

    /** Является ли статус терминальным (финальным)? */
    fun isTerminal(): Boolean = allowedTransitions().isEmpty()

    /** Нужно ли показывать клиенту как "Processing" (скрытые внутренние статусы) */
    fun displayStatus(): String = when (this) {
        ComplianceCheck -> "PROCESSING"
        ComplianceHold -> "UNDER_REVIEW"
        PaymentCapturing -> "PROCESSING"
        RefundPending -> "REFUNDING"
        else -> value
    }

    companion object {
        /** Парсинг из строки БД */
        fun fromString(value: String): TransferStatus = when (value) {
            "CREATED" -> Created
            "COMPLIANCE_CHECK" -> ComplianceCheck
            "COMPLIANCE_HOLD" -> ComplianceHold
            "PAYMENT_CAPTURING" -> PaymentCapturing
            "PAYMENT_FAILED" -> PaymentFailed
            "PROCESSING" -> Processing
            "IN_TRANSIT" -> InTransit
            "AVAILABLE_FOR_PICKUP" -> AvailableForPickup
            "COMPLETED" -> Completed
            "PAYOUT_FAILED" -> PayoutFailed
            "COMPLIANCE_REJECTED" -> Rejected
            "CANCELLED" -> Cancelled
            "REFUND_PENDING" -> RefundPending
            "REFUNDED" -> Refunded
            else -> throw IllegalArgumentException("Unknown transfer status: $value")
        }

        /** Все возможные статусы (для валидации, тестов) */
        val ALL: List<TransferStatus> = listOf(
            Created, ComplianceCheck, ComplianceHold, PaymentCapturing,
            PaymentFailed, Processing, InTransit, AvailableForPickup,
            Completed, PayoutFailed, Rejected, Cancelled, RefundPending, Refunded
        )
    }

    override fun toString(): String = value
}
```

---

### 3. JPA-конвертер для TransferStatus

JPA не знает о sealed class — нужен конвертер для маппинга `sealed class ↔ VARCHAR` в БД.

Создать файл `domain/model/TransferStatusConverter.kt`:

```kotlin
package com.transferhub.transfer.domain.model

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * JPA конвертер: TransferStatus sealed class ↔ VARCHAR в PostgreSQL.
 * autoApply = true: применяется ко всем полям типа TransferStatus автоматически.
 */
@Converter(autoApply = true)
class TransferStatusConverter : AttributeConverter<TransferStatus, String> {

    override fun convertToDatabaseColumn(attribute: TransferStatus?): String? =
        attribute?.value

    override fun convertToEntityAttribute(dbData: String?): TransferStatus? =
        dbData?.let { TransferStatus.fromString(it) }
}
```

---

### 4. JPA Entities

#### Важные правила JPA + Kotlin:

1. **Kotlin-JPA плагин** (`kotlin("plugin.jpa")` в build.gradle.kts) — генерирует no-arg конструкторы для `@Entity` классов. Без него JPA не сможет создавать экземпляры. **Проверь, что плагин есть в build.gradle.kts.** Если нет — добавь:
   ```kotlin
   plugins {
       kotlin("plugin.jpa") version "2.0.x" // версия должна совпадать с kotlin
   }
   ```

2. **Kotlin-Spring плагин** (`kotlin("plugin.spring")`) — делает Spring-бины open. Тоже нужен.

3. **Класс entity НЕ должен быть data class** — JPA entity работает через identity (id), а не через structural equality. `data class` переопределяет `equals/hashCode` по всем полям, что ломает JPA proxy, lazy loading и работу в HashSet/HashMap.

4. **Свойства — `var`**, не `val` — JPA должен мочь мутировать entity через сеттеры.

#### Transfer.kt

```kotlin
package com.transferhub.transfer.domain.model

import com.transferhub.transfer.domain.vo.DeliveryMethod
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Основная сущность — международный денежный перевод.
 *
 * Маппится на таблицу `transfers` (Flyway V001).
 * Optimistic locking через @Version — защита от конкурентных обновлений статуса.
 */
@Entity
@Table(name = "transfers")
class Transfer(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    // --- Идемпотентность ---
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    val idempotencyKey: UUID,

    // --- Отправитель ---
    @Column(name = "sender_id", nullable = false, updatable = false)
    val senderId: UUID,

    // --- Котировка ---
    @Column(name = "quote_id", nullable = false, updatable = false)
    val quoteId: UUID,

    // --- Финансовые данные (immutable после создания) ---
    @Column(name = "send_amount", nullable = false, precision = 15, scale = 2)
    val sendAmount: BigDecimal,

    @Column(name = "send_currency", nullable = false, length = 3)
    val sendCurrency: String,

    @Column(name = "receive_amount", nullable = false, precision = 15, scale = 2)
    val receiveAmount: BigDecimal,

    @Column(name = "receive_currency", nullable = false, length = 3)
    val receiveCurrency: String,

    @Column(name = "exchange_rate", nullable = false, precision = 12, scale = 6)
    val exchangeRate: BigDecimal,

    @Column(name = "fee_amount", nullable = false, precision = 10, scale = 2)
    val feeAmount: BigDecimal,

    @Column(name = "fee_currency", nullable = false, length = 3)
    val feeCurrency: String,

    // --- Маршрут ---
    @Column(name = "source_country", nullable = false, length = 2)
    val sourceCountry: String,

    @Column(name = "dest_country", nullable = false, length = 2)
    val destCountry: String,

    @Column(name = "delivery_method", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    val deliveryMethod: DeliveryMethod,

    // --- Получатель ---
    @Column(name = "recipient_id", nullable = false)
    val recipientId: UUID,

    // --- Состояние (mutable) ---
    @Column(name = "status", nullable = false, length = 30)
    @Convert(converter = TransferStatusConverter::class)
    var status: TransferStatus = TransferStatus.Created,

    @Column(name = "status_reason")
    var statusReason: String? = null,

    // --- Saga tracking (заполняются позже, при получении событий) ---
    @Column(name = "payment_id")
    var paymentId: UUID? = null,

    @Column(name = "payout_id")
    var payoutId: UUID? = null,

    // --- Optimistic locking ---
    @Version
    @Column(name = "version", nullable = false)
    var version: Int = 0,

    // --- Аудит ---
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null

) {

    /**
     * Переход в новый статус с валидацией state machine.
     * Выбрасывает exception при недопустимом переходе.
     *
     * @param newStatus целевой статус
     * @param reason причина перехода (для FAILED, CANCELLED, COMPLIANCE_HOLD)
     * @throws IllegalStateException если переход из текущего статуса в целевой не допустим
     */
    fun transitionTo(newStatus: TransferStatus, reason: String? = null) {
        check(status.canTransitionTo(newStatus)) {
            "Invalid status transition: ${status.value} → ${newStatus.value}. " +
                "Allowed transitions from ${status.value}: ${status.allowedTransitions().map { it.value }}"
        }
        status = newStatus
        statusReason = reason
        updatedAt = Instant.now()

        if (newStatus.isTerminal()) {
            completedAt = Instant.now()
        }
    }

    /** Можно ли отменить перевод? Только из CREATED */
    fun isCancellable(): Boolean = status.canTransitionTo(TransferStatus.Cancelled)

    // --- equals / hashCode по id (JPA best practice) ---

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Transfer) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Transfer(id=$id, status=${status.value}, sendAmount=$sendAmount $sendCurrency → $receiveAmount $receiveCurrency)"
}
```

#### OutboxEvent.kt

```kotlin
package com.transferhub.transfer.domain.model

import com.transferhub.transfer.domain.vo.OutboxEventStatus
import com.transferhub.transfer.domain.vo.OutboxEventType
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Событие в outbox-таблице (Transactional Outbox Pattern).
 *
 * Записывается в ОДНОЙ транзакции с бизнес-данными (Transfer).
 * Outbox Service поллит эту таблицу и отправляет события в Kafka.
 *
 * Маппится на таблицу `outbox_events` (Flyway V002).
 */
@Entity
@Table(name = "outbox_events")
class OutboxEvent(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    /** Тип агрегата, к которому относится событие */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    val aggregateType: String = "Transfer",

    /** ID агрегата (transfer_id) — используется как Kafka key для ordering */
    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: UUID,

    /** Тип события */
    @Column(name = "event_type", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val eventType: OutboxEventType,

    /** JSON payload события (JSONB в PostgreSQL) */
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    val payload: String,

    /** Статус обработки */
    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: OutboxEventStatus = OutboxEventStatus.PENDING,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEvent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "OutboxEvent(id=$id, type=${eventType.name}, aggregateId=$aggregateId, status=${status.name})"
}
```

> **Важно:** имена колонок (`aggregate_type`, `aggregate_id`, `event_type`, `payload`, `status`) должны совпадать с тем, что создано в Flyway-миграции V002. Если в миграции колонки названы иначе (например, `entity_type` вместо `aggregate_type`), адаптируй `@Column(name = "...")` под реальную схему. **Проверь файл V002 перед созданием entity.**

#### Recipient.kt

```kotlin
package com.transferhub.transfer.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Получатель перевода.
 *
 * Хранит данные, необходимые для выплаты.
 * В MVP — управляется через Transfer Service.
 * В будущем — может быть вынесен в отдельный Recipient Service.
 *
 * Маппится на таблицу `recipients` (Flyway V003).
 */
@Entity
@Table(name = "recipients")
class Recipient(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    /** Владелец (отправитель, который добавил получателя) */
    @Column(name = "sender_id", nullable = false)
    val senderId: UUID,

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String,

    /** Страна получателя (ISO 3166-1 alpha-2) */
    @Column(name = "country", nullable = false, length = 2)
    val country: String,

    // --- Банковские реквизиты (nullable — зависит от delivery method) ---
    @Column(name = "bank_name", length = 200)
    var bankName: String? = null,

    @Column(name = "bank_account_number", length = 50)
    var bankAccountNumber: String? = null,

    @Column(name = "bank_code", length = 30)
    var bankCode: String? = null,

    // --- Мобильный кошелёк ---
    @Column(name = "mobile_wallet_provider", length = 50)
    var mobileWalletProvider: String? = null,

    @Column(name = "mobile_number", length = 20)
    var mobileNumber: String? = null,

    // --- Аудит ---
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Recipient) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "Recipient(id=$id, name=$firstName $lastName, country=$country)"
}
```

> **Важно:** колонки `recipients` таблицы могут отличаться от представленных выше. Проверь миграцию V003 и адаптируй entity под реальную схему.

#### IdempotencyRecord.kt

```kotlin
package com.transferhub.transfer.domain.model

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Запись идемпотентности.
 *
 * Когда клиент отправляет POST /api/v1/transfers с X-Idempotency-Key,
 * мы сохраняем key + response. При повторном запросе с тем же key —
 * возвращаем сохранённый response без повторного создания перевода.
 *
 * Маппится на таблицу `idempotency_keys` (Flyway V004).
 */
@Entity
@Table(name = "idempotency_keys")
class IdempotencyRecord(

    @Id
    @Column(name = "id", updatable = false)
    val id: UUID = UUID.randomUUID(),

    /** Idempotency key из заголовка X-Idempotency-Key */
    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: UUID,

    /** HTTP-метод + path, чтобы разделять ключи разных операций */
    @Column(name = "operation", nullable = false, length = 100)
    val operation: String,  // "POST /api/v1/transfers"

    /** ID созданного ресурса (transfer_id) */
    @Column(name = "resource_id", nullable = false)
    val resourceId: UUID,

    /** HTTP status code, который вернули при первом запросе */
    @Column(name = "response_code", nullable = false)
    val responseCode: Int,

    /** Сериализованный JSON ответа (для возврата при повторном запросе) */
    @Column(name = "response_body", nullable = false, columnDefinition = "jsonb")
    val responseBody: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    /** Записи автоматически удаляются через 24 часа (pg_cron или application-level cleanup) */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant = Instant.now().plusSeconds(86400) // 24h

) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdempotencyRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "IdempotencyRecord(key=$idempotencyKey, operation=$operation, resourceId=$resourceId)"
}
```

> **Важно:** структура таблицы `idempotency_keys` может отличаться от представленной. Проверь V004 и адаптируй. Колонки вроде `operation`, `response_code`, `response_body`, `expires_at` могут называться иначе или отсутствовать.

---

### 5. Проверка Gradle-плагинов

В `build.gradle.kts` Transfer Service должны быть плагины:

```kotlin
plugins {
    kotlin("jvm") version "..."
    kotlin("plugin.spring") version "..."    // делает Spring-классы open
    kotlin("plugin.jpa") version "..."       // генерирует no-arg конструкторы для @Entity
    id("org.springframework.boot") version "..."
    id("io.spring.dependency-management") version "..."
}
```

Если `kotlin("plugin.jpa")` отсутствует — **добавь его**. Без него JPA не сможет инстанциировать entity-классы (нет no-arg конструктора).

Также убедись, что в dependencies есть:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
runtimeOnly("org.postgresql:postgresql")
```

---

## Правила, которые нужно соблюдать

1. **Entity — НЕ data class.** JPA entity работает через identity (id), не через structural equality. `data class` ломает `equals/hashCode`, `copy()` обходит бизнес-логику, `toString()` с lazy-полями вызывает LazyInitializationException.

2. **BigDecimal для денег — ВСЕГДА.** Ни в одном месте не должно быть `Double` или `Float` для денежных сумм. Это правило финтеха без исключений.

3. **`val` для immutable полей, `var` для mutable.** id, idempotencyKey, sendAmount, createdAt — `val` (нельзя менять после создания). status, statusReason, updatedAt — `var` (меняются в процессе жизненного цикла).

4. **`@Version` для optimistic locking.** Если два инстанса сервиса одновременно пытаются обновить статус одного перевода — один получит `OptimisticLockException`. Это правильное поведение.

5. **equals/hashCode по id.** Для JPA entity это единственно правильный подход. Не по бизнес-полям, не автогенерированный data class.

6. **Sealed class, не enum, для TransferStatus.** Sealed class позволяет добавлять методы (`allowedTransitions`, `canTransitionTo`), хранить данные в подклассах (если понадобится), и при этом даёт exhaustive `when` — компилятор заставляет обработать все варианты.

7. **Имена колонок в @Column(name = "...") должны совпадать с Flyway-миграциями.** Перед созданием entity — открой соответствующий SQL-файл миграции и убедись, что имена колонок совпадают.

---

## Проверка результата

После создания всех файлов:

1. Проект должен компилироваться без ошибок: `./gradlew :services:transfer-service:compileKotlin` (или как настроен Gradle multi-module).

2. Убедись, что import'ы корректны и нет циклических зависимостей.

3. Если приложение запускается с подключением к PostgreSQL (Docker Compose up), Hibernate не должен выдавать ошибки маппинга. В логах не должно быть:
   - `SchemaManagementException`
   - `MappingException`
   - Warnings про unmapped columns

4. Рекомендация: в `application.yml` добавить для отладки:
   ```yaml
   spring:
     jpa:
       show-sql: true
       properties:
         hibernate:
           format_sql: true
       hibernate:
         ddl-auto: validate  # НЕ create/update — схему создаёт Flyway!
   ```
   `ddl-auto: validate` заставит Hibernate проверить, что entity-маппинг соответствует реальной схеме БД. Если есть расхождение — ошибка при старте.

---

## Чего НЕ делать в этом блоке

- **Не создавай репозитории** (Spring Data) — это Block 3.
- **Не создавай сервисный слой** — это Block 4.
- **Не создавай контроллеры и DTO** — это Block 5.
- **Не добавляй бизнес-логику** кроме state machine в TransferStatus и `transitionTo()` в Transfer.
- **Не настраивай Redis** — это Block 7.

Фокус этого блока — чистая доменная модель.
