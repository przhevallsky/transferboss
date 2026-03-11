# UUID-ссылки vs JPA-связи (@ManyToOne / @OneToMany)

## Суть вопроса

В transfer-service все связи между сущностями реализованы через **голые UUID-поля**, а не через JPA-аннотации `@ManyToOne`, `@OneToMany`, `@JoinColumn`.

```kotlin
// Transfer.kt — ТАК сделано у нас (UUID-ссылки)
@Column(name = "sender_id", nullable = false, updatable = false)
val senderId: UUID,

@Column(name = "recipient_id", nullable = false)
val recipientId: UUID,
```

```kotlin
// Как выглядело бы с JPA-связями (МЫ ТАК НЕ ДЕЛАЕМ)
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "recipient_id", nullable = false)
val recipient: Recipient,
```

---

## Какие связи есть в transfer-service

| Связь | Поле | FK в БД | Почему UUID |
|---|---|---|---|
| Transfer → Sender | `senderId: UUID` | Нет | Sender живёт в другом сервисе |
| Transfer → Recipient | `recipientId: UUID` | Нет | Слабая связанность внутри сервиса |
| Transfer → Quote | `quoteId: UUID` | Нет | Quote живёт в pricing-service (Redis) |
| Transfer → Payment | `paymentId: UUID?` | Нет | Заполняется асинхронно из Kafka-события |
| Transfer → Payout | `payoutId: UUID?` | Нет | Заполняется асинхронно из Kafka-события |
| Recipient → Sender | `senderId: UUID` | Нет | Sender — внешний сервис |
| IdempotencyRecord → Transfer | `transferId: UUID` | **Да** | Единственный FK — оба в одной таблице |

Единственный настоящий FK constraint — `idempotency_keys.transfer_id REFERENCES transfers(id)`, потому что это жёсткая связь внутри одного агрегата.

---

## Почему выбраны UUID-ссылки: 6 аргументов

### 1. Микросервисная граница — FK невозможен физически

`senderId` ссылается на пользователя, который живёт в отдельном User Service с собственной базой данных. **Foreign key может существовать только в пределах одной БД.** Нельзя создать FK из `transfers.sender_id` в таблицу, которая находится в другом PostgreSQL-инстансе.

То же самое с `quoteId` (pricing-service), `paymentId` (payment-service), `payoutId` (payout-service).

### 2. Нет N+1 проблемы

С JPA `@ManyToOne` при загрузке списка трансферов Hibernate автоматически (или лениво) подтягивает связанные сущности. Классическая ловушка:

```kotlin
// JPA-связь: загружаем 50 трансферов → 50 отдельных SELECT для recipient
val transfers = transferRepository.findAll(pageable)  // 1 запрос
transfers.forEach { it.recipient.firstName }           // +50 запросов = N+1
```

С UUID-ссылками мы **контролируем** момент и способ загрузки:

```kotlin
// TransferService.listTransfers() — наш подход
val page = transferRepository.findBySenderIdFirstPage(senderId, limit)

// Собираем уникальные ID, один batch-запрос
val recipientIds = page.map { it.recipientId }.distinct()
val recipientMap = recipientRepository.findAllById(recipientIds).associateBy { it.id }

// Склеиваем в памяти — ровно 2 SQL-запроса вместо N+1
val results = page.map { TransferWithRecipient(it, recipientMap[it.recipientId]) }
```

**2 запроса вместо 51.** И это явно видно в коде, а не спрятано за магией Hibernate.

### 3. Нет каскадных операций — предсказуемое поведение

С JPA-связями легко случайно получить:

```kotlin
// JPA каскад — удаляем sender, JPA удаляет всех его recipient'ов и трансферы
@OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true)
val transfers: List<Transfer> = mutableListOf()
```

В финтех это **катастрофа**: удалил пользователя — потерял историю транзакций, которую регулятор требует хранить 5-7 лет. С UUID-ссылками каскад невозможен конструктивно — каждая сущность управляется отдельно.

### 4. Saga-паттерн требует асинхронных ссылок

`paymentId` и `payoutId` в Transfer заполняются **не при создании**, а позже — когда приходит Kafka-событие от payment-service / payout-service:

```kotlin
// PaymentEventConsumer.kt — paymentId приходит через Kafka, спустя секунды/минуты
val transfer = transferRepository.findTransferById(transferId)
transfer.paymentId = UUID.fromString(event.paymentId)
```

JPA `@ManyToOne` требует, чтобы связанная сущность **существовала в той же БД** в момент сохранения. Payment живёт в другом сервисе — JPA-связь здесь невозможна в принципе.

### 5. Тестируемость и простота

