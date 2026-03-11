# Подготовка к собеседованию — Java/Spring/Kafka Developer

> Каждый ответ подкреплён реальным кодом из проекта **TransferBoss** — микросервисной системы денежных переводов (5 сервисов, Saga, Kafka, PostgreSQL, Redis, gRPC, Docker).

---

## Содержание

1. [Apache Kafka](#1-apache-kafka)
2. [Архитектура и паттерны](#2-архитектура-и-паттерны)
3. [Spring Framework](#3-spring-framework)
4. [Spring Data JPA / Hibernate](#4-spring-data-jpa--hibernate)
5. [PostgreSQL](#5-postgresql)
6. [Redis / Key-Value DB](#6-redis--key-value-db)
7. [Java Core](#7-java-core)
8. [Unit Testing](#8-unit-testing)
9. [REST API Design](#9-rest-api-design)
10. [Docker & Kubernetes](#10-docker--kubernetes)
11. [CI/CD](#11-cicd)
12. [Linux](#12-linux)
13. [Git](#13-git)
14. [Behavioral / System Design](#14-behavioral--system-design)
15. [Design Patterns (GoF)](#15-design-patterns-gof)

---

## 1. Apache Kafka

### В: Что такое Kafka и зачем она нужна?

**Теория**: Kafka — распределённый event streaming platform. Работает как append-only лог: продюсер записывает сообщения в конец топика, консьюмеры читают по offset. Данные хранятся на диске, не удаляются после чтения (в отличие от RabbitMQ). Это позволяет нескольким consumer groups читать одни и те же данные независимо.

**Из проекта TransferBoss**: Kafka связывает 5 микросервисов. transfer-service публикует события через Outbox pattern, mock-payment/mock-payout слушают и отвечают, notification-gateway (Go) реагирует на изменения статусов.

Топики проекта:
- `transfers.payment.requested` — запрос на оплату
- `payments.payment.captured` / `payments.payment.failed` — ответ от платёжного шлюза
- `transfers.payout.requested` — запрос на выплату
- `payouts.payout.completed` / `payouts.payout.failed` — ответ от payout сервиса

---

### В: Как устроены партиции и consumer groups?

**Теория**: Топик делится на партиции (partitions). Каждая партиция — отдельный упорядоченный лог. Внутри одной партиции порядок гарантирован. Consumer group — это группа консьюмеров, которые делят партиции между собой: каждая партиция читается только одним консьюмером группы. Если консьюмеров больше, чем партиций — лишние простаивают.

**Из проекта TransferBoss**: consumer group `transfer-service` читает из трёх топиков (`payments.payment.captured`, `payments.payment.failed`, `payments.payment.refunded`).

```kotlin
// PaymentEventConsumer.kt:55
@KafkaListener(
    topics = ["payments.payment.captured", "payments.payment.failed", "payments.payment.refunded"],
    groupId = "transfer-service"
)
```

**Подводный камень**: Если нужна гарантия порядка обработки для конкретного перевода — используй `transferId` как ключ сообщения. Все события одного перевода попадут в одну партицию.

---

### В: KRaft vs ZooKeeper — в чём разница?

**Теория**: Раньше Kafka использовала ZooKeeper для хранения метаданных (список брокеров, конфигурация топиков, лидеры партиций). С Kafka 3.3+ появился KRaft (Kafka Raft) — встроенный механизм консенсуса. Метаданные хранятся в самой Kafka как внутренний топик `__cluster_metadata`. Это упрощает деплой (минус один компонент), ускоряет выбор лидера, убирает ограничение ZooKeeper на количество партиций.

**Из проекта TransferBoss**: Docker Compose запускает Kafka в KRaft режиме — контейнера ZooKeeper нет. Это видно в `docker-compose.yml` — Kafka настроена с `KAFKA_KRAFT_CLUSTER_ID`.

---

### В: Delivery semantics — at-most-once, at-least-once, exactly-once?

**Теория**:
- **At-most-once**: консьюмер коммитит offset до обработки. Если упал — сообщение потеряно.
- **At-least-once**: консьюмер коммитит offset после обработки. Если упал — обработает повторно. Нужна идемпотентность!
- **Exactly-once**: транзакционный продюсер + read-committed isolation level + idempotent producer. Сложно и медленно, работает только Kafka→Kafka.

**Из проекта TransferBoss**: Используем **at-least-once + идемпотентность на уровне приложения**. Консьюмер записывает `eventId` в таблицу `consumed_events` в той же транзакции, что и бизнес-логику:

```kotlin
// PaymentEventConsumer.kt:82-86
val updated = transactionTemplate.execute {
    if (consumedEventRepository.existsByEventId(event.eventId)) {
        log.info("Duplicate event {}, skipping", event.eventId)
        return@execute false
    }
    // ... бизнес-логика ...
    consumedEventRepository.save(ConsumedEvent(
        eventId = event.eventId,
        consumerGroup = "transfer-service",
        topic = receivedTopic
    ))
    true
}
```

**Подводный камень**: Idempotent Producer (`enable.idempotence=true`) защищает только от дубликатов при отправке (retry продюсера). Это НЕ защищает консьюмера. Для консьюмера нужна своя дедупликация.

---

### В: Как обрабатывать ошибки в Kafka consumer? Что такое DLT?

**Теория**: Если консьюмер не может обработать сообщение, есть варианты: ретрай (в тот же топик или retry-топики), пропуск, или отправка в DLT (Dead Letter Topic). DLT — специальный топик для "ядовитых" сообщений, которые не удалось обработать после всех попыток. Их анализируют вручную или автоматизированно.

**Из проекта TransferBoss**: Spring Kafka `@RetryableTopic` создаёт цепочку retry-топиков автоматически:

```kotlin
// PaymentEventConsumer.kt:48-54
@RetryableTopic(
    attempts = "4",
    backoff = Backoff(
        delayExpression = "\${kafka.retry.delay:30000}",       // 30 сек
        multiplierExpression = "\${kafka.retry.multiplier:10.0}", // x10
        maxDelayExpression = "\${kafka.retry.max-delay:3600000}"  // макс 1 час
    ),
    topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
    dltStrategy = DltStrategy.FAIL_ON_ERROR,
    exclude = [NonRetriableConsumerException::class]  // не ретраить то, что не исправится
)
```

Итого цепочка: `payments.payment.captured` → `payments.payment.captured-retry-0` (30с) → `retry-1` (300с) → `retry-2` (3000с) → `payments.payment.captured-dlt`.

DLT-хендлер считает метрику для алертинга:

```kotlin
// PaymentEventConsumer.kt:144-152
@DltHandler
fun handleDlt(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String, ...) {
    dltCounter.increment()  // Prometheus метрика для алерта
    log.error("Payment event sent to DLT: topic={}, message={}", topic, message)
}
```

**Подводный камень**: Разделяй ошибки на retriable и non-retriable. `NonRetriableConsumerException` (невалидный JSON, неизвестный event type) — нет смысла ретраить, сразу в DLT. `TransientConsumerException` (Transfer not found, DB timeout) — стоит попробовать ещё раз.

---

### В: Что такое CooperativeStickyAssignor?

**Теория**: Стратегия распределения партиций между консьюмерами в группе. По умолчанию `RangeAssignor` — при ребалансировке ВСЕ партиции отзываются и назначаются заново (stop-the-world). `CooperativeStickyAssignor` — инкрементальный: отзываются только те партиции, которые нужно переназначить. Остальные консьюмеры продолжают работать.

**Из проекта TransferBoss**:

```yaml
# application.yml:49
spring.kafka.consumer.properties:
  partition.assignment.strategy: org.apache.kafka.clients.consumer.CooperativeStickyAssignor
```

**Подводный камень**: Нельзя смешивать eager (Range/RoundRobin) и cooperative стратегии в одной consumer group. При миграции нужен rolling upgrade: сначала добавить cooperative как второй в список, потом убрать eager.

---

### В: Что такое Outbox Pattern и зачем он нужен?

**Теория**: Проблема dual-write: если сервис сохраняет данные в БД и отправляет событие в Kafka — одна из операций может упасть (данные есть, событие нет; или наоборот). Outbox pattern решает это: событие записывается в таблицу `outbox_events` в той же транзакции, что и бизнес-данные. Отдельный процесс (outbox relay) читает эту таблицу и публикует в Kafka. Если relay упадёт — при рестарте прочитает непубликованные записи.

**Из проекта TransferBoss**: `TransferService.createTransfer()` сохраняет `Transfer` + `OutboxEvent` в одной `@Transactional`:

```kotlin
// TransferService.kt:130-132
// 8. SAVE BOTH в одной транзакции (@Transactional на методе)
val savedTransfer = transferRepository.save(transfer)
outboxEventRepository.save(outboxEvent)
```

Отдельный `outbox-service` поллит таблицу `outbox_events` и публикует в Kafka.

**Подводный камень**: Outbox relay должен быть идемпотентным — при рестарте может прочитать событие, которое уже опубликовано. Поэтому на стороне консьюмера ВСЕГДА нужна дедупликация.

---

### В: Как гарантировать порядок сообщений в Kafka?

**Теория**: Порядок гарантируется только внутри одной партиции. Если топик имеет N партиций, сообщения в разных партициях обрабатываются параллельно и независимо. Чтобы все события одной сущности шли в порядке — используй ID сущности как ключ сообщения (`message key`). Kafka хеширует ключ и направляет в одну партицию.

**Из проекта TransferBoss**: Все события одного перевода отправляются с `transferId` как ключом, чтобы `PAYMENT_CAPTURED` всегда пришёл после `PAYMENT_REQUESTED` в рамках одного перевода.

---

### В: Что будет, если консьюмер обрабатывает сообщение слишком долго?

**Теория**: Если консьюмер не вызывает `poll()` дольше `max.poll.interval.ms` (по умолчанию 5 минут) — брокер считает его мёртвым и запускает ребалансировку. Партиции переназначаются другому консьюмеру. Решения: уменьшить `max.poll.records`, увеличить `max.poll.interval.ms`, или вынести тяжёлую обработку в отдельный тред.

---

### В: Compaction vs Retention — когда что использовать?

**Теория**: **Retention** (по времени или размеру) — старые сегменты удаляются. Подходит для событий (event log). **Compaction** — Kafka оставляет только последнее значение для каждого ключа. Подходит для changelog/state (текущий баланс, конфигурация). Можно комбинировать: `cleanup.policy=compact,delete`.

---

## 2. Архитектура и паттерны

### В: Что такое Saga pattern?

**Теория**: В микросервисах нет распределённых транзакций (2PC медленный и ненадёжный). Saga — последовательность локальных транзакций в разных сервисах. Каждый шаг имеет компенсирующее действие (rollback). Два подхода: **Choreography** (сервисы реагируют на события друг друга) и **Orchestration** (центральный оркестратор управляет шагами).

**Из проекта TransferBoss**: Choreography-based Saga для создания перевода. Полный lifecycle:

```
CREATED → PAYMENT_PENDING → PAYMENT_CAPTURED → PAYOUT_PENDING → COMPLETED (happy path)
                           → PAYMENT_FAILED (terminal)
                                              → FAILED → REFUND_PENDING → REFUNDED (compensation)
```

Каждый переход — отдельный Kafka event, обработанный отдельным сервисом.

```kotlin
// TransferStatus.kt:40-57 — State Machine с допустимыми переходами
fun allowedTransitions(): Set<TransferStatus> = when (this) {
    Created -> setOf(ComplianceCheck, PaymentPending, Cancelled)
    PaymentPending -> setOf(PaymentCaptured, PaymentFailed)
    PaymentCaptured -> setOf(PayoutPending)
    PayoutPending -> setOf(Delivering, Completed, Failed)
    Failed -> setOf(RefundPending)
    RefundPending -> setOf(Refunded)
    Completed -> emptySet()    // Terminal
    Refunded -> emptySet()     // Terminal
    // ...
}
```

**Подводный камень**: Каждый шаг Saga должен быть идемпотентным — при retry того же события результат должен быть тем же. В TransferBoss это обеспечивается таблицей `consumed_events`.

---

### В: Что такое Circuit Breaker?

**Теория**: Паттерн защиты от каскадных отказов. Три состояния:
- **Closed** (нормальная работа) — запросы проходят, считаются ошибки
- **Open** (сервис недоступен) — запросы блокируются сразу, без попытки вызова. Через `waitDuration` переходит в Half-Open
- **Half-Open** (проверка) — пропускается несколько пробных запросов. Если успешны — Closed, иначе — обратно в Open

**Из проекта TransferBoss**: Resilience4j Circuit Breaker для gRPC-вызова pricing-service:

```kotlin
// PricingClient.kt:37-45
private val circuitBreaker = CircuitBreaker.of(
    "pricing-service",
    CircuitBreakerConfig.custom()
        .failureRateThreshold(50f)            // открыть при 50% ошибок
        .waitDurationInOpenState(Duration.ofSeconds(30))  // ждать 30с перед проверкой
        .slidingWindowSize(10)                // окно: последние 10 вызовов
        .minimumNumberOfCalls(5)              // начать считать после 5 вызовов
        .build()
)
```

Когда circuit breaker открыт:

```kotlin
// PricingClient.kt:87-89
catch (e: CallNotPermittedException) {
    log.warn("Circuit breaker open for pricing-service: {}", e.message)
    throw PricingUnavailableException("Pricing service circuit breaker is open", e)
}
```

---

### В: Зачем нужна идемпотентность и как её реализовать?

**Теория**: Идемпотентность — повторный вызов с теми же параметрами даёт тот же результат. Критически важна в распределённых системах: сеть ненадёжна, ретраи неизбежны, дубликаты будут. Без идемпотентности — один перевод может создаться дважды.

**Из проекта TransferBoss**: Три уровня защиты:

1. **HTTP уровень** — `X-Idempotency-Key` заголовок + distributed lock:
```kotlin
// TransferService.kt:64-73
val lockKey = "sender/${command.senderId}/create"
return distributedLockService.executeWithLock(lockKey) {
    val existingTransfer = transferRepository.findByIdempotencyKey(command.idempotencyKey)
    if (existingTransfer != null) {
        return@executeWithLock Pair(TransferWithRecipient(existingTransfer, recipient), false)
    }
    // ... создание нового ...
}
```

2. **DB уровень** — UNIQUE constraint:
```sql
-- V001:47
CONSTRAINT uq_transfers_idempotency_key UNIQUE (idempotency_key)
```

3. **Kafka consumer уровень** — таблица `consumed_events`:
```kotlin
// PaymentEventConsumer.kt:83
if (consumedEventRepository.existsByEventId(event.eventId)) { return@execute false }
```

---

### В: State Machine для управления состояниями — зачем?

**Теория**: Без state machine можно случайно перевести перевод из `COMPLETED` обратно в `CREATED`. State machine фиксирует допустимые переходы и делает невозможные переходы ошибкой компиляции или runtime-проверкой.

**Из проекта TransferBoss**: `sealed class TransferStatus` — exhaustive `when` гарантирует обработку ВСЕХ статусов:

```kotlin
// Transfer.kt:112-124
fun transitionTo(newStatus: TransferStatus, reason: String? = null) {
    check(status.canTransitionTo(newStatus)) {
        "Invalid status transition: ${status.value} → ${newStatus.value}. " +
            "Allowed transitions from ${status.value}: ${status.allowedTransitions().map { it.value }}"
    }
    status = newStatus
    updatedAt = Instant.now()
    if (newStatus.isTerminal()) {
        completedAt = Instant.now()
    }
}
```

**Подводный камень**: Sealed class (Kotlin) / sealed interface (Java 17+) лучше enum — можно добавить данные к конкретным статусам (например, `Failed(reason: String)`).

---

### В: Distributed Locking — зачем и как?

**Теория**: В кластере из нескольких инстансов одного сервиса возможен race condition: два запроса с одинаковым idempotency key попадают на разные инстансы одновременно. Distributed lock гарантирует, что только один инстанс выполняет операцию. Варианты: Redis (Redlock), Consul, ZooKeeper, PostgreSQL advisory locks.

**Из проекта TransferBoss**: Consul-based distributed lock с session TTL:

```kotlin
// TransferService.kt:64,66
val lockKey = "sender/${command.senderId}/create"
return distributedLockService.executeWithLock(lockKey) { ... }
```

Конфигурация:
```yaml
# application.yml:106-111
consul.lock:
  enabled: true
  session-ttl-seconds: 15        # auto-release если сервис упал
  acquire-timeout-ms: 5000       # сколько ждать лока
  retry-interval-ms: 50          # между попытками
```

**Подводный камень**: Всегда ставь TTL на лок. Если сервис взял лок и упал без освобождения — лок должен истечь автоматически. Иначе — deadlock навечно.

---

### В: gRPC vs REST — когда что?

**Теория**: REST — текстовый (JSON), простой, хорош для внешних API (браузеры, мобильные). gRPC — бинарный (protobuf), быстрее (~10x по размеру), двунаправленный стриминг, строгая типизация через `.proto` файлы. Используй gRPC для internal service-to-service, REST для external API.

**Из проекта TransferBoss**: REST для внешнего API (`TransferController` — клиенты), gRPC для внутреннего (`PricingClient` → pricing-service):

```kotlin
// PricingClient.kt:54-56
val response = stub
    .withDeadlineAfter(3, TimeUnit.SECONDS)  // жёсткий таймаут
    .validateQuote(request)
```

Классификация ошибок по gRPC status code:

```kotlin
// PricingClient.kt:81-86
when (e.status.code) {
    Status.Code.INVALID_ARGUMENT, Status.Code.NOT_FOUND ->
        throw QuoteExpiredException(quoteId, e.status.description)
    else ->
        throw PricingUnavailableException("Pricing service error: ${e.status.code}", e)
}
```

---

## 3. Spring Framework

### В: Как работает IoC контейнер Spring?

**Теория**: IoC (Inversion of Control) — приложение не создаёт зависимости само, а получает их от контейнера. Spring контейнер при старте:
1. Сканирует классы (@Component, @Service, @Repository, @Controller)
2. Создаёт BeanDefinition для каждого
3. Обрабатывает BeanFactoryPostProcessor (например, PropertySourcesPlaceholderConfigurer)
4. Инстанцирует бины, инжектит зависимости
5. Вызывает BeanPostProcessor (например, AOP прокси)
6. Вызывает @PostConstruct / InitializingBean

**Из проекта TransferBoss**: Конструктор-инъекция — Spring рекомендует именно её (не `@Autowired` на поля):

```kotlin
// TransferService.kt:26-33
@Service
class TransferService(
    private val transferRepository: TransferRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val recipientRepository: RecipientRepository,
    private val objectMapper: ObjectMapper,
    private val distributedLockService: DistributedLockService,
    private val pricingClient: PricingClient
)
```

**Подводный камень**: Конструктор-инъекция лучше `@Autowired`:
- Зависимости `val` (immutable) — нельзя случайно перезаписать
- Видно все зависимости сразу (если их слишком много — знак, что класс делает слишком многое)
- Работает без Spring (в тестах можно передать mock напрямую)

---

### В: Что такое AOP в Spring? Где применяется?

**Теория**: AOP (Aspect-Oriented Programming) — выделение сквозной логики (logging, security, transactions) в отдельные модули (aspects). Spring AOP работает через proxy: обёртывает бин прокси-объектом, который перехватывает вызовы методов. Два типа прокси: JDK Dynamic Proxy (для интерфейсов) и CGLIB (для классов).

**Из проекта TransferBoss**:

1. `@Transactional` — самый частый пример AOP. Spring создаёт прокси, который открывает/коммитит/откатывает транзакцию:
```kotlin
// TransferService.kt:62
@Transactional
fun createTransfer(command: CreateTransferCommand): Pair<TransferWithRecipient, Boolean> { ... }
```

2. `@RestControllerAdvice` — AOP для обработки ошибок всех контроллеров:
```kotlin
// GlobalExceptionHandler.kt:20-22
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class GlobalExceptionHandler { ... }
```

**Подводный камень**: `@Transactional` на private методе не работает! Spring AOP работает через прокси — прокси перехватывает только public/protected вызовы извне. Вызов private метода из того же класса идёт напрямую, минуя прокси. То же самое с self-invocation: если `methodA()` вызывает `this.methodB()`, а `@Transactional` стоит на `methodB` — транзакция не откроется.

---

### В: @Transactional vs TransactionTemplate — когда что?

**Теория**: `@Transactional` — декларативный подход (через AOP). Транзакция открывается при входе в метод и коммитится при выходе. `TransactionTemplate` — программный подход: точный контроль над границами транзакции. Используй `TransactionTemplate`, когда:
- Нужна транзакция внутри lambda/callback
- Нужны разные транзакции в одном методе
- Нужен контроль, какой именно код в транзакции, а какой — нет

**Из проекта TransferBoss**: Оба подхода:

```kotlin
// TransferService.kt:62 — декларативный (весь метод в одной транзакции)
@Transactional
fun createTransfer(command: CreateTransferCommand) { ... }

// PaymentEventConsumer.kt:82 — программный (явные границы)
val updated = transactionTemplate.execute {
    // Только этот блок в транзакции
    if (consumedEventRepository.existsByEventId(event.eventId)) { return@execute false }
    // ... обновляем transfer, сохраняем consumed_event ...
    true
}
// Evict кэш ПОСЛЕ коммита транзакции — за пределами transactionTemplate
if (updated) { transferCacheService.evict(transferId) }
```

**Подводный камень**: В `PaymentEventConsumer` `TransactionTemplate` используется намеренно: cache evict должен быть ПОСЛЕ коммита. Если бы весь метод был `@Transactional`, evict произошёл бы до коммита — race condition.

---

### В: Как работает Spring Boot Auto-Configuration?

**Теория**: Spring Boot сканирует `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` из всех jar в classpath. Каждый класс автоконфигурации аннотирован `@Conditional*` — создаёт бины только при выполнении условий (наличие класса, property, другого бина). Порядок: `@AutoConfigureOrder`, `@AutoConfigureBefore/After`.

**Из проекта TransferBoss**: `@ConditionalOnProperty` для условного создания бинов:

```kotlin
// ConsulDistributedLockService — реальная реализация
@Component
@ConditionalOnProperty(name = ["consul.lock.enabled"], havingValue = "true")
class ConsulDistributedLockService : DistributedLockService

// NoOpDistributedLockService — fallback (в тестах Consul отключён)
@Component
@ConditionalOnProperty(name = ["consul.lock.enabled"], havingValue = "false", matchIfMissing = true)
class NoOpDistributedLockService : DistributedLockService
```

В тестах `consul.lock.enabled` не задан → `matchIfMissing = true` → используется NoOp. В проде — Consul.

---

### В: Profiles в Spring — зачем и как?

**Теория**: Profiles позволяют иметь разные конфигурации для разных окружений (dev, test, prod). Активируются через `spring.profiles.active`. Можно иметь `application-test.yml`, `application-prod.yml`. Бины тоже можно привязать к профилю через `@Profile("test")`.

**Из проекта TransferBoss**:

```kotlin
// IntegrationTestBase.kt:12
@ActiveProfiles("test")
abstract class IntegrationTestBase { ... }
```

```yaml
# application.yml — основная конфигурация
spring.cloud.consul.discovery.enabled: true

# application-test.yml — Consul отключён
spring.cloud.consul.enabled: false
```

---

### В: Как работает Spring Security + JWT?

**Теория**: Spring Security — цепочка фильтров (SecurityFilterChain). Каждый HTTP-запрос проходит через фильтры: CORS → CSRF → Authentication → Authorization. Для JWT:
1. Клиент отправляет `Authorization: Bearer <token>` заголовок
2. JwtAuthenticationFilter извлекает токен, валидирует подпись (HMAC/RSA)
3. Извлекает claims (userId, roles), создаёт `Authentication` объект
4. Кладёт в `SecurityContextHolder` — доступен во всех бинах

**Из проекта TransferBoss**: JWT пока не реализован, используется временный заголовок:

```kotlin
// TransferController.kt:52-53
@RequestHeader("X-Sender-Id", required = false) senderIdHeader: UUID?
// ...
val senderId = senderIdHeader ?: UUID.fromString("00000000-0000-0000-0000-000000000001")
```

На собеседовании можно рассказать: "В текущей версии используется X-Sender-Id header, но архитектура готова к JWT — нужно добавить SecurityFilterChain и заменить header extraction на извлечение из SecurityContext."

---

### В: Graceful Shutdown — зачем и как?

**Теория**: При остановке сервиса (deploy, scale-down) нельзя обрывать текущие запросы. Graceful shutdown: перестать принимать новые запросы → дождаться завершения текущих → освободить ресурсы (закрыть пулы, коммитить offset в Kafka). В Kubernetes: Pod получает SIGTERM → `preStop` hook → readiness probe fails → трафик перестаёт идти → контейнер останавливается.

**Из проекта TransferBoss**:

```yaml
# application.yml:3,76
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 30s
```

30 секунд — максимальное время ожидания завершения текущих запросов.

---

## 4. Spring Data JPA / Hibernate

### В: Entity lifecycle — какие состояния есть у JPA entity?

**Теория**: 4 состояния:
- **Transient** — `new Transfer()`, не привязан к persistence context. JPA про него не знает.
- **Managed** — после `repository.save()` или `entityManager.find()`. Все изменения автоматически синхронизируются с БД при flush (dirty checking).
- **Detached** — после закрытия EntityManager или `detach()`. Изменения не отслеживаются. Чтобы сохранить — нужно `merge()`.
- **Removed** — помечен к удалению, удалится при flush.

**Из проекта TransferBoss**:

```kotlin
// PaymentEventConsumer.kt:88-100
val transfer = transferRepository.findTransferById(transferId)  // → Managed
transfer.transitionTo(newStatus, event.reason)                    // dirty checking отследит
transfer.paymentId = event.paymentId                              // это тоже
transferRepository.save(transfer)                                 // flush → UPDATE SQL
```

**Подводный камень**: `save()` в Spring Data JPA — это `merge()` если entity detached, или просто маркировка для persist если новый. Для managed entity `save()` технически не нужен (dirty checking сделает UPDATE автоматически при flush), но явный `save()` делает код понятнее.

---

### В: Что такое N+1 проблема и как её решить?

**Теория**: N+1 — когда для загрузки N связанных объектов выполняется N+1 SQL запросов: 1 для основной сущности + N для каждой связи (lazy loading). Решения: `JOIN FETCH` (JPQL), `@EntityGraph`, `@BatchSize`, или batch lookup в коде.

**Из проекта TransferBoss**: Вместо JPA `@ManyToOne` для recipient используется UUID-ссылка + batch lookup:

```kotlin
// Transfer.kt:69 — UUID вместо @ManyToOne
@Column(name = "recipient_id", nullable = false)
val recipientId: UUID

// TransferService.kt:228-231 — batch lookup вместо N+1
val recipientIds = page.map { it.recipientId }.distinct()
val recipientMap = recipientRepository.findAllById(recipientIds).associateBy { it.id }
val results = page.map { TransferWithRecipient(it, recipientMap[it.recipientId]) }
```

1 запрос на transfers + 1 запрос на recipients = 2 запроса вместо N+1.

**Подводный камень**: UUID-ссылки вместо JPA relationships — сознательный trade-off. Теряем каскадирование и навигацию, но получаем отсутствие N+1, независимость модулей, лёгкое разделение на микросервисы.

---

### В: Optimistic Locking — что это и зачем?

**Теория**: Механизм защиты от lost update при конкурентных изменениях. Каждая запись имеет `version`. При UPDATE Hibernate добавляет `WHERE version = ?`. Если другой поток уже обновил запись (version изменился) — UPDATE не найдёт строку → `OptimisticLockException`.

**Из проекта TransferBoss**:

```kotlin
// Transfer.kt:87-90
@Version
@Column(name = "version", nullable = false)
var version: Int = 0
```

```sql
-- V001:39 — в миграции
version INTEGER NOT NULL DEFAULT 0
```

При конфликте — GlobalExceptionHandler возвращает 409 Conflict:

```kotlin
// GlobalExceptionHandler.kt:135-153
@ExceptionHandler(ObjectOptimisticLockingFailureException::class)
fun handleOptimisticLock(...): ResponseEntity<ProblemDetail> {
    val problem = ProblemDetail.forStatus(HttpStatus.CONFLICT).apply {
        title = "Concurrent Modification"
        detail = "The resource was modified by another request. Please retry."
    }
    return ResponseEntity.status(HttpStatus.CONFLICT).body(problem)
}
```

---

### В: `ddl-auto: validate` vs `update` vs `create` — что выбрать?

**Теория**:
- `create` — дропает таблицы и создаёт заново. Только для тестов.
- `update` — добавляет недостающие колонки/таблицы, НО никогда не удаляет. Опасно в проде — Hibernate может создать не то.
- `validate` — только проверяет, что entities совпадают со схемой. Если нет — ошибка при старте. Именно это нужно в проде.
- `none` — ничего не делать. Для Flyway.

**Из проекта TransferBoss**: `validate` + Flyway = золотой стандарт:

```yaml
# application.yml:21-22
spring.jpa.hibernate.ddl-auto: validate    # проверяй, но не меняй
spring.flyway.enabled: true                # миграции управляются Flyway
```

**Подводный камень**: Hibernate validator строгий к типам. PostgreSQL `CHAR(3)` хранится как `bpchar`, а Hibernate ожидает `char`. Решение: всегда используй `VARCHAR(N)` в миграциях.

---

### В: `readOnly = true` в @Transactional — что даёт?

**Теория**: Подсказка Hibernate и драйверу:
1. Hibernate отключает dirty checking — не нужно сравнивать snapshot при flush → экономия CPU
2. JDBC драйвер может маршрутизировать на read replica (если настроен)
3. Некоторые БД оптимизируют read-only транзакции (без WAL-записей)

**Из проекта TransferBoss**:

```kotlin
// TransferService.kt:162,194
@Transactional(readOnly = true)
fun getTransfer(transferId: UUID): TransferWithRecipient { ... }

@Transactional(readOnly = true)
fun listTransfers(senderId: UUID, cursor: String?, size: Int): ... { ... }
```

---

## 5. PostgreSQL

### В: Какие типы индексов есть в PostgreSQL?

**Теория**:
- **B-tree** (по умолчанию) — для `=`, `<`, `>`, `BETWEEN`, `ORDER BY`. Покрывает 95% случаев.
- **Hash** — только для `=`. Быстрее B-tree для equality, но не поддерживает range. Редко нужен.
- **GIN** — для массивов, JSONB, полнотекстового поиска. Медленнее на запись.
- **GiST** — для геоданных, диапазонов, полнотекста.
- **BRIN** — для огромных таблиц с физически отсортированными данными (time-series).

**Из проекта TransferBoss**: Все индексы — B-tree:

```sql
-- V001:63-64 — Составной индекс для cursor pagination
CREATE INDEX idx_transfers_sender_created
    ON transfers (sender_id, created_at DESC);

-- V001:67-69 — Partial index: только активные переводы (~5% от общего числа)
CREATE INDEX idx_transfers_status
    ON transfers (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED');

-- V001:72-74 — Conditional index
CREATE INDEX idx_transfers_payment_id
    ON transfers (payment_id) WHERE payment_id IS NOT NULL;
```

---

### В: Partial Index — что это и зачем?

**Теория**: Индекс, который покрывает только строки, соответствующие условию `WHERE`. Меньше места, быстрее запись, быстрее поиск. Идеален, когда 90%+ строк имеют одно значение (COMPLETED), а ищешь по редким.

**Из проекта TransferBoss**:

```sql
-- V001:67-69 — индексируем ТОЛЬКО активные переводы
CREATE INDEX idx_transfers_status ON transfers (status)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'REFUNDED');
```

В зрелой системе ~95% переводов в терминальном статусе. Partial index покрывает только 5%, экономя 95% места и ускоряя запись.

---

### В: MVCC — как работает конкурентный доступ в PostgreSQL?

**Теория**: MVCC (Multi-Version Concurrency Control) — каждая транзакция видит свой snapshot данных. При UPDATE создаётся новая версия строки (новый tuple), старая помечается как "мёртвая". Читатели не блокируют писателей и наоборот. "Мёртвые" tuples очищаются VACUUM.

---

### В: Уровни изоляции транзакций — какие и когда?

**Теория**:
- **READ UNCOMMITTED** — PostgreSQL не поддерживает (работает как READ COMMITTED)
- **READ COMMITTED** (default) — видишь только закоммиченные данные. Каждый SELECT видит свежий snapshot.
- **REPEATABLE READ** — snapshot фиксируется в начале транзакции. Повторный SELECT вернёт то же самое. Защита от non-repeatable read.
- **SERIALIZABLE** — полная изоляция, как если бы транзакции выполнялись последовательно. PostgreSQL откатит транзакции при обнаружении конфликта.

**Из проекта TransferBoss**: Используется READ COMMITTED (default PostgreSQL). Защита от конкурентных изменений — через optimistic locking (`@Version`) и distributed locks (Consul), а не через уровни изоляции.

---

### В: Числовые типы для денег — NUMERIC vs FLOAT?

**Теория**: FLOAT/DOUBLE — приблизительные типы (IEEE 754). `0.1 + 0.2 != 0.3`. Для денег — НИКОГДА. NUMERIC(precision, scale) — точный тип с фиксированной точностью. `NUMERIC(15,2)` — до 15 цифр, 2 после запятой (до 9,999,999,999,999.99).

**Из проекта TransferBoss**:

```sql
-- V001:14-20
send_amount     NUMERIC(15,2) NOT NULL,
receive_amount  NUMERIC(15,2) NOT NULL,
exchange_rate   NUMERIC(12,6) NOT NULL,    -- курс с 6 знаками
fee_amount      NUMERIC(10,2) NOT NULL,
```

В Kotlin — `BigDecimal` (не `Double`!):

```kotlin
// Transfer.kt:37
val sendAmount: BigDecimal
```

**Подводный камень**: `BigDecimal("0.1")` — правильно. `BigDecimal(0.1)` — НЕПРАВИЛЬНО (потеря точности при конвертации из double).

---

### В: Cursor-based vs Offset-based пагинация?

**Теория**:
- **Offset** (`LIMIT 20 OFFSET 1000`): БД сканирует и пропускает 1000 строк. Чем дальше — тем медленнее. При вставке новых строк — дублирование/пропуск (page drift).
- **Cursor** (`WHERE created_at < :cursor ORDER BY created_at DESC LIMIT 20`): использует индекс, всегда быстро. Нет page drift. Но нельзя "прыгнуть" на страницу 50.

**Из проекта TransferBoss**:

```sql
-- V001:63-64 — индекс для cursor pagination
CREATE INDEX idx_transfers_sender_created
    ON transfers (sender_id, created_at DESC);
```

```kotlin
// TransferService.kt:209-215 — запрос с cursor
val (cursorCreatedAt, cursorId) = decodeCursor(cursor)
transferRepository.findBySenderIdAfterCursor(
    senderId = senderId,
    cursorCreatedAt = cursorCreatedAt,
    cursorId = cursorId,
    limit = effectiveSize + 1  // +1 чтобы узнать, есть ли следующая страница
)
```

Cursor закодирован как Base64 JSON — opaque для клиента:

```kotlin
// TransferService.kt:304-306
private fun encodeCursor(createdAt: Instant, id: UUID): String {
    val json = objectMapper.writeValueAsString(mapOf("c" to createdAt.toString(), "i" to id.toString()))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
```

---

## 6. Redis / Key-Value DB

### В: Какие структуры данных есть в Redis?

**Теория**:
- **String** — простое значение (кэш, счётчик, session). До 512MB.
- **Hash** — поля внутри ключа (`HSET user:1 name "John" age 30`). Для объектов.
- **List** — двусвязный список. Очереди (LPUSH/RPOP).
- **Set** — уникальные значения. Пересечение/объединение.
- **Sorted Set (ZSet)** — Set со score. Рейтинги, leaderboard.
- **Stream** — append-only log (похож на Kafka topic). Consumer groups.

**Из проекта TransferBoss**: Используем **String** — JSON-serialized TransferResponse:

```kotlin
// TransferCacheService.kt:44
redisTemplate.opsForValue().set(key, json, CACHE_TTL)
```

---

### В: Cache-Aside Pattern — как работает?

**Теория**: Самый распространённый паттерн кэширования:
1. **Read**: проверь кэш → если есть (HIT) — вернуть. Если нет (MISS) — прочитай из БД, положи в кэш, вернуть.
2. **Write**: обнови БД → удали из кэша (не обновляй!). Следующий read положит свежие данные.

Почему удалять, а не обновлять? Потому что между записью в БД и обновлением кэша может прийти параллельный read и положить в кэш старые данные.

**Из проекта TransferBoss**:

```kotlin
// TransferController.kt:81-93 — Read path
fun getTransfer(@PathVariable id: UUID): ResponseEntity<TransferResponse> {
    val cached = transferCacheService.getCached(id)    // 1. Проверяем кэш
    if (cached != null) return ResponseEntity.ok(cached)  // HIT → возвращаем

    val result = transferService.getTransfer(id)           // 2. MISS → читаем из БД
    val response = result.transfer.toResponse(result.recipient)
    transferCacheService.put(id, response)                 // 3. Кладём в кэш
    return ResponseEntity.ok(response)
}

// PaymentEventConsumer.kt:136-138 — Invalidation path
if (updated) {
    transferCacheService.evict(transferId)                 // Удаляем из кэша ПОСЛЕ коммита
}
```

---

### В: Graceful Degradation — что если Redis упал?

**Теория**: Кэш — это оптимизация, не источник правды. Если Redis недоступен, приложение должно продолжать работать (медленнее, из БД). Никогда не бросай exception из cache-операций.

**Из проекта TransferBoss**: Все Redis-операции обёрнуты в try-catch:

```kotlin
// TransferCacheService.kt:23-37
fun getCached(transferId: UUID): TransferResponse? {
    return try {
        val json = redisTemplate.opsForValue().get(key)
        if (json != null) {
            objectMapper.readValue(json, TransferResponse::class.java)
        } else null
    } catch (e: Exception) {
        log.warn("Redis GET failed for transferId={}: {}", transferId, e.message)
        null  // Fallback: как будто cache miss
    }
}
```

**Подводный камень**: Логируй Redis-ошибки как WARN, не ERROR. Redis падает — это нормально, приложение продолжает работать. ERROR зарезервируй для того, что реально сломано.

---

### В: TTL и Eviction — как управлять памятью Redis?

**Теория**: TTL (Time To Live) — автоматическое удаление ключа через N секунд. Eviction policy — что делать, когда Redis заполнен:
- `noeviction` — возвращать ошибку (по умолчанию)
- `allkeys-lru` — удалять наименее недавно использованные ключи
- `volatile-lru` — то же, но только среди ключей с TTL
- `allkeys-lfu` — удалять наименее часто используемые

**Из проекта TransferBoss**: Короткий TTL для быстро меняющихся данных:

```kotlin
// TransferCacheService.kt:20
private val CACHE_TTL = Duration.ofSeconds(30)
```

30 секунд — компромисс: снимаем нагрузку с БД при частых запросах, но данные не устаревают сильно.

---

## 7. Java Core

### 7.1 ООП

### В: Назовите 4 принципа ООП

**Теория**:
1. **Инкапсуляция** — скрытие внутреннего состояния за публичным API. Поля `private`, доступ через методы. Позволяет менять внутреннюю реализацию, не ломая вызывающий код.
2. **Наследование** — класс-потомок получает поля и методы родителя. `extends` для классов, `implements` для интерфейсов. Позволяет переиспользовать код.
3. **Полиморфизм** — один интерфейс, разные реализации. Переменная типа `Animal` может содержать `Dog` или `Cat`. Вызов `animal.speak()` выполнит нужный метод в runtime (dynamic dispatch).
4. **Абстракция** — выделение существенных характеристик, игнорирование деталей. Интерфейс `List` скрывает, как именно хранятся элементы (массив или связный список).

---

### В: abstract class vs interface — когда что?

**Теория**:
- **interface** — контракт "что делать". Только абстрактные методы (+ default с Java 8). Класс может реализовать МНОГО интерфейсов. Нет состояния (нет полей с данными, только константы).
- **abstract class** — частичная реализация. Может иметь поля, конструктор, обычные методы. Класс может наследовать только ОДИН abstract class.

Правило: если нужен только контракт → interface. Если нужно общее состояние/логика для группы классов → abstract class.

**Из проекта TransferBoss**: `DistributedLockService` — interface (контракт), две реализации:
```kotlin
interface DistributedLockService {
    fun <T> executeWithLock(key: String, action: () -> T): T
}
class ConsulDistributedLockService : DistributedLockService  // prod
class NoOpDistributedLockService : DistributedLockService    // test
```

---

### В: Overriding vs Overloading?

**Теория**:
- **Overriding** (переопределение) — потомок заменяет реализацию метода родителя. Та же сигнатура (имя + параметры). Решается в runtime (dynamic dispatch). Аннотация `@Override`.
- **Overloading** (перегрузка) — несколько методов с одинаковым именем, но разными параметрами. Решается в compile time (static dispatch).

```java
// Overloading — compile time
void process(String s) { ... }
void process(int n) { ... }

// Overriding — runtime
class Animal { void speak() { } }
class Dog extends Animal { @Override void speak() { "Woof"; } }
```

---

### В: Composition vs Inheritance?

**Теория**: "Favor composition over inheritance" (GoF). Наследование создаёт жёсткую связь (потомок зависит от внутренней реализации родителя). Composition — класс содержит другой класс как поле и делегирует ему работу. Гибче, легче тестировать, нет проблемы хрупкого базового класса.

**Из проекта TransferBoss**: `TransferService` НЕ наследует от `Repository` — он содержит `TransferRepository` как зависимость:
```kotlin
@Service
class TransferService(
    private val transferRepository: TransferRepository,  // composition
    private val pricingClient: PricingClient              // composition
)
```

---

### В: Что такое SOLID?

**Теория**:
- **S** (Single Responsibility) — класс делает одну вещь. `TransferService` — бизнес-логика переводов. `TransferCacheService` — кэширование. Не в одном классе.
- **O** (Open/Closed) — открыт для расширения, закрыт для модификации. Добавляешь новый `TransferStatus` — не меняешь существующие.
- **L** (Liskov Substitution) — потомок можно подставить вместо родителя без сюрпризов. `NoOpDistributedLockService` работает везде, где ожидается `DistributedLockService`.
- **I** (Interface Segregation) — маленькие специализированные интерфейсы лучше одного жирного.
- **D** (Dependency Inversion) — зависим от абстракций (интерфейсов), не от конкретных классов. `TransferService` зависит от `DistributedLockService` (interface), не от `ConsulDistributedLockService`.

---

### 7.2 Ключевые слова и модификаторы

### В: Модификаторы доступа — какие есть?

**Теория**: 4 уровня (от самого открытого к закрытому):

| Модификатор | Класс | Пакет | Потомок | Весь мир |
|-------------|-------|-------|---------|----------|
| `public`    | да    | да    | да      | да       |
| `protected` | да    | да    | да      | нет      |
| default (package-private) | да | да | нет | нет |
| `private`   | да    | нет   | нет     | нет      |

Правило: начинай с `private`, расширяй по необходимости. Поля — `private` всегда.

---

### В: Ключевое слово `final` — что делает?

**Теория**:
- **final переменная** — нельзя переприсвоить (для ссылок: ссылка не меняется, но объект внутри можно мутировать!)
- **final метод** — нельзя переопределить в потомке
- **final класс** — нельзя наследовать (String, Integer — final)

```java
final List<String> list = new ArrayList<>();
list.add("ok");          // ✅ мутация объекта — можно
list = new ArrayList<>(); // ❌ переприсвоение ссылки — нельзя
```

**Подводный камень**: `final` не означает immutable! Для настоящей иммутабельности нужны `Collections.unmodifiableList()` или `List.of()`.

---

### В: `static` — зачем и когда?

**Теория**:
- **static поле** — одно на все экземпляры класса (shared state). Пример: счётчик, константы.
- **static метод** — не нужен экземпляр для вызова (`Math.abs()`, `UUID.randomUUID()`). Нет доступа к `this`.
- **static блок** — выполняется один раз при загрузке класса (инициализация).
- **static inner class** — не держит ссылку на внешний класс (в отличие от non-static inner class).

**Из проекта TransferBoss**:
```kotlin
// TransferCacheService.kt:18-21
companion object {  // Kotlin аналог static
    private const val KEY_PREFIX = "transfer:status:"
    private val CACHE_TTL = Duration.ofSeconds(30)
}
```

---

### В: `this` и `super` — что это?

**Теория**:
- **this** — ссылка на текущий экземпляр. Используется для разрешения конфликта имён (`this.name = name`) и вызова другого конструктора (`this(arg)`).
- **super** — ссылка на родительский класс. Вызов метода родителя (`super.method()`), вызов конструктора родителя (`super(arg)` — обязан быть первой строкой).

---

### В: `transient` и `volatile`?

**Теория**:
- **transient** — поле НЕ сериализуется (Serializable). Пароли, кэшированные вычисления.
- **volatile** — гарантирует видимость изменений между потоками. Чтение/запись идёт из main memory, не из CPU cache. НО: не гарантирует атомарность (`volatile int count; count++` — НЕ атомарно!).

---

### В: `instanceof` и pattern matching (Java 16+)?

**Теория**: `instanceof` проверяет тип объекта. С Java 16 — pattern matching убирает необходимость отдельного каста:

```java
// До Java 16
if (obj instanceof String) {
    String s = (String) obj;  // явный каст
    s.length();
}

// Java 16+ (pattern matching)
if (obj instanceof String s) {
    s.length();  // s уже String, каст не нужен
}

// Java 21 (switch pattern matching)
switch (shape) {
    case Circle c -> c.radius();
    case Rectangle r -> r.width() * r.height();
}
```

---

### 7.3 Исключения (Exceptions)

### В: Иерархия исключений в Java?

**Теория**:
```
Throwable
  ├─ Error (НЕ ловить! JVM-проблемы)
  │    ├─ OutOfMemoryError
  │    ├─ StackOverflowError
  │    └─ VirtualMachineError
  └─ Exception
       ├─ Checked exceptions (ОБЯЗАТЕЛЬНО обработать)
       │    ├─ IOException
       │    ├─ SQLException
       │    ├─ FileNotFoundException
       │    └─ ClassNotFoundException
       └─ RuntimeException — Unchecked (НЕ обязательно)
            ├─ NullPointerException
            ├─ IllegalArgumentException
            ├─ IllegalStateException
            ├─ IndexOutOfBoundsException
            ├─ ClassCastException
            ├─ UnsupportedOperationException
            └─ ConcurrentModificationException
```

---

### В: Checked vs Unchecked — в чём разница?

**Теория**:
- **Checked** (наследуют `Exception`, но НЕ `RuntimeException`) — компилятор ЗАСТАВЛЯЕТ обработать: `try-catch` или `throws` в сигнатуре. Это ошибки, от которых можно восстановиться: файл не найден, сеть недоступна, БД упала.
- **Unchecked** (наследуют `RuntimeException`) — компилятор НЕ проверяет. Это баги в коде: NPE, выход за границы массива, невалидный аргумент. Ловить их — скрывать баг.
- **Error** — критические проблемы JVM. НЕ ловить, НЕ обрабатывать. OOM, StackOverflow — приложение должно упасть и перезапуститься.

**Правило**: бросай Unchecked для ошибок программиста (валидация, невозможные состояния). Бросай Checked для ошибок окружения (IO, сеть), от которых вызывающий код может восстановиться.

---

### В: try-catch-finally — порядок выполнения?

**Теория**:
1. `try` — код, который может бросить исключение
2. `catch` — обработка конкретного типа. Порядок важен: от конкретного к общему!
3. `finally` — выполняется ВСЕГДА (и при нормальном завершении, и при exception, и при return из try/catch)

```java
try {
    return readFile();
} catch (FileNotFoundException e) {  // конкретный — первый
    log.warn("File not found: {}", e.getMessage());
    return defaultValue;
} catch (IOException e) {            // общий — после конкретного
    log.error("IO error", e);
    throw new ServiceException("Failed to read", e);
} finally {
    closeResource();  // выполнится ВСЕГДА
}
```

**Подводный камень**: `return` в `finally` перезаписывает `return` из `try`/`catch`. Никогда не пиши `return` в `finally`!

---

### В: try-with-resources — что это?

**Теория** (Java 7+): Автоматическое закрытие ресурсов (файлов, соединений, стримов). Ресурс должен реализовать `AutoCloseable`. `close()` вызывается автоматически при выходе из `try` — даже при exception.

```java
// До Java 7 — ручное закрытие в finally (легко забыть!)
InputStream is = null;
try {
    is = new FileInputStream("file");
    // ...
} finally {
    if (is != null) is.close();  // и это тоже может бросить IOException!
}

// Java 7+ — try-with-resources
try (var is = new FileInputStream("file");
     var reader = new BufferedReader(new InputStreamReader(is))) {
    // ...
}  // is и reader закроются автоматически, в обратном порядке
```

---

### В: Как правильно создавать свои исключения?

**Теория**: Наследуй от подходящего класса:
- Бизнес-правило нарушено → `RuntimeException` (unchecked)
- Внешняя система недоступна → `Exception` (checked) или `RuntimeException` — зависит от стиля проекта
- Всегда передавай cause (`Throwable cause`) — не теряй stack trace!

**Из проекта TransferBoss**: Иерархия бизнес-исключений:

```kotlin
// Базовый класс — все бизнес-ошибки
abstract class BusinessException(
    val statusCode: Int,
    val errorType: String,
    val title: String,
    message: String
) : RuntimeException(message)  // Unchecked! Spring сам обработает

// Конкретные
class TransferNotFoundException(id: UUID) : BusinessException(
    statusCode = 404,
    errorType = "https://api.transferhub.com/errors/transfer-not-found",
    title = "Transfer Not Found",
    message = "Transfer with id $id not found"
)

class UnsupportedCorridorException(source: String, dest: String) : BusinessException(
    statusCode = 422,
    errorType = "https://api.transferhub.com/errors/unsupported-corridor",
    title = "Unsupported Corridor",
    message = "Corridor ${source}→${dest} is not supported"
)
```

Все ловятся централизованно в `GlobalExceptionHandler`:
```kotlin
// GlobalExceptionHandler.kt:26-43
@ExceptionHandler(BusinessException::class)
fun handleBusinessException(ex: BusinessException, request: WebRequest): ResponseEntity<ProblemDetail> {
    val problem = ProblemDetail.forStatus(ex.statusCode).apply {
        type = URI.create(ex.errorType)
        title = ex.title
        detail = ex.message
    }
    return ResponseEntity.status(ex.statusCode).body(problem)
}
```

---

### В: Какие исключения нужно ловить, а какие — нет?

**Теория**:

**Ловить и обрабатывать:**
- Checked exceptions от внешних систем (IO, DB, HTTP) — fallback, retry, graceful degradation
- Бизнес-исключения на границе системы (контроллер) — преобразовать в HTTP response
- `InterruptedException` — восстановить флаг прерывания: `Thread.currentThread().interrupt()`

**НЕ ловить (или ловить только на верхнем уровне для логирования):**
- `NullPointerException` — это баг, фиксить код, а не ловить
- `ClassCastException` — это баг
- `ArrayIndexOutOfBoundsException` — это баг
- `Error` (OOM, SOE) — JVM в плохом состоянии, нельзя гарантировать корректность

**Антипаттерны:**
```java
// ❌ Пустой catch — проглатывание ошибки
try { ... } catch (Exception e) { }

// ❌ Логирование + перебрасывание (дублирование в логах!)
try { ... } catch (Exception e) {
    log.error("Error", e);
    throw e;  // вышестоящий handler тоже залогирует
}

// ❌ Ловить Exception/Throwable вместо конкретного типа
try { ... } catch (Exception e) { ... }  // ловит ВСЁ, включая NPE и баги

// ✅ Правильно: ловить конкретный тип
try { ... } catch (IOException e) { ... }
```

**Из проекта TransferBoss**: Разделение retriable и non-retriable ошибок в Kafka consumer:

```kotlin
// Ошибка десериализации → NonRetriableConsumerException (ретрай бессмысленен)
val event = try {
    objectMapper.readValue(message, PaymentEvent::class.java)
} catch (e: Exception) {
    throw NonRetriableConsumerException("Failed to deserialize", e)
}

// Transfer не найден → TransientConsumerException (ретрай поможет — eventual consistency)
val transfer = transferRepository.findTransferById(transferId)
    ?: throw TransientConsumerException("Transfer not found: $transferId")
```

`@RetryableTopic` автоматически ретраит TransientConsumerException, но отправляет NonRetriableConsumerException сразу в DLT.

---

### В: Что такое exception chaining (цепочка исключений)?

**Теория**: Когда ловишь одно исключение и бросаешь другое — ВСЕГДА передавай оригинал как `cause`. Иначе потеряешь stack trace и не сможешь понять корневую причину.

```java
// ❌ Плохо — потеряли причину
catch (SQLException e) {
    throw new ServiceException("DB error");  // где stack trace от SQL?
}

// ✅ Хорошо — сохраняем цепочку
catch (SQLException e) {
    throw new ServiceException("DB error", e);  // e = cause
}
```

**Из проекта TransferBoss**:
```kotlin
// PricingClient.kt:85
throw PricingUnavailableException("Pricing service error: ${e.status.code}", e)
//                                                                          ↑ cause
```

---

### В: Как Spring обрабатывает исключения в REST API?

**Теория**: Spring MVC ловит исключения из контроллеров и преобразует в HTTP-ответы. Порядок поиска обработчика:
1. `@ExceptionHandler` в самом контроллере
2. `@RestControllerAdvice` (глобальный обработчик) — в порядке `@Order`
3. Default Spring handler (белая страница ошибки)

**Из проекта TransferBoss**: `GlobalExceptionHandler` обрабатывает ВСЕ типы ошибок:
- `BusinessException` → 4xx с RFC 9457 ProblemDetail
- `MethodArgumentNotValidException` (@Valid) → 400 с массивом violations
- `ObjectOptimisticLockingFailureException` → 409 Conflict
- `Exception` (catch-all) → 500 с traceId для поддержки

Порядок `@Order(Ordered.HIGHEST_PRECEDENCE)` гарантирует, что наш handler срабатывает раньше дефолтных Spring-обработчиков.

---

### 7.4 Коллекции

### В: Иерархия коллекций в Java?

**Теория**:
```
Iterable
  └─ Collection
       ├─ List (упорядоченная, дубликаты OK)
       │    ├─ ArrayList   (массив, быстрый доступ по индексу)
       │    ├─ LinkedList  (связный список, быстрая вставка/удаление)
       │    └─ Vector      (устаревший synchronized ArrayList)
       ├─ Set (без дубликатов)
       │    ├─ HashSet        (без порядка, O(1))
       │    ├─ LinkedHashSet  (порядок вставки)
       │    └─ TreeSet        (отсортированный, O(log n))
       └─ Queue (FIFO)
            ├─ PriorityQueue  (по приоритету, heap)
            ├─ ArrayDeque     (двусторонняя очередь)
            └─ LinkedList     (тоже реализует Queue)

Map (отдельная иерархия, НЕ Collection)
  ├─ HashMap          (без порядка, O(1))
  ├─ LinkedHashMap    (порядок вставки или access-order)
  ├─ TreeMap          (отсортированный по ключу, O(log n))
  ├─ ConcurrentHashMap (потокобезопасный)
  └─ Hashtable        (устаревший synchronized HashMap)
```

---

### В: ArrayList vs LinkedList — когда что?

**Теория**:
- **ArrayList**: массив внутри. `get(i)` = O(1), `add()` в конец = amortized O(1), `add(i)` в середину = O(n) (сдвиг элементов). Кэш-friendly (элементы рядом в памяти).
- **LinkedList**: двусвязный список. `get(i)` = O(n), `add/remove` в начале/конце = O(1). Много мелких объектов → давление на GC.

**Ответ на собеседовании**: "Почти всегда ArrayList. LinkedList выигрывает только при частых вставках/удалениях в начало/середину, но на практике ArrayList быстрее даже в этих случаях из-за CPU cache locality."

---

### В: HashSet vs TreeSet vs LinkedHashSet?

**Теория**:
- **HashSet** — O(1) add/contains/remove. Без порядка. Внутри — HashMap (значение = dummy object).
- **TreeSet** — O(log n). Элементы отсортированы (Comparable или Comparator). Внутри — TreeMap (красно-чёрное дерево).
- **LinkedHashSet** — O(1) как HashSet, но сохраняет порядок вставки (внутри — LinkedHashMap).

---

### В: HashMap vs TreeMap vs LinkedHashMap vs ConcurrentHashMap?

**Теория**:
- **HashMap** — O(1), null keys/values OK, не потокобезопасный
- **TreeMap** — O(log n), отсортирован по ключу, null key = NPE
- **LinkedHashMap** — O(1), порядок вставки (или access-order для LRU cache!)
- **ConcurrentHashMap** — O(1), потокобезопасный без глобального лока. Сегментированные локи (Java 8: CAS + synchronized на бакет). null key/value = NPE.

**Подводный камень**: `Hashtable` (устаревший) — лок на ВЕСЬ объект при каждой операции. `ConcurrentHashMap` — лок только на отдельный бакет. Разница в production под нагрузкой — огромная.

---

### В: Fail-fast vs Fail-safe итераторы?

**Теория**:
- **Fail-fast** (ArrayList, HashMap) — если коллекция изменена во время итерации → `ConcurrentModificationException`. Механизм: `modCount` — счётчик модификаций.
- **Fail-safe** (ConcurrentHashMap, CopyOnWriteArrayList) — итерация по копии или snapshot. Не бросает exception, но может не увидеть последние изменения.

```java
// Fail-fast — ConcurrentModificationException!
for (String s : list) {
    list.remove(s);  // ❌ модификация во время итерации
}

// Правильно — Iterator.remove()
Iterator<String> it = list.iterator();
while (it.hasNext()) {
    if (condition) it.remove();  // ✅
}

// Или removeIf (Java 8+)
list.removeIf(s -> condition);  // ✅
```

---

### В: Comparable vs Comparator?

**Теория**:
- **Comparable** — "я сам знаю, как себя сравнивать". Реализуется самим классом: `class User implements Comparable<User>`. Один `compareTo()` — одна естественная сортировка.
- **Comparator** — "внешний сравниватель". Отдельный объект: `Comparator.comparing(User::getName)`. Можно создать сколько угодно разных сортировок.

```java
// Comparable — один способ
class Transfer implements Comparable<Transfer> {
    public int compareTo(Transfer other) {
        return this.createdAt.compareTo(other.createdAt);
    }
}

// Comparator — гибко, несколько способов
list.sort(Comparator.comparing(Transfer::getAmount).reversed());
list.sort(Comparator.comparing(Transfer::getStatus).thenComparing(Transfer::getCreatedAt));
```

---

### В: Unmodifiable vs Immutable коллекции?

**Теория**:
- `Collections.unmodifiableList(list)` — обёртка, бросает UnsupportedOperationException при мутации. НО: если оригинальный list изменится — unmodifiable тоже покажет изменения!
- `List.of("a", "b")` (Java 9+) — настоящая immutable коллекция. Нет оригинала, который можно изменить. null не допускается.
- `List.copyOf(list)` (Java 10+) — immutable копия существующей коллекции.

---

### 7.5 Многопоточность

### В: Thread vs Runnable vs Callable?

**Теория**:
- **Thread** — наследование `extends Thread`. Ограничено (Java: один базовый класс). Устаревший подход.
- **Runnable** — интерфейс с `void run()`. Нет возвращаемого значения, нет checked exceptions.
- **Callable<V>** — интерфейс с `V call() throws Exception`. Возвращает результат, может бросать exception. Используется с `ExecutorService.submit()` → `Future<V>`.

```java
// Runnable — нет результата
Runnable task = () -> System.out.println("work");
new Thread(task).start();

// Callable — есть результат
Callable<Integer> task = () -> { return 42; };
Future<Integer> future = executor.submit(task);
int result = future.get(); // блокирует до готовности
```

---

### В: Жизненный цикл потока?

**Теория**:
```
NEW → (start()) → RUNNABLE → (scheduler) → RUNNING
                                 ↕
                    BLOCKED (ждёт монитор synchronized)
                    WAITING (wait(), join(), park())
                    TIMED_WAITING (sleep(ms), wait(ms))
                                 ↓
                           TERMINATED
```

- `NEW` — создан, но `start()` ещё не вызван
- `RUNNABLE` — готов к выполнению или выполняется (JVM не разделяет)
- `BLOCKED` — ждёт захвата монитора (другой поток в synchronized блоке)
- `WAITING` — ждёт уведомления (`wait()`, `join()`, `LockSupport.park()`)
- `TERMINATED` — завершён (нормально или с exception)

---

### В: synchronized — метод vs блок?

**Теория**:
- **synchronized метод** — лок на `this` (для instance) или на `Class` (для static). Весь метод — критическая секция.
- **synchronized блок** — лок на конкретный объект. Меньшая гранулярность → лучше concurrency.

```java
// Synchronized метод — лок на this
public synchronized void method() { /* ... */ }

// Synchronized блок — лок на конкретный объект
public void method() {
    synchronized (lockObject) {
        // только этот участок под локом
    }
    // этот код выполняется без лока
}
```

**Подводный камень**: synchronized на String literal — плохо! `synchronized("lock")` — все потоки в JVM с тем же literal будут конкурировать (String interning).

---

### В: wait() / notify() / notifyAll()?

**Теория**: Механизм координации потоков через монитор объекта:
- `wait()` — поток отпускает монитор и засыпает, пока другой поток не вызовет `notify()`
- `notify()` — будит ОДИН случайный ждущий поток
- `notifyAll()` — будит ВСЕ ждущие потоки (они конкурируют за монитор)

Обязательно вызывать внутри `synchronized` блока! Иначе — `IllegalMonitorStateException`.

```java
synchronized (queue) {
    while (queue.isEmpty()) {
        queue.wait();       // отпустить лок, ждать
    }
    item = queue.poll();
}

// Другой поток:
synchronized (queue) {
    queue.add(item);
    queue.notifyAll();      // разбудить ждущих
}
```

**Подводный камень**: Всегда `wait()` в цикле `while`, не в `if`! Возможны spurious wakeups (поток проснулся без notify).

---

### В: ReentrantLock vs synchronized?

**Теория**:
| | synchronized | ReentrantLock |
|---|---|---|
| Синтаксис | ключевое слово | объект |
| tryLock (с таймаутом) | нет | да |
| Interruptible lock | нет | да (`lockInterruptibly()`) |
| Fairness (FIFO) | нет | да (опция) |
| Condition variables | один (wait/notify) | несколько (`newCondition()`) |
| Автоматический unlock | да (выход из блока) | нет (надо в finally!) |

```java
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    // критическая секция
} finally {
    lock.unlock();  // ОБЯЗАТЕЛЬНО в finally!
}

// tryLock — не блокирует, если лок занят
if (lock.tryLock(5, TimeUnit.SECONDS)) {
    try { /* ... */ } finally { lock.unlock(); }
} else {
    // лок не получен за 5 секунд
}
```

---

### В: ExecutorService и ThreadPool?

**Теория**: Создавать поток на каждую задачу — дорого (allocate stack, OS thread). ThreadPool переиспользует потоки:

- `Executors.newFixedThreadPool(n)` — фиксированное число потоков. Лишние задачи в очереди.
- `Executors.newCachedThreadPool()` — потоки создаются по необходимости, переиспользуются. Опасно при спайках (может создать тысячи потоков!).
- `Executors.newSingleThreadExecutor()` — один поток, задачи выполняются последовательно.
- `Executors.newScheduledThreadPool(n)` — для периодических задач.
- `ThreadPoolExecutor(core, max, keepAlive, queue)` — полный контроль.

**Подводный камень**: `Executors.newFixedThreadPool()` использует unbounded `LinkedBlockingQueue` — при перегрузке задачи копятся в памяти → OOM. В продакшне всегда используй `ThreadPoolExecutor` с bounded queue и rejection policy.

---

### В: Future vs CompletableFuture?

**Теория**:
- **Future** — результат async операции. `get()` блокирует. Нет callback. Нет композиции.
- **CompletableFuture** (Java 8+) — non-blocking callbacks, chain, compose:

```java
CompletableFuture.supplyAsync(() -> fetchUser(id))          // async
    .thenApply(user -> user.getName())                       // map
    .thenCombine(fetchBalance(id), (name, bal) -> ...)       // join two futures
    .thenAccept(result -> log.info(result))                  // consume
    .exceptionally(ex -> { log.error(ex); return fallback; }) // error handling
```

---

### В: CountDownLatch, CyclicBarrier, Semaphore?

**Теория**:
- **CountDownLatch** — "ждём N событий". Счётчик уменьшается (`countDown()`), ждущие потоки (`await()`) освобождаются когда счётчик = 0. Одноразовый.
- **CyclicBarrier** — "все N потоков ждут друг друга". Когда все дошли до барьера — все освобождаются. Переиспользуемый.
- **Semaphore** — "не больше N одновременно". `acquire()` → если свободных permits нет — ждёт. `release()` → освобождает permit. Пример: пул соединений.

```java
// Semaphore — ограничение параллелизма
Semaphore semaphore = new Semaphore(10); // макс 10 одновременных
semaphore.acquire();
try {
    callExternalApi(); // не больше 10 потоков здесь одновременно
} finally {
    semaphore.release();
}
```

---

### В: ThreadLocal — что это?

**Теория**: Переменная, у которой свое значение для каждого потока. Потоки не видят значения друг друга. Применение: хранение контекста (userId, traceId) без передачи параметром через весь call stack.

**Из проекта TransferBoss**: `MDC` (Mapped Diagnostic Context) из SLF4J — это ThreadLocal для логирования:
```kotlin
// PaymentEventConsumer.kt:64,139-140
MDC.put("traceId", event.eventId)   // записать traceId в ThreadLocal
// ... все логи в этом потоке содержат traceId ...
MDC.clear()                          // обязательно очистить!
```

**Подводный камень**: ВСЕГДА очищай ThreadLocal в `finally`! В ThreadPool поток переиспользуется — если не очистить, следующая задача увидит чужой контекст. Утечка памяти при long-lived threads.

---

### В: Deadlock — что это и как избежать?

**Теория**: Два потока блокируют друг друга: поток A держит лок 1 и ждёт лок 2, поток B держит лок 2 и ждёт лок 1. Никто не может продолжить.

```java
// Deadlock!
Thread A: synchronized(lock1) { synchronized(lock2) { ... } }
Thread B: synchronized(lock2) { synchronized(lock1) { ... } }
```

Как избежать:
1. **Один порядок захвата** — всегда lock1 → lock2, никогда lock2 → lock1
2. **tryLock с таймаутом** — если не получил за N секунд, отпустить всё и повторить
3. **Минимизация вложенных локов** — не захватывай несколько локов одновременно
4. **Lock ordering** — сортировать объекты по ID перед захватом

---

### В: Java Memory Model и happens-before?

**Теория**: JMM определяет, когда изменения одного потока видны другому. Без синхронизации — компилятор и CPU могут переупорядочить операции. Happens-before гарантирует видимость:

- `synchronized` unlock → lock на том же мониторе
- `volatile` write → read того же поля
- `Thread.start()` → первая операция в потоке
- Последняя операция в потоке → `Thread.join()` вызывающего
- `final` поля — после конструктора видны всем потокам

Проще: "если есть happens-before связь между записью и чтением — чтение увидит запись. Если нет — может увидеть, может нет."

---

### 7.6 HashMap, Stream, Sealed, GC (продвинутые вопросы)

### В: HashMap — как работает внутри?

**Теория**: HashMap хранит пары key→value в массиве бакетов (Node[]). При `put(key, value)`:
1. Вычисляется `hashCode()` ключа
2. Определяется индекс бакета: `(n-1) & hash`
3. Если бакет пустой — записывает. Если коллизия — цепочка (связный список)
4. Если цепочка > 8 элементов и capacity ≥ 64 — дерево (TreeMap, O(log n))
5. Если load factor > 0.75 — resize (x2), все элементы пересчитываются

**Подводный камень**: `equals()` и `hashCode()` ОБЯЗАНЫ быть согласованы: если `a.equals(b)` → `a.hashCode() == b.hashCode()`. Иначе элемент "потеряется" в HashMap.

---

### В: Sealed classes (Java 17+) — что это?

**Теория**: `sealed class` ограничивает, кто может его наследовать. Компилятор знает ВСЕ подтипы → может проверять exhaustive `switch` (в Java 21 — pattern matching). Идеально для конечных наборов: статусы, типы событий, результаты операций.

**Из проекта TransferBoss**: (Kotlin sealed class, аналог Java sealed):

```kotlin
// TransferStatus.kt:16
sealed class TransferStatus(val value: String) {
    data object Created : TransferStatus("CREATED")
    data object PaymentPending : TransferStatus("PAYMENT_PENDING")
    data object Completed : TransferStatus("COMPLETED")
    // ...
}
```

Компилятор гарантирует, что `when` обрабатывает ВСЕ варианты — нельзя забыть обработать `PaymentFailed`:

```kotlin
// TransferStatus.kt:40-57
fun allowedTransitions(): Set<TransferStatus> = when (this) {
    Created -> ...
    PaymentPending -> ...
    // Если забудешь один — ошибка компиляции!
}
```

---

### В: Concurrency — что такое volatile, synchronized, CAS?

**Теория**:
- **volatile** — гарантирует visibility: все потоки видят последнее значение. НЕ гарантирует atomicity (i++ не атомарна даже с volatile).
- **synchronized** — mutual exclusion: только один поток выполняет блок. Гарантирует и visibility, и atomicity.
- **CAS** (Compare-And-Swap) — атомарная операция CPU: "если текущее значение = expected, замени на new". Основа `AtomicInteger`, `ConcurrentHashMap`. Lock-free, но может быть медленнее при высокой конкуренции (постоянные retry).

**Подводный камень**: `HashMap` не потокобезопасен. Для конкурентного доступа — `ConcurrentHashMap` (lock-free чтение, сегментные локи на запись).

---

### В: Stream API — ленивость и short-circuiting?

**Теория**: Stream-операции делятся на intermediate (map, filter, flatMap — ленивые) и terminal (collect, forEach, reduce — запускают обработку). Ленивость: `list.stream().filter(x -> ...).map(x -> ...)` — ни один элемент не обработан до terminal-операции. Short-circuiting: `findFirst()`, `anyMatch()`, `limit()` — останавливают обработку, не проходя все элементы.

---

### В: Java 17-21 — ключевые фичи?

**Теория**:
- **Java 17**: Sealed classes, pattern matching for `instanceof` (`if (obj instanceof String s)`), records, text blocks
- **Java 21**: Virtual threads (Project Loom — миллионы легковесных тредов), pattern matching в switch, sequenced collections, record patterns

**Из проекта TransferBoss**: Используется Java 21. Virtual threads — альтернатива корутинам для IO-bound задач.

---

### В: Garbage Collection — G1 vs ZGC?

**Теория**:
- **G1** (default с Java 9): делит heap на регионы, собирает сначала регионы с наибольшим количеством мусора. Пауза: ~200ms. Хорош для большинства приложений.
- **ZGC** (Java 15+): concurrent GC, пауза < 1ms (!) независимо от heap size. Подходит для low-latency (финтех, трейдинг). Чуть больше overhead по CPU.
- **Shenandoah**: похож на ZGC, от Red Hat.

**Из проекта TransferBoss**: Для финтеха ZGC идеален — пользователи не должны ждать GC-паузу при денежном переводе.

---

## 8. Unit Testing

### В: Test Pyramid — что это?

**Теория**: Тесты строятся пирамидой:
- **Unit (внизу)**: много, быстрые, изолированные (моки). Тестируют одну функцию.
- **Integration (середина)**: меньше, медленнее. Тестируют взаимодействие компонентов (DB, Kafka, Redis).
- **E2E (вверху)**: мало, самые медленные. Тестируют полный сценарий.

Соотношение: ~70% unit / ~20% integration / ~10% E2E.

---

### В: MockK vs Mockito — в чём разница?

**Теория**: Mockito — Java-библиотека для мокирования. MockK — Kotlin-native, поддерживает suspend-функции, extension functions, объекты. Оба делают одно: создают фейковые объекты, которые возвращают заданные значения. MockK идиоматичнее для Kotlin:

```kotlin
// Mockito (Java-стиль)
when(repository.findById(id)).thenReturn(entity)
verify(repository, times(1)).save(any())

// MockK (Kotlin-стиль)
every { repository.findById(id) } returns entity
verify(exactly = 1) { repository.save(any()) }
```

**Из проекта TransferBoss**:

```kotlin
// TransferServiceTest.kt — MockK
@ExtendWith(MockKExtension::class)
class TransferServiceTest {
    @MockK private lateinit var transferRepository: TransferRepository
    @MockK private lateinit var pricingClient: PricingClient

    @Test
    fun `createTransfer - success`() {
        every { transferRepository.findByIdempotencyKey(any()) } returns null
        every { transferRepository.save(any()) } answers { firstArg() }
        // ...
    }
}
```

---

### В: Testcontainers — что это и зачем?

**Теория**: Testcontainers запускает реальные Docker-контейнеры (PostgreSQL, Redis, Kafka) для интеграционных тестов. Преимущества перед H2/embedded: тестируешь ТУ ЖЕ БД, что в проде. PostgreSQL-specific фичи (partial indexes, JSONB, MVCC) работают. H2 — другой движок, поведение отличается.

**Из проекта TransferBoss**:

```kotlin
// IntegrationTestBase.kt:16-41
companion object {
    private val postgres = PostgreSQLContainer("postgres:16-alpine").apply {
        withDatabaseName("transfer_db")
        withUsername("test")
        withPassword("test")
        start()
    }

    private val redis = GenericContainer("redis:7-alpine").apply {
        withExposedPorts(6379)
        start()
    }

    @DynamicPropertySource
    @JvmStatic
    fun configureProperties(registry: DynamicPropertyRegistry) {
        registry.add("spring.datasource.url") { postgres.jdbcUrl }
        registry.add("spring.data.redis.host") { redis.host }
        registry.add("spring.data.redis.port") { redis.firstMappedPort }
    }
}
```

Kafka тестируется через `@EmbeddedKafka` (легче, чем Testcontainers Kafka):

```kotlin
// IntegrationTestBase.kt:13
@EmbeddedKafka(partitions = 1, topics = ["payments.payment.captured", ...])
```

---

### В: Как тестировать Kafka consumer с ретраями?

**Теория**: Нужно проверить, что при transient ошибке сообщение попадает в retry-топик, а при fatal ошибке — в DLT. `@EmbeddedKafka` запускает in-memory брокер, Spring `@RetryableTopic` создаёт retry/DLT топики автоматически.

**Из проекта TransferBoss**: `RetryDltIntegrationTest` отправляет "ядовитое" сообщение и проверяет, что оно попало в DLT. Также тестирует, что корректные сообщения обрабатываются с первой попытки.

---

## 9. REST API Design

### В: POST возвращает 201 или 200?

**Теория**: `201 Created` — ресурс создан. `200 OK` — запрос обработан успешно (ресурс уже существовал). RFC 7231: POST для создания → 201 + `Location` header. Idempotent retry → 200 с существующим ресурсом.

**Из проекта TransferBoss**:

```kotlin
// TransferController.kt:63-71
return if (isNew) {
    ResponseEntity
        .created(URI.create("/api/v1/transfers/${result.transfer.id}"))  // 201 + Location
        .body(response)
} else {
    ResponseEntity.ok(response)  // 200 — idempotency hit
}
```

---

### В: RFC 9457 Problem Details — что это?

**Теория**: Стандартный формат ошибок для HTTP API. Вместо кастомных JSON-структур используется единый формат с полями: `type` (URI ошибки), `title`, `status`, `detail`, `instance` (URI запроса). Позволяет добавлять custom properties.

**Из проекта TransferBoss**:

```kotlin
// GlobalExceptionHandler.kt:34-43
val problem = ProblemDetail.forStatus(ex.statusCode).apply {
    type = URI.create(ex.errorType)
    title = ex.title
    detail = ex.message
    instance = extractPath(request)
    setProperty("traceId", getTraceId())     // custom property для поддержки
    setProperty("timestamp", Instant.now().toString())
}
```

Для validation ошибок — массив violations:

```kotlin
// GlobalExceptionHandler.kt:62-69
val problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST).apply {
    title = "Validation Error"
    detail = "Request body has ${violations.size} validation error(s)"
    setProperty("violations", violations)  // [{field, message, rejectedValue}, ...]
}
```

---

### В: 400 Bad Request vs 422 Unprocessable Entity?

**Теория**:
- **400** — синтаксическая ошибка: невалидный JSON, отсутствует обязательное поле, неверный формат UUID
- **422** — синтаксически верно, но семантически некорректно: неподдерживаемый коридор, минимальная сумма не достигнута, котировка истекла

**Из проекта TransferBoss**: Validation (`@Valid`) → 400. Business rules (`UnsupportedCorridorException`, `QuoteExpiredException`) → 422.

---

## 10. Docker & Kubernetes

### В: Multi-stage Dockerfile — зачем?

**Теория**: Multi-stage позволяет отделить этап сборки от финального образа. Build stage содержит JDK, Gradle, исходники. Final stage — только JRE + JAR. Результат: образ меньше (200MB вместо 800MB), нет компилятора и исходников в продакшне.

**Из проекта TransferBoss**: Для Go-сервиса ещё экстремальнее — scratch image (0MB base):

Notification-gateway Dockerfile:
```dockerfile
# Build stage: Go компилятор + зависимости
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /gateway ./cmd/main.go

# Final stage: пустой образ + один бинарник
FROM scratch
COPY --from=builder /gateway /gateway
ENTRYPOINT ["/gateway"]
```

Финальный образ: ~10MB (один статический бинарник).

---

### В: Docker Compose — как связать несколько сервисов?

**Теория**: Docker Compose запускает несколько контейнеров с общей сетью. Сервисы обращаются друг к другу по имени контейнера (DNS): `postgres:5432`, `kafka:9092`. Volumes для persistent data, depends_on для порядка запуска, healthcheck для readiness.

**Из проекта TransferBoss**: 11 сервисов в одном docker-compose:
- PostgreSQL 16, Redis 7, Kafka (KRaft), Consul
- transfer-service, outbox-service, pricing-service, mock-payment, mock-payout, notification-gateway
- Prometheus для мониторинга

---

### В: Kubernetes — основные объекты?

**Теория**:
- **Pod** — минимальная единица, 1+ контейнеров
- **Deployment** — управляет ReplicaSet (сколько Pod'ов, rolling update)
- **Service** — стабильный endpoint для Pod'ов (ClusterIP, NodePort, LoadBalancer)
- **ConfigMap / Secret** — конфигурация / чувствительные данные
- **Ingress** — HTTP routing (маршрутизация по URL/host)
- **HPA** — автоскейлинг по CPU/memory/custom metrics

---

### В: Probes в Kubernetes — какие бывают?

**Теория**:
- **Liveness** — жив ли контейнер? Если нет — K8s перезапускает Pod.
- **Readiness** — готов ли принимать трафик? Если нет — убирается из Service endpoints.
- **Startup** — медленный старт (JVM warmup). Пока не пройдёт — liveness/readiness не проверяются.

**Из проекта TransferBoss**: Spring Actuator предоставляет health endpoint:

```yaml
# application.yml:79-85
management.endpoints.web.exposure.include: health,info,prometheus,metrics
management.endpoint.health.show-details: always
```

Endpoint `/actuator/health` — для liveness/readiness проб в K8s.

---

## 11. CI/CD

### В: Как устроен CI pipeline?

**Теория**: CI (Continuous Integration) — автоматическая сборка и тестирование при каждом push/PR. Типичные стадии: lint → compile → unit tests → integration tests → build artifact → (deploy).

**Из проекта TransferBoss**: GitHub Actions с path-based change detection — тестируются только изменённые сервисы:

```yaml
# ci.yml — path-based detection (dorny/paths-filter)
# Если изменён только notification-gateway — не запускать тесты transfer-service
```

Concurrency control:
```yaml
# ci.yml:14-16
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true  # Отменить предыдущий запуск на том же PR
```

CI Gate job — финальная проверка, нужна для branch protection:
```yaml
# ci.yml
ci-gate:
  if: always()
  needs: [transfer-service, outbox-service, pricing-service, notification-gateway]
  # Проверяет, что все required jobs прошли
```

---

### В: Trunk-based development vs GitFlow?

**Теория**:
- **GitFlow**: develop, release, hotfix ветки. Сложно, долгий цикл, merge-hell.
- **Trunk-based**: все работают с main/trunk. Feature branches короткоживущие (1-2 дня). Частые мержи, CI на каждый PR. Быстрый feedback loop.

**Из проекта TransferBoss**: Trunk-based — все PR-ы в `main`, CI обязателен перед мержем.

---

## 12. Linux

### В: Основные команды для DevOps?

**Основные**:
- `ps aux` — список процессов, `top` / `htop` — мониторинг CPU/RAM
- `df -h` — место на дисках, `du -sh *` — размер директорий
- `free -h` — RAM (total/used/available)
- `ss -tlnp` — открытые порты (замена netstat)
- `journalctl -u service-name -f` — логи systemd-сервиса
- `tail -f /var/log/app.log | grep ERROR` — следить за ошибками
- `curl -v http://localhost:8080/actuator/health` — проверка endpoint

### В: Как найти, что ест память/CPU?

- `top` → сортировка по MEM (Shift+M) или CPU (Shift+P)
- `htop` — интерактивный вариант
- `docker stats` — ресурсы контейнеров
- `jcmd <pid> GC.heap_info` — heap для Java-процесса

### В: Как посмотреть, что слушает порт 8080?

```bash
ss -tlnp | grep 8080
# или
lsof -i :8080
```

---

## 13. Git

### В: Rebase vs Merge — когда что?

**Теория**:
- **Merge** — создаёт merge commit. История нелинейная, но видно, когда ветка была создана и влита.
- **Rebase** — переносит коммиты "поверх" main. Линейная история, чистый git log. НО: не ребейсь на shared ветках (перезапись истории = проблемы для коллег).

Правило: `rebase` свою feature-ветку перед merge. `merge --no-ff` для влития в main (сохраняет контекст PR).

### В: Как отменить последний коммит?

```bash
git reset --soft HEAD~1    # отменить коммит, изменения останутся staged
git reset --mixed HEAD~1   # отменить коммит, изменения unstaged (default)
git reset --hard HEAD~1    # отменить коммит и УДАЛИТЬ изменения (ОСТОРОЖНО!)
git revert HEAD            # создать новый коммит, отменяющий предыдущий (безопасно для shared)
```

### В: Cherry-pick — что это?

**Теория**: Копирует один конкретный коммит из другой ветки в текущую. Используется для hotfix: забрать fix из develop в release, или из feature в main без мержа всей ветки.

---

## 14. Behavioral / System Design

### В: Расскажите о сложной технической проблеме, которую вы решили

**Пример 1 — Hibernate bpchar**: При добавлении `ddl-auto: validate` в CI, тесты начали падать. Hibernate 6 не распознавал PostgreSQL тип `bpchar` (internal name для `CHAR(N)`) как совместимый с `char`. Решение: заменить `CHAR(N)` на `VARCHAR(N)` в миграциях. Урок: всегда тестируй schema validation в CI с реальной БД (Testcontainers), не с H2.

**Пример 2 — Testcontainers Docker 29**: После обновления Docker Desktop до 29, Testcontainers не могли подключиться (изменился протокол). Решение задокументировано в runbook для команды.

---

### В: Спроектируйте систему денежных переводов

**Ответ (основан на TransferBoss)**:

1. **API Gateway** → REST API для клиентов (mobile/web)
2. **Transfer Service** — создание перевода, state machine, idempotency
3. **Pricing Service** — котировки, обменные курсы (gRPC для скорости)
4. **Payment Service** — интеграция с платёжными шлюзами
5. **Payout Service** — выплата получателю
6. **Notification Service** — SMS/Email/Push

**Коммуникация**: Kafka для async events (Saga pattern), gRPC для sync queries (pricing).

**Consistency**: Outbox pattern (нет dual-write), idempotency на 3 уровнях.

**Resilience**: Circuit breaker (pricing), retry с exponential backoff (Kafka), graceful degradation (Redis cache).

**Scaling**: Kafka партиционирование по коридорам, read replicas PostgreSQL, Redis cluster.

---

### В: Как бы вы масштабировали систему?

1. **Transfer Service**: горизонтально (stateless). Distributed lock через Consul/Redis обеспечивает корректность.
2. **Kafka**: больше партиций = больше параллелизм (но не больше, чем консьюмеров).
3. **PostgreSQL**: read replicas для GET-запросов, partitioning таблицы transfers по дате.
4. **Redis**: Redis Cluster (шардирование по ключу).
5. **gRPC pricing**: connection pooling + load balancing (L7).

---

### В: Как обрабатывать дубликаты запросов?

**Ответ**: Три уровня (defense in depth):
1. **API**: `X-Idempotency-Key` header + distributed lock → предотвращает одновременные дубликаты
2. **DB**: `UNIQUE(idempotency_key)` constraint → ловит дубликаты при race condition
3. **Kafka consumer**: `consumed_events` таблица с `eventId` → дедупликация при redelivery

Каждый уровень ловит то, что пропустил предыдущий.

---

### В: Расскажите о вашем опыте с микросервисами

**Ответ**: TransferBoss — 5 сервисов на разных стеках:
- **transfer-service** (Kotlin/Spring Boot) — основной бизнес-сервис, 7 Flyway миграций, 14 статусов перевода
- **outbox-service** (Kotlin/Spring Boot) — relay для Outbox pattern
- **pricing-service** (Kotlin/Spring Boot) — gRPC сервер котировок
- **mock-payment/mock-payout** (Kotlin/Spring Boot) — эмуляция внешних систем
- **notification-gateway** (Go) — минимальный образ, Kafka consumer → delivery adapters

Ключевые решения:
- Saga choreography (не orchestration) — проще, нет single point of failure
- UUID references вместо JPA relationships — независимые модули
- Outbox pattern вместо dual-write — guaranteed delivery
- Cache-aside с graceful degradation — Redis необязателен для работы

---

## 15. Design Patterns (GoF)

### Creational Patterns

### В: Singleton — что это и как реализовать потокобезопасно?

**Теория**: Гарантирует, что у класса ровно один экземпляр + глобальная точка доступа. Варианты:

```java
// 1. Eager initialization (самый простой)
public class Singleton {
    private static final Singleton INSTANCE = new Singleton();
    private Singleton() {}
    public static Singleton getInstance() { return INSTANCE; }
}

// 2. Double-checked locking (lazy, thread-safe)
public class Singleton {
    private static volatile Singleton instance;  // volatile обязателен!
    private Singleton() {}
    public static Singleton getInstance() {
        if (instance == null) {                  // первая проверка без лока
            synchronized (Singleton.class) {
                if (instance == null) {           // вторая проверка под локом
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}

// 3. Enum singleton (рекомендация Effective Java — самый безопасный)
public enum Singleton {
    INSTANCE;
    public void doWork() { ... }
}
```

**Из проекта TransferBoss**: Spring-бины по умолчанию — singleton scope. `TransferService`, `PricingClient` и т.д. — один экземпляр на весь контейнер. Spring управляет lifecycle, не нужен рукописный Singleton.

**Подводный камень**: `volatile` в double-checked locking — обязателен! Без него возможна ситуация: один поток видит наполовину инициализированный объект (instruction reordering).

---

### В: Factory Method / Abstract Factory?

**Теория**:
- **Factory Method** — метод, который создаёт объекты. Вместо `new ConcreteClass()` — вызов фабричного метода. Позволяет подменять реализацию.
- **Abstract Factory** — фабрика фабрик. Создаёт семейство связанных объектов (например, UI-элементы для разных ОС: WindowsButton + WindowsCheckbox).

```java
// Factory Method
public interface Notification { void send(String msg); }
public class EmailNotification implements Notification { ... }
public class SmsNotification implements Notification { ... }

public class NotificationFactory {
    public static Notification create(String type) {
        return switch (type) {
            case "email" -> new EmailNotification();
            case "sms" -> new SmsNotification();
            default -> throw new IllegalArgumentException("Unknown: " + type);
        };
    }
}
```

**Из проекта TransferBoss**: `TransferStatus.fromString()` — по сути Factory Method:
```kotlin
// TransferStatus.kt:80-96
fun fromString(value: String): TransferStatus = when (value) {
    "CREATED" -> Created
    "PAYMENT_PENDING" -> PaymentPending
    // ...
}
```

---

### В: Builder — когда использовать?

**Теория**: Для создания объектов с множеством параметров (особенно опциональных). Вместо конструктора с 10 параметрами — цепочка вызовов. Immutable после build.

```java
Transfer transfer = Transfer.builder()
    .senderId(UUID.randomUUID())
    .sendAmount(new BigDecimal("100.00"))
    .sendCurrency("USD")
    .receiveCurrency("PHP")
    .build();
```

В Java: Lombok `@Builder` или ручной builder. В Kotlin: named arguments + default values заменяют builder:
```kotlin
// Kotlin не нуждается в Builder — named arguments!
Transfer(
    senderId = UUID.randomUUID(),
    sendAmount = BigDecimal("100.00"),
    sendCurrency = "USD",
    receiveCurrency = "PHP"
)
```

---

### Structural Patterns

### В: Adapter — что это?

**Теория**: Преобразует интерфейс одного класса в интерфейс, который ожидает клиент. "Переходник" между несовместимыми интерфейсами.

Пример из Java SDK: `Arrays.asList()` — адаптирует массив к интерфейсу `List`. `InputStreamReader` — адаптирует byte stream к char stream.

---

### В: Decorator — что это?

**Теория**: Оборачивает объект, добавляя функциональность без изменения оригинала. Цепочка декораторов.

Пример из Java SDK: `BufferedInputStream(new FileInputStream("file"))` — добавляет буферизацию к любому InputStream. `Collections.synchronizedList(list)` — добавляет synchronized к любому List.

---

### В: Proxy — что это?

**Теория**: Объект-заместитель, контролирующий доступ к оригиналу. Виды: protection proxy (проверка прав), caching proxy (кэширование), remote proxy (удалённый вызов), lazy proxy (отложенная инициализация).

**Из проекта TransferBoss**: Spring AOP создаёт proxy вокруг бинов:
- `@Transactional` на `TransferService` → Spring создаёт CGLIB proxy, который оборачивает вызов метода в транзакцию
- Каждый вызов `createTransfer()` на самом деле идёт через proxy: `open tx → target method → commit/rollback`

---

### В: Facade — что это?

**Теория**: Простой интерфейс к сложной подсистеме. Скрывает сложность за одним методом.

**Из проекта TransferBoss**: `TransferService.createTransfer()` — фасад для 8 шагов:
1. Проверить idempotency key
2. Валидировать бизнес-правила
3. Найти получателя
4. Проверить delivery method
5. Валидировать котировку через gRPC
6. Создать Transfer entity
7. Создать OutboxEvent
8. Сохранить оба в одной транзакции

Клиент (контроллер) вызывает один метод — не знает про все эти шаги.

---

### Behavioral Patterns

### В: Strategy — что это?

**Теория**: Определяет семейство алгоритмов, инкапсулирует каждый и делает их взаимозаменяемыми. Клиент выбирает стратегию в runtime.

```java
// Strategy = интерфейс
interface SortStrategy { void sort(int[] arr); }
class QuickSort implements SortStrategy { ... }
class MergeSort implements SortStrategy { ... }

// Клиент выбирает стратегию
class Sorter {
    private SortStrategy strategy;
    void setStrategy(SortStrategy s) { this.strategy = s; }
    void sort(int[] arr) { strategy.sort(arr); }
}
```

**Из проекта TransferBoss**: `DistributedLockService` — Strategy pattern:
- `ConsulDistributedLockService` — стратегия для прода
- `NoOpDistributedLockService` — стратегия для тестов
- Spring выбирает стратегию через `@ConditionalOnProperty`

---

### В: Observer — что это?

**Теория**: Один объект (subject) уведомляет множество подписчиков (observers) об изменениях. Слабая связь: subject не знает, кто подписан.

Пример из Java SDK: `java.util.Observer` (устаревший), `PropertyChangeListener`, `Flow.Publisher` (Reactive Streams).

**Из проекта TransferBoss**: Kafka = Observer pattern на уровне системы:
- **Subject**: transfer-service публикует события (`transfers.payment.requested`)
- **Observers**: mock-payment, notification-gateway подписаны и реагируют
- Слабая связь: transfer-service не знает, кто слушает его топики

---

### В: State — что это?

**Теория**: Объект меняет своё поведение при изменении внутреннего состояния. Вместо цепочки `if/else` на статус — каждый статус = отдельный класс с методами.

**Из проекта TransferBoss**: `TransferStatus` sealed class — каждый статус знает свои допустимые переходы:
```kotlin
sealed class TransferStatus(val value: String) {
    fun allowedTransitions(): Set<TransferStatus> = when (this) {
        Created -> setOf(ComplianceCheck, PaymentPending, Cancelled)
        PaymentPending -> setOf(PaymentCaptured, PaymentFailed)
        Completed -> emptySet()  // terminal
        // ...
    }
}
```
Вместо `if (status == "CREATED" && newStatus == "PAYMENT_PENDING")` — `status.canTransitionTo(newStatus)`. Невозможно забыть обработать статус (exhaustive when).

---

### В: Template Method — что это?

**Теория**: Определяет скелет алгоритма в базовом классе, а конкретные шаги — в потомках. "Алгоритм фиксирован, детали переопределяются."

Пример из Java SDK: `AbstractList.get()` — абстрактный, `add()` — бросает UnsupportedOperationException по умолчанию. `HttpServlet.doGet()`, `doPost()` — шаблонный метод `service()` вызывает нужный.

Пример из Spring: `JdbcTemplate.execute()` — шаблон "открой соединение → выполни запрос → закрой соединение". Ты пишешь только запрос.

---

### В: Command — что это?

**Теория**: Инкапсулирует запрос как объект. Позволяет параметризовать клиентов, ставить запросы в очередь, логировать, отменять (undo).

**Из проекта TransferBoss**: `CreateTransferCommand` — DTO, представляющий команду на создание перевода:
```kotlin
data class CreateTransferCommand(
    val senderId: UUID,
    val idempotencyKey: UUID,
    val sendAmount: BigDecimal,
    val sendCurrency: String,
    // ...
)
```
Контроллер создаёт Command из HTTP request, передаёт в Service. Если нужно — можно сохранить в очередь, повторить, залогировать.