С UUID-ссылками для создания Transfer в тесте нужен только UUID:

```kotlin
val transfer = Transfer(
    senderId = UUID.randomUUID(),      // не нужно создавать Sender-entity
    recipientId = UUID.randomUUID(),   // не нужно создавать Recipient и сохранять в БД
    quoteId = UUID.randomUUID(),       // не нужно поднимать pricing-service
    // ...
)
```

С JPA-связями пришлось бы сначала создать и сохранить все связанные сущности, настроить каскады, следить за порядком persist. Тесты становятся хрупкими и медленными.

### 6. Независимый деплой сервисов

Если Recipient станет отдельным микросервисом (Recipient Service), код Transfer **не изменится** — `recipientId: UUID` останется как есть. С JPA `@ManyToOne` пришлось бы переписывать сущность, менять маппинги, миграции.

---

## Как валидируется целостность без FK

Раз нет FK constraints и JPA-связей, кто проверяет, что `recipientId` реально существует?

### Валидация на уровне сервиса (TransferService.kt)

```kotlin
// 1. Проверяем, что recipient существует
val recipient = recipientRepository.findRecipientById(command.recipientId)
    ?: throw RecipientNotFoundException(command.recipientId)

// 2. Проверяем, что recipient принадлежит этому sender'у
if (recipient.senderId != command.senderId) {
    throw RecipientNotFoundException(command.recipientId)  // не раскрываем чужие данные
}
```

Обрати внимание на строку 2: при несовпадении владельца кидаем **тот же** `RecipientNotFoundException`, а не `AccessDeniedException`. Это **security best practice** — не раскрываем злоумышленнику факт существования чужого recipient'а (IDOR protection).

### Валидация quote через gRPC (PricingClient.kt)

```kotlin
// Валидируем quoteId через синхронный gRPC-вызов в pricing-service
val quoteData = pricingClient.validateQuote(command.quoteId.toString())
// Если quote протух или не найден — QuoteExpiredException
```

### Валидация saga-ссылок через Kafka ordering

`paymentId` / `payoutId` не валидируются отдельным запросом — ordering гарантируется тем, что **Kafka key = transferId**. Все события одного трансфера попадают в одну партицию и обрабатываются последовательно.

---

## Сравнительная таблица

| Критерий | UUID-ссылки (наш подход) | JPA @ManyToOne/@OneToMany |
|---|---|---|
| Кросс-сервисные связи | Работает | Невозможно |
| N+1 проблема | Контролируем явно (batch) | Скрытая, требует тюнинга |
| Каскадные удаления | Невозможны (безопасно) | По умолчанию или случайно |
| Lazy loading proxy | Нет (нет LazyInitException) | Да, частый источник багов |
| Тестирование | Простое (только UUID) | Нужен граф сущностей |
| Миграция в другой сервис | Код не меняется | Рефакторинг entity + маппинг |
| Целостность данных | Валидация в сервисном слое | FK constraint в БД |
| Производительность JOIN | Контролируем (batch / DTO) | Hibernate решает сам |

---

## Когда JPA-связи всё-таки уместны

UUID-ссылки — не серебряная пуля. JPA-связи (`@ManyToOne`, `@OneToMany`) хороши, когда:

1. **Монолит** — все таблицы в одной БД, FK constraints полезны
2. **Тесно связанный агрегат** — например, `Order ↔ OrderItem`, где items не имеют смысла без order и всегда загружаются вместе
3. **CRUD-приложение** — Spring Data REST, автоматическая сериализация графа сущностей
4. **Маленький проект** — overhead от UUID-подхода (ручные batch-запросы) не оправдан

В нашем случае transfer-service — часть **распределённой системы** с saga-оркестрацией, Kafka-событиями и gRPC-вызовами между сервисами. UUID-ссылки здесь — архитектурно правильный выбор.

---

## Как объяснить на собеседовании (короткая версия)

> «Мы используем UUID-ссылки вместо JPA @ManyToOne по трём причинам:
>
> **Первое** — это микросервисная архитектура: sender, quote, payment живут в отдельных сервисах со своими БД, FK constraint физически невозможен.
>
> **Второе** — предсказуемость: нет скрытых lazy-loading прокси, нет N+1, нет каскадных удалений. Мы явно контролируем все SQL-запросы через batch-загрузку в сервисном слое.
>
> **Третье** — saga-паттерн: paymentId и payoutId заполняются асинхронно через Kafka-события, спустя секунды после создания трансфера. JPA-связь требует, чтобы связанная сущность существовала в момент persist.
>
> Валидация целостности — на уровне сервиса: проверяем существование recipient'а, его принадлежность sender'у, и валидность quote через gRPC-вызов в pricing-service.»
