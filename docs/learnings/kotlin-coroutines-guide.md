# Kotlin Coroutines — Полное руководство с нуля

## Оглавление

1. [Проблема: зачем вообще нужны корутины](#1-проблема)
2. [Что такое корутина](#2-что-такое-корутина)
3. [suspend — ключевое слово](#3-suspend)
4. [Как корутина приостанавливается изнутри](#4-как-приостанавливается)
5. [Builders: launch, async, runBlocking](#5-builders)
6. [await — ожидание результата](#6-await)
7. [Dispatchers — где выполняется код](#7-dispatchers)
8. [Structured Concurrency — дисциплина](#8-structured-concurrency)
9. [CoroutineScope и CoroutineContext](#9-coroutinescope-и-coroutinecontext)
10. [Отмена (Cancellation)](#10-отмена)
10.1. [Flow — реактивные потоки](#101-flow)
11. [Реальные сценарии для собеседования](#11-реальные-сценарии)
12. [Как это работает в нашем проекте](#12-в-нашем-проекте)
13. [CompletableFuture.await() — мост Java ↔ корутины](#13-completablefuture-await)
14. [runBlocking в тестах — ловушка](#14-runblocking-в-тестах)
15. [Частые ошибки](#15-частые-ошибки)
16. [Шпаргалка](#16-шпаргалка)

---

## 1. Проблема

### Синхронный код блокирует поток

```kotlin
fun getQuote(quoteId: String): Quote {
    val json = redis.get(quoteId)    // Поток ЗАБЛОКИРОВАН на ~1ms, ждёт ответа от Redis
    return parseJson(json)
}
```

Пока `redis.get()` ждёт ответа от Redis, поток **ничего не делает** — просто стоит. Если у тебя 200 параллельных запросов, тебе нужно 200 потоков. Каждый поток — ~1 МБ стека. 200 потоков = 200 МБ RAM на ожидание.

### Асинхронный код (Java callbacks) — ад

```java
// Java CompletableFuture — без корутин
redis.getAsync(quoteId).thenApply(json -> {
    Quote quote = parseJson(json);
    return cacheService.validate(quote).thenApply(result -> {
        if (result.isValid()) {
            return notificationService.send(quote).thenApply(sent -> {
                // callback внутри callback внутри callback...
                // "callback hell"
            });
        }
    });
});
```

Работает, не блокирует поток. Но код нечитаемый.

### Корутины — читаемый асинхронный код

```kotlin
suspend fun getQuote(quoteId: String): Quote {
    val json = redis.getAsync(quoteId).await()    // НЕ блокирует поток, но выглядит синхронно
    val quote = parseJson(json)
    val result = cacheService.validate(quote)      // тоже suspend — тоже не блокирует
    if (result.isValid) {
        notificationService.send(quote)            // и это тоже
    }
    return quote
}
```

**Выглядит как обычный синхронный код**, но под капотом поток освобождается при каждом `await`/suspend-вызове.

---

## 2. Что такое корутина

Корутина — это **легковесный поток**, который может приостановиться и возобновиться.

### Поток vs Корутина

```
Поток (Thread):
┌────────────────────────────────────────┐
│  Начало → redis.get() [БЛОК 1ms] → Конец  │
│  Весь поток занят, даже когда ждёт     │
│  ~1 МБ памяти на стек                 │
│  Управляется ОС                        │
└────────────────────────────────────────┘

Корутина:
┌──────────┐          ┌──────────┐
│  Начало  │──pause──▶│redis ждёт│──resume──▶│  Конец  │
│  Код до  │          │Поток СВОБОДЕН│        │  Код после │
└──────────┘          └──────────┘           └──────────┘
  ~несколько байт
  Управляется Kotlin runtime
```

Когда корутина "приостанавливается" (suspend):
1. Текущее состояние сохраняется в объект в куче (heap)
2. Поток **освобождается** для другой работы
3. Когда результат готов — корутина **возобновляется** (возможно, на другом потоке)

### Аналогия

Представь ресторан:
- **Потоки** = официанты
- **Корутины** = заказы

**Без корутин:** один официант стоит у кухни и ждёт, пока приготовят блюдо. 200 заказов = нужно 200 официантов.

**С корутинами:** официант принял заказ, передал кухне, пошёл обслуживать другой столик. Когда блюдо готово — любой свободный официант несёт его. 200 заказов = достаточно 4 официанта.

### 100 000 корутин — классический пример из документации

```kotlin
fun main() = runBlocking {
    // Запустим 100 000 корутин — каждая ждёт 5 секунд и печатает точку
    repeat(100_000) {
        launch {
            delay(5000L)
            print(".")
        }
    }
}
// Работает! ~несколько МБ памяти
// Попробуй то же с Thread — OutOfMemoryError после ~2000-5000 потоков
```

Это возможно, потому что корутина при приостановке занимает лишь **объект continuation на куче** (~десятки-сотни байт), а не целый стек потока (~1 МБ).

### Concurrency vs Parallelism

Это разные вещи, и корутины дают **concurrency**, но не обязательно **parallelism**:

```
Concurrency (конкурентность):
  Несколько задач ПРОГРЕССИРУЮТ одновременно,
  но не обязательно выполняются В ТОТ ЖЕ МОМЕНТ.
  Аналогия: один повар жонглирует тремя кастрюлями,
  переключаясь между ними.

Parallelism (параллелизм):
  Несколько задач выполняются БУКВАЛЬНО одновременно
  на разных CPU-ядрах.
  Аналогия: три повара, каждый варит свою кастрюлю.
```

```kotlin
// Concurrency без parallelism — одно ядро, но задачи чередуются
launch(Dispatchers.Default) { task1() }  // пока task1 ждёт IO...
launch(Dispatchers.Default) { task2() }  // ...task2 работает на том же потоке

// Concurrency с parallelism — несколько ядер
launch(Dispatchers.Default) { cpuTask1() }  // ядро 1
launch(Dispatchers.Default) { cpuTask2() }  // ядро 2 (реально параллельно)
```

Корутины оптимизированы для **IO-bound** задач (где много ожидания). Для **CPU-bound** задач корутины тоже работают, но реальный параллелизм ограничен числом потоков в `Dispatchers.Default` (= числу ядер CPU).

---

## 3. suspend — ключевое слово

`suspend` — это пометка функции: "эта функция может приостановить выполнение".

```kotlin
// Обычная функция — НЕ может приостановиться
fun add(a: Int, b: Int): Int = a + b

// suspend-функция — МОЖЕТ приостановиться
suspend fun getFromRedis(key: String): String? {
    return redisCommands.get(key).await()   // тут приостановка
}
```

### Правило вызова

suspend-функцию можно вызвать **только из**:
1. Другой suspend-функции
2. Корутинного билдера (launch, async, runBlocking)

```kotlin
// ОК — suspend вызывает suspend
suspend fun validateQuote(quoteId: String): Boolean {
    val quote = getFromRedis(quoteId)   // ок — мы тоже suspend
    return quote != null
}

// ОШИБКА — обычная функция вызывает suspend
fun validateQuote(quoteId: String): Boolean {
    val quote = getFromRedis(quoteId)   // Ошибка компиляции!
    return quote != null
}

// ОК — через корутинный билдер
fun main() {
    runBlocking {
        val quote = getFromRedis("123")   // ок — мы внутри корутины
    }
}
```

### Что suspend НЕ означает

- **НЕ** означает "выполняется в фоновом потоке"
- **НЕ** означает "выполняется асинхронно"
- **НЕ** означает "обязательно приостановится"

suspend означает только: "у этой функции ЕСТЬ ВОЗМОЖНОСТЬ приостановиться". Она может и не приостановиться — тогда выполнится как обычная функция.

```kotlin
suspend fun maybeNotSuspend(): Int {
    return 42   // никакой приостановки, просто возвращает число
}
```

### Что компилятор делает с suspend (вопрос на собеседовании)

Когда ты пишешь `suspend fun`, компилятор **тайно добавляет параметр** — `Continuation`. Это "обратный вызов", через который корутина возобновляется:

```kotlin
// Ты пишешь:
suspend fun getUser(id: String): User

// Компилятор превращает в:
fun getUser(id: String, continuation: Continuation<User>): Any?
//                       ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
//                       скрытый параметр — "куда вернуть результат"
```

`Continuation` — это по сути callback с двумя полями:
- `context` — настройки корутины (dispatcher, job)
- `resumeWith(result)` — "вот результат, продолжай работу"

Возвращает `Any?`, потому что два варианта:
- Вернул **результат** → функция не приостанавливалась, всё как обычно
- Вернул **COROUTINE_SUSPENDED** → "я приостановился, результат будет позже через continuation.resumeWith()"

**Вот почему suspend нельзя вызвать из обычной функции** — у обычной функции нет `Continuation`, который надо передать!

---

## 4. Как корутина приостанавливается изнутри

Это самое сложное для понимания. Разберём пошагово.

### Что делает компилятор

Когда ты пишешь:

```kotlin
suspend fun fetchQuote(quoteId: String): Quote {
    println("Шаг 1: запрашиваю Redis")
    val json = redis.getAsync(quoteId).await()    // точка приостановки
    println("Шаг 2: получил ответ")
    val quote = parseJson(json)
    return quote
}
```

Компилятор превращает это в **конечный автомат (state machine)**:

```
Состояние 0: выполнить "Шаг 1", начать redis.getAsync(), ПРИОСТАНОВИТЬСЯ
             ↓ (когда Redis ответит)
Состояние 1: выполнить "Шаг 2", parseJson, вернуть результат
```

### Пошагово — что происходит в runtime

```
Поток-1:
  │
  ├─ 1. Входит в fetchQuote()
  ├─ 2. println("Шаг 1")
  ├─ 3. redis.getAsync(quoteId) → отправил запрос Redis, получил CompletableFuture
  ├─ 4. .await() → "результата ещё нет, ПРИОСТАНАВЛИВАЮСЬ"
  ├─ 5. Состояние корутины сохранено в объект на куче (heap):
  │     { state=1, quoteId="123", continuation=... }
  ├─ 6. Поток-1 СВОБОДЕН → идёт выполнять другие корутины
  │
  │     ... проходит 0.5ms, Redis ответил ...
  │
Поток-2 (или тот же Поток-1):
  │
  ├─ 7. CompletableFuture завершился → вызывает continuation.resume(json)
  ├─ 8. Корутина возобновляется с state=1
  ├─ 9. println("Шаг 2")
  ├─ 10. parseJson(json)
  └─ 11. return quote
```

### Ключевой момент: Continuation

`Continuation` — это объект, который хранит:
- Где мы остановились (номер состояния)
- Все локальные переменные на момент остановки
- Куда вернуть результат

Это как закладка в книге — ты знаешь, на какой странице остановился и что было на предыдущих.

```kotlin
// Упрощённо — что генерирует компилятор
class FetchQuote_StateMachine : Continuation<Any?> {
    var state = 0
    var quoteId: String? = null
    var json: String? = null

    fun invokeSuspend(result: Any?): Any {
        when (state) {
            0 -> {
                println("Шаг 1")
                state = 1
                val future = redis.getAsync(quoteId!!)
                return future.awaitSuspend(this)   // передаём СЕБЯ как callback
                // ↑ возвращает COROUTINE_SUSPENDED — сигнал "я приостановился"
            }
            1 -> {
                json = result as String   // результат от Redis
                println("Шаг 2")
                return parseJson(json!!)
            }
        }
    }
}
```

**Вывод:** `suspend` — это не магия. Компилятор превращает последовательный код в state machine с callback'ами. Но ты пишешь его как обычный код.

---

## 5. Builders: launch, async, runBlocking

Корутинный билдер — это функция, которая **создаёт и запускает корутину**.

### launch — запустил и забыл (fire and forget)

```kotlin
// Внутри любого CoroutineScope (launch, runBlocking, coroutineScope, etc.)

// Не возвращает результат, только Job (хэндл для управления)
val job: Job = launch {
    println("Я выполняюсь в корутине")
    delay(1000)
    println("Прошла 1 секунда")
}
// Код тут продолжается СРАЗУ, не ждёт launch
job.join()  // если нужно дождаться — явный вызов join()
```

Используется для **побочных эффектов** — отправить уведомление, записать лог, обновить метрику.

### async — запустил и жду результат

```kotlin
// Внутри любого CoroutineScope

// Возвращает Deferred<T> — обещание результата
val deferred: Deferred<String> = async {
    delay(500)
    "result"
}
// Код тут продолжается СРАЗУ

// Когда нужен результат:
val result: String = deferred.await()   // ← тут приостанавливаемся, пока не готово
```

Используется для **параллельных вычислений**:

```kotlin
suspend fun loadDashboard(): Dashboard = coroutineScope {
    // Два запроса ПАРАЛЛЕЛЬНО
    val quotesDeferred = async { getQuotes() }       // запустился
    val transfersDeferred = async { getTransfers() }  // запустился СРАЗУ, не ждёт первый

    // Ждём оба результата
    val quotes = quotesDeferred.await()
    val transfers = transfersDeferred.await()

    Dashboard(quotes, transfers)
}
// Общее время ≈ max(timeQuotes, timeTransfers), а не сумма!
```

### runBlocking — мост из обычного мира в suspend-мир

```kotlin
// БЛОКИРУЕТ текущий поток до завершения корутины
fun main() {
    runBlocking {   // <- текущий поток заблокирован
        val quote = getFromRedis("123")   // suspend-вызов
        println(quote)
    }
    // Код тут выполнится только когда корутина внутри runBlocking завершится
}
```

**Когда использовать:**
- `main()` функция
- Тесты (`@Test fun test() = runBlocking { ... }`)
- Мост между Java-библиотекой и suspend-кодом

**Когда НЕ использовать:**
- Внутри другой корутины (зачем блокировать, если уже в suspend?)
- В обработчиках HTTP-запросов (заблокируешь поток сервера)

### Lazy start (CoroutineStart.LAZY)

По умолчанию `launch` и `async` запускают корутину **сразу**. С `CoroutineStart.LAZY` корутина создаётся, но не стартует до явного вызова `start()` или `await()`:

```kotlin
val deferred = async(start = CoroutineStart.LAZY) {
    println("Computing...")
    heavyCalculation()
}

// ... корутина ещё НЕ запущена ...
println("Before start")

val result = deferred.await()  // ← вот теперь стартовала и мы ждём результат
// Или: deferred.start() — стартовать без ожидания
```

**Когда полезно:** когда хочешь подготовить корутину заранее, но запустить позже по условию.

### coroutineScope — "менеджер проекта"

`async` и `launch` не могут существовать в воздухе — им нужен **scope** (владелец). Кто-то должен:
- раздать задачи
- дождаться ВСЕХ результатов
- если одна задача провалилась — отменить остальные

Этот "кто-то" — `coroutineScope { }`.

```kotlin
// БЕЗ scope — async некуда прикрепиться, ошибка компиляции
suspend fun processOrder(orderId: String) {
    async { getItems(orderId) }    // ОШИБКА: нет scope!
}

// С scope — есть "менеджер", который контролирует задачи
suspend fun processOrder(orderId: String) = coroutineScope {
    val items = async { getItems(orderId) }    // менеджер выдал задачу
    val payment = async { getPayment(orderId) } // менеджер выдал задачу
    assemble(items.await(), payment.await())
}
// менеджер (coroutineScope) уйдёт ТОЛЬКО когда обе задачи готовы
// если getItems() упадёт → менеджер отменит getPayment() и бросит ошибку наверх
```

### Сравнение

```
launch {          }  →  Job        →  "сделай это, мне результат не нужен"
async {           }  →  Deferred<T> →  "сделай это, результат дашь потом"
runBlocking {     }  →  T          →  "сделай это ПРЯМО СЕЙЧАС, я подожду"
coroutineScope {  }  →  T          →  "создай scope, дождись всех детей"
```

---

## 6. await — ожидание результата

`await()` встречается в двух контекстах — важно не путать.

### 6.1 Deferred.await() — ждём результат async

```kotlin
val deferred: Deferred<Int> = async { computeExpensiveThing() }

// Приостанавливает корутину, пока async не завершится
val result: Int = deferred.await()
```

Это Kotlin-native. Работает только с `Deferred` от `async`.

### 6.2 CompletableFuture.await() — мост из Java

```kotlin
import kotlinx.coroutines.future.await   // ← нужен этот импорт!

val future: CompletableFuture<String> = redis.getAsync(key)

// Приостанавливает корутину, пока Future не завершится
val result: String = future.await()
```

Это extension-функция из `kotlinx-coroutines-jdk8`. Она превращает Java `CompletableFuture` в suspend-вызов.

### Что await делает внутри

```
future.await()
  │
  ├── Future уже завершён?
  │     ├── ДА → вернуть результат сразу (без приостановки)
  │     └── НЕТ → приостановить корутину
  │               ├── Зарегистрировать callback на Future: future.whenComplete { result ->
  │               │     continuation.resume(result)   // возобновить корутину
  │               │   }
  │               └── Освободить поток
  │
  │     ... Future завершился ...
  │
  ├── callback вызван → continuation.resume(result)
  └── корутина возобновлена → return result
```

### await() vs .get() — КРИТИЧЕСКАЯ разница

```kotlin
// ПЛОХО — блокирует поток!
val result = future.get()         // Thread.sleep() под капотом
                                   // Поток стоит и ничего не делает

// ХОРОШО — освобождает поток!
val result = future.await()       // Корутина приостановлена
                                   // Поток свободен для других корутин
```

**Никогда не вызывай `.get()` внутри корутины.** Это убивает весь смысл.

---

## 7. Dispatchers — на каком потоке работает корутина

Корутина сама по себе не знает, на каком потоке ей работать. Dispatcher — это **маршрутизатор**, который говорит: "ты работаешь на этом пуле потоков".

### Dispatchers.Default

```kotlin
launch(Dispatchers.Default) { /* ... */ }
```
- Пул потоков = количество CPU ядер
- Для **CPU-intensive** работы: парсинг JSON, вычисления, сортировка
- Не для IO!

### Dispatchers.IO

```kotlin
launch(Dispatchers.IO) { /* ... */ }
```
- Пул до 64 потоков (или больше)
- Для **блокирующего IO**: JDBC, файловая система, блокирующие HTTP-клиенты
- Если вызываешь Java-библиотеку, которая блокирует поток — оберни в `withContext(Dispatchers.IO)`

### Dispatchers.Main

- Только для Android/UI — главный поток
- В серверных приложениях не используется

### Dispatchers.Unconfined

- Запускается в текущем потоке, после приостановки может оказаться где угодно
- Полезен в тестах и специфичных случаях, когда не важно на каком потоке выполняться
- В продакшн-коде почти никогда не нужен

### Когда какой Dispatcher

```kotlin
suspend fun processQuote() {
    // CPU-работа — Default
    val parsed = withContext(Dispatchers.Default) {
        parseHugeJson(rawData)
    }

    // Блокирующий JDBC — IO
    val dbResult = withContext(Dispatchers.IO) {
        jdbcTemplate.queryForObject("SELECT ...", Quote::class.java)
    }

    // Неблокирующий Redis (Lettuce async) — не нужен особый Dispatcher!
    val cached = redis.getAsync(key).await()
    // ↑ await() не блокирует поток, поэтому любой Dispatcher подходит
}
```

### Dispatchers.IO и Default — общий пул потоков

Важный факт из документации: `Dispatchers.IO` и `Dispatchers.Default` **разделяют потоки**. Переключение между ними через `withContext` обычно **не приводит к смене потока** — это оптимизация runtime:

```kotlin
suspend fun process() {
    withContext(Dispatchers.Default) {
        println("Default: ${Thread.currentThread().name}")
        // DefaultDispatcher-worker-1

        withContext(Dispatchers.IO) {
            println("IO: ${Thread.currentThread().name}")
            // DefaultDispatcher-worker-1  ← тот же поток! Смены не было
        }
    }
}
```

Это работает, потому что `Dispatchers.IO` использует **тот же пул**, но с увеличенным лимитом (до 64 потоков). `Dispatchers.Default` ограничен числом ядер.

### limitedParallelism() — ограничение параллелизма

Начиная с `kotlinx.coroutines 1.6`, можно создавать **view на dispatcher** с ограниченной параллельностью:

```kotlin
// Ограничить IO до 4 одновременных запросов к конкретной БД
val dbDispatcher = Dispatchers.IO.limitedParallelism(4)

// Ограничить Default до 2 потоков для тяжёлых вычислений
val heavyComputeDispatcher = Dispatchers.Default.limitedParallelism(2)

suspend fun queryDatabase() = withContext(dbDispatcher) {
    // Максимум 4 корутины одновременно обращаются к БД
    jdbc.query("SELECT ...")
}
```

**Когда полезно:**
- Ограничить нагрузку на конкретный ресурс (пул БД, внешний API)
- Эквивалент `Executors.newFixedThreadPool(N)`, но для корутин

### newSingleThreadContext — один выделенный поток

```kotlin
// Создаёт dispatcher с ОДНИМ выделенным потоком
val singleThread = newSingleThreadContext("MyThread")

launch(singleThread) {
    // Всегда выполняется на потоке "MyThread"
    // Гарантия: никто другой на этом потоке не работает
}

// ВАЖНО: нужно закрыть, когда больше не нужен!
singleThread.close()
```

**Когда полезно:** thread-confined мутабельное состояние (вместо мьютексов).

### Thread confinement — защита данных без блокировок

Идея простая: если к переменной обращается только ОДИН поток — не нужны ни мьютексы, ни synchronized. Просто гарантируй, что весь доступ идёт через один поток:

```kotlin
class Counter {
    private val counterContext = newSingleThreadContext("CounterThread")
    private var count = 0  // мутабельное состояние — НЕ thread-safe

    suspend fun increment() = withContext(counterContext) {
        count++  // безопасно! Всегда выполняется на одном потоке
    }

    suspend fun get(): Int = withContext(counterContext) {
        count
    }
}
```

Альтернатива — `Mutex` из корутин (не блокирует поток, в отличие от `synchronized`):

```kotlin
class Counter {
    private val mutex = Mutex()
    private var count = 0

    suspend fun increment() {
        mutex.withLock {
            count++  // защищено мьютексом
        }
    }
}
```

### withContext — переключение Dispatcher

```kotlin
suspend fun heavyComputation(): Result {
    return withContext(Dispatchers.Default) {
        // Этот блок выполнится на Default-пуле
        // Корутина "переедет" на другой поток
        expensiveCalculation()
    }
    // Тут корутина вернётся на исходный Dispatcher
}
```

`withContext` — это **suspend-функция**, которая:
1. Переключает корутину на указанный Dispatcher
2. Выполняет блок
3. Переключает обратно на исходный Dispatcher
4. **НЕ** создаёт новую корутину (в отличие от `launch`/`async`)

```kotlin
// withContext vs async+await — разница
suspend fun example() {
    // withContext — последовательно, не создаёт новую корутину
    val a = withContext(Dispatchers.IO) { fetchA() }  // ← ждём
    val b = withContext(Dispatchers.IO) { fetchB() }  // ← потом ждём
    // Общее время = timeA + timeB

    // async+await — параллельно, создаёт новые корутины
    coroutineScope {
        val a = async(Dispatchers.IO) { fetchA() }  // ← стартовал
        val b = async(Dispatchers.IO) { fetchB() }  // ← стартовал одновременно
        use(a.await(), b.await())
    }
    // Общее время = max(timeA, timeB)
}
```

---

## 8. Structured Concurrency — дисциплина

### Зачем это нужно — проблема "потерянных корутин"

Представь: ты запускаешь корутину и забываешь о ней. Кто её остановит? Кто узнает, если она упала?

```kotlin
// ПЛОХО — "запустил и забыл" (unstructured)
fun handleRequest() {
    GlobalScope.launch { sendNotification() }  // кто за это отвечает?
    GlobalScope.launch { updateMetrics() }      // кто отменит, если запрос упал?
    // Функция вернулась, а корутины живут сами по себе
    // Как дети без присмотра — могут потеряться, упасть, никто не заметит
}
```

Это как нанять работника и уйти из офиса. Работник может работать, может уволиться, может сломать сервер — ты не узнаешь.

### Structured Concurrency — "родитель отвечает за детей"

**Structured concurrency** — это правило: **ты не можешь запустить корутину "в воздух"**. Каждая корутина привязана к scope (родителю), и родитель:
- **ждёт** всех детей
- **отменяет** всех детей при ошибке
- **не завершится** раньше детей

```kotlin
// ХОРОШО — structured: всё под контролем
suspend fun handleRequest() = coroutineScope {
    launch { sendNotification() }   // ребёнок 1 — привязан к scope
    launch { updateMetrics() }       // ребёнок 2 — привязан к scope
    // coroutineScope НЕ вернётся, пока оба ребёнка не закончат
    // Если один упал → второй отменится → ошибка пробросится наверх
}
```

**Аналогия:** Это как школьная экскурсия. Учитель (scope) не уедет, пока все дети (корутины) не сядут в автобус. Если один ребёнок заблудился — учитель не скажет "ну ладно, поехали без него". Учитель будет ждать или отменит экскурсию.

### Три правила (запомни для собеседования)

1. **Каждая корутина привязана к scope** — нет бесхозных корутин
2. **Scope ждёт ВСЕХ детей** — пока все не закончат, scope не завершится
3. **Ошибка одного ребёнка = ошибка всех** — fail-fast, никто не продолжает работать

### Реальный пример: параллельная загрузка данных для dashboard

```kotlin
// Нужно загрузить данные из 3 источников параллельно
suspend fun loadDashboard(userId: String): Dashboard = coroutineScope {
    // Все три запроса стартуют ОДНОВРЕМЕННО
    val profileDeferred = async { userService.getProfile(userId) }           // ~50ms
    val transactionsDeferred = async { txService.getRecent(userId) }         // ~200ms
    val notificationsDeferred = async { notificationService.getUnread(userId) } // ~100ms

    // Ждём все результаты (общее время ≈ 200ms, а не 350ms)
    Dashboard(
        profile = profileDeferred.await(),
        transactions = transactionsDeferred.await(),
        notifications = notificationsDeferred.await()
    )
}
```

### Пошаговый trace: что происходит при ошибке

Допустим, `txService.getRecent()` бросает `TimeoutException` на 150ms:

```
t=0ms:   coroutineScope запускает 3 async-корутины
t=0ms:   async{getProfile}       — STARTED
t=0ms:   async{getRecent}        — STARTED
t=0ms:   async{getUnread}        — STARTED
t=50ms:  async{getProfile}       — COMPLETED (результат "John")
t=100ms: async{getUnread}        — COMPLETED (результат [3 notifications])
t=150ms: async{getRecent}        — FAILED с TimeoutException ✗

Что происходит дальше:
t=150ms: coroutineScope получает ошибку от дочерней корутины
t=150ms: coroutineScope отменяет ВСЕХ оставшихся детей
         (getProfile и getUnread уже завершены — ничего не произойдёт)
t=150ms: coroutineScope бросает TimeoutException наверх
t=150ms: loadDashboard() пробрасывает TimeoutException вызывающему
```

Если бы ошибка произошла на 30ms (до завершения getProfile и getUnread):

```
t=0ms:   Все 3 async стартовали
t=30ms:  async{getRecent}  — FAILED с TimeoutException ✗
t=30ms:  coroutineScope отменяет оставшихся детей:
         async{getProfile}  — CANCELLED ✗ (была в процессе)
         async{getUnread}   — CANCELLED ✗ (была в процессе)
t=30ms:  coroutineScope бросает TimeoutException
```

**Ключевое:** при structured concurrency ресурсы не утекают. Если одна задача упала — все остальные корректно отменяются, и ошибка пробрасывается вызывающему.

### Визуализация дерева корутин

```
processTransfer (parent scope)
├── async { getQuote() }      (child 1)
├── async { getBalance() }    (child 2)
└── [scope ждёт всех детей]

Успех:
├── async { getQuote() }      COMPLETED ✓  (100ms)
├── async { getBalance() }    COMPLETED ✓  (80ms)
└── processTransfer возвращает результат (через 100ms)

Ошибка:
├── async { getQuote() }      FAILED ✗     (50ms — бросил IOException)
├── async { getBalance() }    CANCELLED ✗  (была на 50ms из 80ms)
└── processTransfer бросает IOException (через 50ms)
```

### Собеседование: "расскажите про structured concurrency"

> Structured concurrency — это когда каждая корутина имеет владельца (scope), и владелец отвечает за её жизнь. Три правила: scope ждёт всех детей, ошибка одного ребёнка отменяет остальных, нет "бесхозных" корутин.
>
> Простая аналогия — школьная экскурсия: учитель (scope) не уедет без всех детей (корутин), и если экскурсия отменена — все дети возвращаются.
>
> `GlobalScope.launch` нарушает это — это как отпустить ребёнка одного. Он может потеряться, упасть — ты не узнаешь.

---

## 9. CoroutineScope и CoroutineContext

### CoroutineContext — "настройки" корутины

Каждая корутина при создании получает набор настроек — **контекст**. Контекст отвечает на вопросы:
- **На каком потоке работать?** → Dispatcher
- **Кто мой родитель?** → Job
- **Как меня зовут?** (для логов) → CoroutineName
- **Что делать при необработанной ошибке?** → CoroutineExceptionHandler

```kotlin
// Контекст собирается из элементов через оператор "+"
val context =
    Job()                           // "кто мой родитель"
    + Dispatchers.Default           // "работать на пуле Default"
    + CoroutineName("my-coroutine") // "имя для логов"
    + CoroutineExceptionHandler { _, e -> log.error("Uncaught", e) }
```

Если складываешь два контекста с одинаковыми элементами — правый заменяет левый (как `Map.put`):

```kotlin
val ctx1 = Dispatchers.Default + CoroutineName("a")
val ctx2 = ctx1 + Dispatchers.IO  // IO заменил Default
// ctx2 = Dispatchers.IO + CoroutineName("a")
```

### Дети наследуют контекст от родителя

Когда ты запускаешь `launch` внутри scope — дочерняя корутина получает контекст родителя. Но может переопределить отдельные элементы:

```kotlin
val scope = CoroutineScope(Dispatchers.Default + CoroutineName("parent"))

scope.launch(CoroutineName("child")) {
    // Dispatcher = Default (унаследовал от parent)
    // Name = "child" (переопределил)
    // Job = НОВЫЙ (дочерний от parent's Job — вот откуда structured concurrency!)
}
```

**Простое правило:** дочерняя корутина = контекст родителя + то, что ты передал + новый Job.

### CoroutineScope — что это на самом деле

Scope — это **просто обёртка над контекстом**. Вот весь интерфейс:

```kotlin
interface CoroutineScope {
    val coroutineContext: CoroutineContext
}
```

Когда ты пишешь `scope.launch { }`, `launch` берёт `scope.coroutineContext` как родительский контекст для новой корутины. Вот и всё.

**Зачем отдельный интерфейс?** Чтобы ты мог привязать контекст к объекту (серверу, сервису) и контролировать lifecycle — создал scope при старте, вызвал `scope.cancel()` при shutdown.

### Scope = "граница жизни" корутин

Когда scope отменяется — **все его корутины умирают**. Это как выключить рубильник — всё, что от него питалось, останавливается.

```kotlin
class GrpcServer {
    // Scope привязан к серверу: создался вместе с сервером, умрёт вместе с ним
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        scope.launch { backgroundTask1() }  // живёт, пока сервер жив
        scope.launch { backgroundTask2() }  // тоже
    }

    fun stop() {
        scope.cancel()   // рубильник! Все корутины отменяются
    }
}
```

### Разные scope для разных ситуаций

Scope живёт столько, сколько живёт его "владелец":

```kotlin
// HTTP-сервер — scope живёт от старта до shutdown
class HttpServer : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        scope.launch { startHealthCheckLoop() }
        scope.launch { startMetricsReporter() }
    }

    override fun close() {
        scope.cancel()  // сервер остановлен → все фоновые задачи отменены
    }
}

// gRPC-сервис — scope привязан к lifecycle сервера
class TransferGrpcService(
    private val scope: CoroutineScope  // инжектируется извне
) {
    fun processTransfer(request: TransferRequest) {
        scope.launch {
            // фоновая обработка, живёт пока жив сервис
        }
    }
}

// Обработчик запроса — scope на один запрос
suspend fun handleRequest(request: Request): Response = coroutineScope {
    // scope живёт ровно столько, сколько обрабатывается запрос
    val data = async { fetchData(request.id) }
    val enriched = async { enrichData(request) }
    buildResponse(data.await(), enriched.await())
}
```

### Паттерн для бэкенда: scope как Spring Bean

В Android есть готовые scope (`viewModelScope`). На бэкенде создаём сами:

```kotlin
// Scope на весь application — Spring сам вызовет cancel() при shutdown
@Configuration
class AppConfig {
    @Bean(destroyMethod = "cancel")
    fun applicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

// Scope на конкретный сервис — умирает вместе с сервисом
class PaymentProcessor : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun close() {
        scope.cancel()  // сервис уничтожен → все его корутины тоже
    }
}
```

### Реальные сценарии

#### Фоновые задачи

```kotlin
class NotificationService(
    private val scope: CoroutineScope
) {
    // Запустить отправку в фоне, не ждать завершения
    fun sendAsync(notification: Notification) {
        scope.launch {
            try {
                emailClient.send(notification)
            } catch (e: Exception) {
                log.error("Failed to send notification", e)
                // SupervisorJob не даст ошибке убить другие задачи
            }
        }
    }
}
```

#### Периодический polling

```kotlin
class HealthChecker(
    private val scope: CoroutineScope
) {
    fun startPolling() {
        scope.launch {
            while (isActive) {  // isActive проверяет, не отменён ли scope
                try {
                    checkAllServices()
                } catch (e: CancellationException) {
                    throw e  // обязательно пробрасываем!
                } catch (e: Exception) {
                    log.warn("Health check failed", e)
                }
                delay(30_000)  // каждые 30 секунд
            }
        }
    }
}
```

#### Graceful shutdown

```kotlin
class Application {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start() {
        appScope.launch { startKafkaConsumer() }
        appScope.launch { startMetricsExporter() }
        appScope.launch { startHealthCheckServer() }
    }

    suspend fun shutdown() {
        log.info("Shutting down...")
        appScope.cancel()           // сигнализируем всем корутинам об отмене
        appScope.coroutineContext[Job]!!.children.forEach { it.join() }
        // или более идиоматично:
        // appScope.coroutineContext.job.cancelAndJoin()
        log.info("All background tasks stopped")
    }
}
```

### coroutineScope {} vs CoroutineScope() — ЧАСТЫЙ вопрос на собеседовании

Выглядят похоже, но это **совершенно разные вещи**:

```
coroutineScope { }    ← маленькая буква, suspend функция
CoroutineScope()      ← большая буква, конструктор
```

**coroutineScope { }** — временный scope на время выполнения блока. Как "совещание": ты собрал людей, раздал задачи, ждёшь пока ВСЕ закончат, и уходишь.

```kotlin
suspend fun loadData() = coroutineScope {
    // Временный scope — живёт ровно столько, сколько выполняется loadData()
    val a = async { fetchA() }
    val b = async { fetchB() }
    merge(a.await(), b.await())
}
// loadData() вернётся ТОЛЬКО когда fetchA и fetchB завершатся
// Если fetchA упал → fetchB отменится → ошибка пробросится наверх
```

**CoroutineScope()** — постоянный scope, привязанный к объекту. Как "отдел в компании": он существует независимо, пока ты его не закроешь.

```kotlin
class MyService {
    // Постоянный scope — живёт, пока живёт MyService
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun doWork() { scope.launch { ... } }
    fun shutdown() { scope.cancel() }  // надо вручную закрыть!
}
```

**Когда что:**
- В suspend-функции нужно запустить параллельные задачи → **coroutineScope { }**
- У объекта есть lifecycle и фоновые задачи → **CoroutineScope()**

```kotlin
// ТИПИЧНАЯ ОШИБКА: конструктор внутри suspend-функции
suspend fun loadData() {
    val scope = CoroutineScope(Dispatchers.Default)  // ПЛОХО!
    scope.launch { fetchA() }  // никто не дождётся, никто не отменит = утечка!
}

// ПРАВИЛЬНО: функция coroutineScope внутри suspend-функции
suspend fun loadData() = coroutineScope {
    launch { fetchA() }  // scope дождётся и отменит при ошибке
}
```

### SupervisorJob — "каждый сам за себя"

По умолчанию, если один ребёнок упал — родитель убивает ВСЕХ остальных. Это логично для параллельных задач: зачем грузить профиль, если транзакции не загрузились?

Но для сервера это катастрофа: один сломанный HTTP-запрос не должен убить весь сервер! Для этого есть **SupervisorJob**:

```kotlin
// Обычный Job: один упал → все упали
val scope = CoroutineScope(Job())
scope.launch { handleRequest1() }   // упал → request2 тоже умрёт!
scope.launch { handleRequest2() }

// SupervisorJob: один упал → остальные работают
val scope = CoroutineScope(SupervisorJob())
scope.launch { handleRequest1() }   // упал → request2 продолжит
scope.launch { handleRequest2() }
```

**Простое правило:**
- Задачи **зависят** друг от друга (dashboard = profile + transactions) → обычный Job (`coroutineScope`)
- Задачи **независимы** (HTTP-запросы, нотификации) → `SupervisorJob`

### coroutineScope vs supervisorScope

```kotlin
// coroutineScope — ошибка ребёнка = ошибка всех (fail-fast)
suspend fun loadDashboard() = coroutineScope {
    val profile = async { getProfile() }        // упал → transactions отменится
    val transactions = async { getTransactions() }
    Dashboard(profile.await(), transactions.await())
}

// supervisorScope — каждый ребёнок сам за себя
suspend fun sendNotifications() = supervisorScope {
    launch { sendEmail() }     // упал → SMS всё равно отправится
    launch { sendSMS() }
    launch { sendPush() }
}
```

### Собеседование: "когда создавать свой scope"

> Свой `CoroutineScope()` нужен, когда у объекта есть lifecycle — он живёт, работает, потом умирает. Scope рождается вместе с объектом и умирает вместе с ним (через `cancel()`).
>
> Примеры: сервер (фоновые задачи от старта до shutdown), сервис с периодическим polling, обработчик fire-and-forget нотификаций.
>
> Внутри suspend-функций НИКОГДА не создавай свой scope. Там используй `coroutineScope { }` — это временный scope на время выполнения функции.

---

## 10. Отмена (Cancellation)

### Как отменить корутину

```kotlin
val job = launch {
    repeat(1000) { i ->
        println("Итерация $i")
        delay(100)   // ← проверяет отмену
    }
}

delay(500)
job.cancel()   // Отмена! Корутина завершится на следующем delay()
```

### Cooperative Cancellation — корутина сама решает, когда остановиться

Важный момент: `cancel()` **не убивает** корутину мгновенно. Корутина проверяет "а не отменили ли меня?" только в **точках приостановки** (suspend-вызовах типа `delay()`, `await()`, `yield()`). Между этими точками корутина работает как ни в чём не бывало.

```kotlin
val job = launch {
    // ПЛОХО — не проверяет отмену, не остановится!
    var i = 0
    while (i < 1_000_000) {
        i++
        // Нет suspend-вызова → отмена не проверяется
    }
}
job.cancel()   // Бесполезно! Цикл продолжит выполняться

// ХОРОШО — проверяет отмену вручную
val job = launch {
    var i = 0
    while (i < 1_000_000) {
        i++
        if (!isActive) break   // ← ручная проверка
        // или:
        yield()                // ← suspend-вызов, проверяет отмену
    }
}
```

### ensureActive() — явная проверка отмены

`ensureActive()` — альтернатива `isActive` + `break`. Бросает `CancellationException` если корутина отменена:

```kotlin
val job = launch {
    var i = 0
    while (i < 1_000_000) {
        ensureActive()  // бросит CancellationException если отменена
        i++
        // Эквивалент: if (!isActive) throw CancellationException()
    }
}
```

**ensureActive() vs yield():**
- `ensureActive()` — только проверяет отмену, не уступает поток
- `yield()` — проверяет отмену И уступает поток другим корутинам

### cancel() vs cancelAndJoin()

```kotlin
val job = launch {
    repeat(1000) {
        println("Working $it")
        delay(100)
    }
}

// cancel() — ТОЛЬКО сигнализирует об отмене, НЕ ждёт завершения
job.cancel()
println("После cancel")  // может напечататься ДО завершения корутины!

// cancelAndJoin() — сигнализирует И ждёт завершения
job.cancelAndJoin()
println("После cancelAndJoin")  // гарантированно ПОСЛЕ завершения корутины
```

### invokeOnCompletion — callback при завершении

```kotlin
val job = launch { doWork() }

job.invokeOnCompletion { cause ->
    when (cause) {
        null -> println("Completed successfully")
        is CancellationException -> println("Was cancelled")
        else -> println("Failed with $cause")
    }
}
```

**Важно:** callback вызывается на произвольном потоке и должен быть быстрым (не suspend!).

### CancellationException — это НЕ ошибка

При отмене корутина бросает `CancellationException`. Но это **нормальная ситуация**, а не сбой. Как увольнение по собственному желанию — это не проблема, просто человек ушёл.

**Ключевой факт:** если ребёнок бросил `CancellationException` — родитель **не считает это ошибкой** и не отменяет остальных детей. Если бросил `IOException` — это уже ошибка, и родитель всех отменит:

```kotlin
coroutineScope {
    val job1 = launch { delay(Long.MAX_VALUE) }
    val job2 = launch { delay(Long.MAX_VALUE) }

    delay(100)
    job1.cancel()  // Отмена job1 — это НЕ ошибка
    // job2 продолжает работать! Parent scope НЕ отменён
    // Потому что CancellationException — это "нормальная отмена", не сбой
}
```

Если бы `job1` бросил `IOException` — это была бы ошибка, и `job2` + parent были бы отменены.

```kotlin
launch {
    try {
        delay(1000)
    } catch (e: CancellationException) {
        // НЕ глотай это исключение! Перебрось:
        throw e
    } catch (e: Exception) {
        // Обработай другие ошибки
    }
}
```

### Отмена в реальном сервисе: пошаговый пример

Допустим, у нас HTTP-сервер с таймаутом 5 секунд на запрос:

```kotlin
class TransferController(
    private val transferService: TransferService
) {
    suspend fun handleTransfer(request: TransferRequest): Response {
        // withTimeout создаёт scope с таймаутом — если не успели за 5с, всё отменяется
        return withTimeout(5000) {
            val validated = async { transferService.validate(request) }    // 1с
            val enriched = async { transferService.enrichData(request) }   // 2с

            val transfer = transferService.create(
                validated.await(),
                enriched.await()
            )

            // Фоновая отправка нотификации — тоже отменится при таймауте
            launch { notificationService.send(transfer) }

            Response.ok(transfer)
        }
    }
}
```

**Trace при таймауте:**

```
t=0s:    withTimeout(5000) создаёт scope
t=0s:    async{validate} STARTED
t=0s:    async{enrichData} STARTED
t=1s:    async{validate} COMPLETED ✓
t=2s:    async{enrichData} COMPLETED ✓
t=2s:    transferService.create() вызван... но он медленный (БД + Kafka)
t=5s:    TIMEOUT! withTimeout отменяет scope:
         └── transferService.create() получает CancellationException
             (на ближайшем suspend-вызове — например, при IO)
t=5s:    launch{notificationService.send} — даже не запустился, scope отменён
t=5s:    withTimeout бросает TimeoutCancellationException
t=5s:    handleTransfer возвращает 504 Gateway Timeout
```

### withTimeout vs withTimeoutOrNull

```kotlin
// withTimeout — бросает TimeoutCancellationException
try {
    val result = withTimeout(1000) { slowOperation() }
} catch (e: TimeoutCancellationException) {
    log.warn("Operation timed out")
}

// withTimeoutOrNull — возвращает null при таймауте (удобнее)
val result = withTimeoutOrNull(1000) { slowOperation() }
if (result == null) {
    log.warn("Operation timed out, using fallback")
    return fallbackValue
}
```

### Освобождение ресурсов при отмене

```kotlin
launch {
    val connection = openDatabaseConnection()
    try {
        // работаем с connection...
        delay(10_000)  // может быть отменена тут
    } finally {
        // finally ВСЕГДА выполнится, даже при отмене
        connection.close()  // ← ресурс освобождён

        // ВНИМАНИЕ: в finally suspend-вызовы бросят CancellationException!
        // Если нужен suspend-вызов в finally:
        withContext(NonCancellable) {
            auditLog.save("Connection closed")  // suspend-вызов в finally
        }
    }
}
```

### Иерархия отмены — каскадное поведение

```
Application scope (SupervisorJob)
├── KafkaConsumer (launch)
│   ├── processMessage1 (launch) — если упадёт, НЕ убьёт KafkaConsumer
│   └── processMessage2 (launch)   (потому что SupervisorJob)
├── HealthCheck (launch)
└── MetricsReporter (launch)

При shutdown:
applicationScope.cancel()
├── KafkaConsumer    — CANCELLED → все дочерние processMessage отменяются
├── HealthCheck      — CANCELLED
└── MetricsReporter  — CANCELLED
```

---

## 10.1. Flow — реактивные потоки (базовые знания)

### Что такое Flow

`Flow` — это **холодный асинхронный поток** данных. Аналог `Sequence`, но с поддержкой suspend-функций.

```kotlin
// Sequence — синхронный, блокирует поток
fun numbers(): Sequence<Int> = sequence {
    yield(1)
    yield(2)
    yield(3)
}

// Flow — асинхронный, НЕ блокирует поток
fun numbers(): Flow<Int> = flow {
    emit(1)
    delay(100)  // suspend! Поток свободен
    emit(2)
    delay(100)
    emit(3)
}
```

### Холодный vs Горячий — важное различие

**Cold Flow** — как Netflix: фильм не идёт, пока ты не нажал Play. Каждый зритель смотрит с начала.

**Hot Flow** — как телевизор: канал вещает независимо от того, смотрит кто-то или нет. Включился — видишь то, что сейчас в эфире.

```kotlin
// Cold: код внутри flow {} НЕ выполняется, пока не вызовут collect
val myFlow = flow {
    println("Flow started")  // не напечатается, пока нет collect
    emit(1)
    emit(2)
}

// Ничего не происходит — flow "спит"

myFlow.collect { println(it) }  // "Flow started", 1, 2  — запустился!
myFlow.collect { println(it) }  // "Flow started", 1, 2  — опять с начала!
```

**SharedFlow/StateFlow — горячие:** данные текут независимо от подписчиков. Подключился — получаешь то, что сейчас есть.

### Основные операторы

```kotlin
// map, filter, take — как у коллекций, но suspend
flowOf(1, 2, 3, 4, 5)
    .filter { it % 2 == 0 }        // 2, 4
    .map { it * 10 }               // 20, 40
    .take(1)                        // 20
    .collect { println(it) }        // 20

// onEach — побочный эффект на каждый элемент
transferEvents
    .onEach { log.info("Processing: $it") }
    .collect { processEvent(it) }

// catch — обработка ошибок в flow
dataFlow
    .catch { e -> emit(fallbackValue) }  // при ошибке — fallback
    .collect { use(it) }

// flowOn — сменить dispatcher для upstream операторов
flow { emit(heavyComputation()) }
    .flowOn(Dispatchers.Default)    // heavyComputation на Default
    .collect { updateUI(it) }       // collect на текущем dispatcher
```

### Терминальные операторы

```kotlin
val flow = flowOf(1, 2, 3, 4, 5)

flow.collect { println(it) }        // собрать все элементы
flow.toList()                        // собрать в список: [1, 2, 3, 4, 5]
flow.first()                         // первый элемент: 1
flow.reduce { acc, v -> acc + v }    // свернуть: 15
flow.count()                         // количество: 5
```

### Flow в реальном сервисе

```kotlin
// Стриминг событий из Kafka
fun kafkaEvents(): Flow<TransferEvent> = flow {
    val consumer = KafkaConsumer<String, TransferEvent>(config)
    consumer.subscribe(listOf("transfer-events"))
    try {
        while (currentCoroutineContext().isActive) {
            val records = withContext(Dispatchers.IO) {
                consumer.poll(Duration.ofMillis(100))
            }
            records.forEach { record -> emit(record.value()) }
        }
    } finally {
        consumer.close()
    }
}

// Использование:
kafkaEvents()
    .filter { it.type == "COMPLETED" }
    .map { enrichWithUserData(it) }
    .collect { notificationService.send(it) }
```

### StateFlow — реактивное состояние

```kotlin
class ConnectionPool {
    // StateFlow — всегда имеет текущее значение
    private val _activeConnections = MutableStateFlow(0)
    val activeConnections: StateFlow<Int> = _activeConnections.asStateFlow()

    fun acquire() { _activeConnections.update { it + 1 } }
    fun release() { _activeConnections.update { it - 1 } }
}

// Подписчик получает текущее значение сразу + все обновления
connectionPool.activeConnections.collect { count ->
    if (count > maxConnections) alert("Too many connections: $count")
}
```

### Backpressure — "а если consumer не успевает?"

Представь конвейер на фабрике: если рабочий в конце не успевает обрабатывать детали — конвейер должен замедлиться, иначе детали упадут на пол.

Flow работает так же: `emit()` **ждёт**, пока `collect` обработает предыдущий элемент. Producer не обгонит consumer:

```kotlin
flow {
    repeat(100) { i ->
        emit(i)          // приостановится, если collector не готов
        println("Emitted $i")
    }
}.collect { value ->
    delay(100)           // медленная обработка
    println("Collected $value")
}
// Вывод: Emitted 0, Collected 0, Emitted 1, Collected 1, ...
// Producer ждёт consumer — строго по очереди
```

### buffer() — отделить producer от consumer

`buffer()` создаёт канал между producer и consumer, позволяя им работать **параллельно**:

```kotlin
flow {
    repeat(5) { i ->
        delay(100)  // producer: 100ms на элемент
        emit(i)
    }
}
.buffer(capacity = 10)  // буфер на 10 элементов
.collect { value ->
    delay(300)  // consumer: 300ms на элемент
    println(value)
}
// Без buffer: 5 * (100 + 300) = 2000ms
// С buffer: 100 + 5 * 300 = 1600ms (producer работает параллельно)
```

### conflate() — пропускать промежуточные значения

Если collector медленный — `conflate()` пропускает промежуточные значения, оставляя только **последнее**:

```kotlin
flow {
    repeat(10) { i ->
        emit(i)
        delay(50)  // producer быстрый — каждые 50ms
    }
}
.conflate()  // при задержке collector'а — пропустить старые
.collect { value ->
    delay(200)  // consumer медленный — каждые 200ms
    println(value)
}
// Вывод: 0, 3, 6, 9  (промежуточные 1,2,4,5,7,8 пропущены)
```

### flatMap-операторы — обработка вложенных потоков

```kotlin
// flatMapConcat — последовательно: ждёт завершения внутреннего flow
(1..3).asFlow().flatMapConcat { userId ->
    flow {
        emit("$userId: fetching...")
        delay(100)
        emit("$userId: done")
    }
}
// 1: fetching, 1: done, 2: fetching, 2: done, 3: fetching, 3: done

// flatMapMerge — параллельно: все внутренние flow работают одновременно
(1..3).asFlow().flatMapMerge(concurrency = 3) { userId ->
    flow {
        emit("$userId: fetching...")
        delay(100)
        emit("$userId: done")
    }
}
// 1: fetching, 2: fetching, 3: fetching, 1: done, 2: done, 3: done

// flatMapLatest — отменяет предыдущий flow при новом значении
searchQuery.flatMapLatest { query ->
    flow { emit(search(query)) }  // при новом запросе — старый отменяется
}
```

### combine и zip — объединение потоков

```kotlin
// zip — попарно: ждёт значения от обоих потоков
val names = flowOf("Alice", "Bob", "Charlie")
val ages = flowOf(25, 30, 35)
names.zip(ages) { name, age -> "$name is $age" }
// "Alice is 25", "Bob is 30", "Charlie is 35"

// combine — комбинирует последние значения: срабатывает при каждом обновлении
val temperature = MutableStateFlow(20)
val humidity = MutableStateFlow(60)
combine(temperature, humidity) { temp, hum ->
    "Temp=$temp, Humidity=$hum"
}.collect { println(it) }
// При любом обновлении temperature или humidity — пересчитывается
```

### Context preservation — одно неочевидное правило

Flow запрещает менять dispatcher внутри `flow { }` через `withContext`. Почему? Чтобы не сломать последовательность `emit()`. Вместо этого используй `flowOn`:

```kotlin
// ПЛОХО — компилятор не пропустит
flow {
    withContext(Dispatchers.IO) {  // IllegalStateException!
        emit(fetchData())
    }
}

// ХОРОШО — flowOn меняет контекст upstream
flow {
    emit(fetchData())  // будет выполнено на IO
}
.flowOn(Dispatchers.IO)  // ← меняет контекст для ВЕРХНИХ операторов
.map { process(it) }     // ← выполняется на контексте коллектора
.collect { use(it) }     // ← выполняется на контексте коллектора
```

### launchIn — запуск flow в scope

```kotlin
// Вместо:
scope.launch {
    myFlow.collect { processEvent(it) }
}

// Можно:
myFlow
    .onEach { processEvent(it) }
    .launchIn(scope)  // запускает collection в указанном scope
```

### Flow vs Sequence vs List

```
List<T>        — все элементы в памяти сразу
Sequence<T>    — ленивый, синхронный, блокирует поток
Flow<T>        — ленивый, асинхронный, НЕ блокирует поток
Channel<T>     — горячий поток с backpressure (для producer-consumer)
SharedFlow<T>  — горячий flow, множество подписчиков, replay
StateFlow<T>   — горячий flow, всегда хранит последнее значение (как LiveData)
```

---

## 11. Реальные сценарии для собеседования

### Сценарий 1: Параллельные HTTP-вызовы с таймаутом

**Задача:** Обогатить платёж данными из 3 внешних сервисов параллельно. Общий таймаут — 3 секунды.

```kotlin
suspend fun enrichPayment(paymentId: String): EnrichedPayment {
    return withTimeout(3000) {
        coroutineScope {
            val userDeferred = async { userService.getUser(paymentId) }          // ~500ms
            val complianceDeferred = async { complianceService.check(paymentId) } // ~800ms
            val fxDeferred = async { fxService.getRate("USD", "EUR") }           // ~200ms

            EnrichedPayment(
                user = userDeferred.await(),
                compliance = complianceDeferred.await(),
                fxRate = fxDeferred.await()
            )
        }
    }
    // Общее время ≈ 800ms (max), а не 1500ms (sum)
    // Если любой упадёт → все отменятся (coroutineScope)
    // Если не успели за 3с → TimeoutCancellationException
}

// Вариант с fallback при таймауте:
suspend fun enrichPaymentSafe(paymentId: String): EnrichedPayment {
    return withTimeoutOrNull(3000) {
        coroutineScope {
            val user = async { userService.getUser(paymentId) }
            val compliance = async { complianceService.check(paymentId) }
            val fx = async { fxService.getRate("USD", "EUR") }
            EnrichedPayment(user.await(), compliance.await(), fx.await())
        }
    } ?: EnrichedPayment.DEFAULT  // fallback при таймауте
}
```

### Сценарий 2: Fan-out / Fan-in (раздать задачи воркерам)

**Задача:** Обработать 1000 транзакций, распределив их по N воркерам.

```kotlin
suspend fun processTransactions(transactions: List<Transaction>) = coroutineScope {
    val workerCount = 10
    val channel = Channel<Transaction>(capacity = 100)  // буферизированный канал

    // Fan-out: N воркеров читают из одного канала
    val workers = (1..workerCount).map { workerId ->
        launch {
            for (tx in channel) {  // каждый воркер берёт следующую задачу
                try {
                    processOne(tx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Worker $workerId failed on tx ${tx.id}", e)
                }
            }
        }
    }

    // Продюсер: отправляем задачи в канал
    for (tx in transactions) {
        channel.send(tx)  // suspend, если канал полон (backpressure!)
    }
    channel.close()  // сигнал воркерам: больше задач не будет

    // coroutineScope ждёт завершения всех воркеров
}
```

### Сценарий 3: Rate Limiting

**Задача:** Вызывать внешний API не чаще 10 раз в секунду.

```kotlin
suspend fun processWithRateLimit(items: List<Item>) = coroutineScope {
    val semaphore = Semaphore(10)  // максимум 10 одновременных запросов

    items.map { item ->
        async {
            semaphore.withPermit {
                // Внутри семафора — максимум 10 корутин одновременно
                externalApi.process(item)
            }
        }
    }.awaitAll()
}

// Более точный rate limiting — через фиксированное окно:
suspend fun callWithRateLimit(items: List<Item>) {
    val batchSize = 10
    items.chunked(batchSize).forEach { batch ->
        coroutineScope {
            batch.map { item ->
                async { externalApi.process(item) }
            }.awaitAll()
        }
        delay(1000)  // пауза 1с между батчами
    }
}
```

### Сценарий 4: Retry с Exponential Backoff

**Задача:** Повторить HTTP-запрос при ошибке с экспоненциальной задержкой.

```kotlin
suspend fun <T> retryWithBackoff(
    maxRetries: Int = 3,
    initialDelay: Long = 1000,
    maxDelay: Long = 10_000,
    factor: Double = 2.0,
    block: suspend () -> T
): T {
    var currentDelay = initialDelay
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            throw e  // НИКОГДА не ретраим отмену!
        } catch (e: Exception) {
            log.warn("Attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
            if (attempt == maxRetries - 1) throw e  // последняя попытка — пробрасываем
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    error("Unreachable")
}

// Использование:
suspend fun getExchangeRate(from: String, to: String): BigDecimal {
    return retryWithBackoff(maxRetries = 3, initialDelay = 500) {
        fxClient.getRate(from, to)  // если 503 → повтор через 500ms, 1000ms, 2000ms
    }
}
```

### Сценарий 5: Graceful Shutdown сервера

**Задача:** При получении SIGTERM корректно завершить все текущие задачи.

```kotlin
class PaymentServer {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("payment-server")
    )
    private val activeRequests = AtomicInteger(0)

    fun start() {
        scope.launch { startKafkaConsumer() }
        scope.launch { startHealthCheck() }

        // Обработчик shutdown
        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking {
                gracefulShutdown()
            }
        })
    }

    private suspend fun gracefulShutdown() {
        log.info("Shutdown initiated, active requests: ${activeRequests.get()}")

        // 1. Перестаём принимать новые запросы (health check → unhealthy)
        healthy.set(false)

        // 2. Даём текущим запросам 30 секунд на завершение
        withTimeoutOrNull(30_000) {
            while (activeRequests.get() > 0) {
                delay(100)
            }
        } ?: log.warn("Forced shutdown with ${activeRequests.get()} active requests")

        // 3. Отменяем все фоновые задачи
        scope.cancel()
        scope.coroutineContext.job.children.forEach { it.join() }

        log.info("Shutdown complete")
    }
}
```

### Сценарий 6: Producer-Consumer через Channel

**Задача:** Kafka consumer кладёт события в Channel, несколько обработчиков их разбирают.

```kotlin
class EventPipeline {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<TransferEvent>(capacity = 256)

    fun start() {
        // 1 producer — читает из Kafka
        scope.launch {
            val consumer = createKafkaConsumer()
            try {
                while (isActive) {
                    val records = withContext(Dispatchers.IO) {
                        consumer.poll(Duration.ofMillis(100))
                    }
                    for (record in records) {
                        events.send(record.value())  // backpressure при полном канале
                    }
                }
            } finally {
                events.close()
                consumer.close()
            }
        }

        // N consumers — обрабатывают события
        repeat(5) { consumerId ->
            scope.launch {
                for (event in events) {  // suspend, если канал пуст
                    try {
                        processEvent(event)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("Consumer $consumerId failed on event ${event.id}", e)
                    }
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
    }
}
```

### Сценарий 7: Параллельный сбор с частичными результатами (supervisorScope)

**Задача:** Собрать данные из 5 источников. Если один недоступен — вернуть остальные.

```kotlin
data class DashboardData(
    val profile: UserProfile?,
    val balance: Balance?,
    val transactions: List<Transaction>?,
    val notifications: List<Notification>?,
    val recommendations: List<Product>?
)

suspend fun loadDashboard(userId: String): DashboardData = supervisorScope {
    // supervisorScope: ошибка одного НЕ отменяет остальных

    val profile = async { safeCall { userService.getProfile(userId) } }
    val balance = async { safeCall { balanceService.getBalance(userId) } }
    val txs = async { safeCall { txService.getRecent(userId, limit = 10) } }
    val notifs = async { safeCall { notifService.getUnread(userId) } }
    val recs = async { safeCall { recsService.getFor(userId) } }

    DashboardData(
        profile = profile.await(),
        balance = balance.await(),
        transactions = txs.await(),
        notifications = notifs.await(),
        recommendations = recs.await()
    )
}

// Обёртка: при ошибке возвращает null вместо exception
private suspend fun <T> safeCall(block: suspend () -> T): T? {
    return try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Partial failure: ${e.message}")
        null
    }
}
```

---

## 12. Как это работает в нашем проекте

### pricing-service: Lettuce async + await

```kotlin
// QuoteCacheService.kt
suspend fun save(quote: Quote) {
    val json = Json.encodeToString(CachedQuote.serializer(), cached)

    redisClientFactory.asyncCommands   // 1. Lettuce async API
        .setex(key, ttl, json)          // 2. Возвращает RedisFuture<String>
                                        //    (это CompletionStage, как CompletableFuture)
        .await()                        // 3. Приостанавливает корутину
                                        //    Поток свободен!
                                        //    Когда Redis ответит — корутина возобновится
}
```

Цепочка вызовов:

```
gRPC request (Netty thread)
  → PricingGrpcService.getQuote()      [suspend]
    → pricingService.calculateQuote()   [suspend]
      → quoteCacheService.save()        [suspend]
        → redis.setex().await()         [ПРИОСТАНОВКА — поток свободен]
        ← Redis ответил "OK"            [ВОЗОБНОВЛЕНИЕ]
      ← return quote
    ← return toQuoteResponse(quote)
  ← gRPC отправляет ответ клиенту
```

Весь путь — одна корутина. Ни один поток не заблокирован.

### transfer-service: Spring Data Redis (синхронный)

```kotlin
// TransferCacheService.kt
fun getCached(transferId: UUID): TransferResponse? {   // НЕ suspend!
    val json = redisTemplate.opsForValue().get(key)     // БЛОКИРУЕТ поток
    return objectMapper.readValue(json, ...)
}
```

Spring Data Redis по умолчанию синхронный. Поток блокируется на время запроса к Redis. Для Spring MVC это нормально — каждый запрос и так обрабатывается в отдельном потоке (thread-per-request модель).

### Почему разные подходы?

```
pricing-service (Ktor + корутины):
  Ktor использует корутины нативно
  Каждый запрос = корутина, не поток
  Поэтому НЕЛЬЗЯ блокировать → используем Lettuce async + await()

transfer-service (Spring MVC):
  Spring MVC = thread-per-request
  Каждый запрос = отдельный поток
  Блокировка потока допустима → используем Spring Data Redis (sync)
  Если бы был Spring WebFlux → нужен был бы Reactive Redis
```

---

## 13. CompletableFuture.await() — мост Java ↔ корутины

### Зачем нужен мост

Lettuce (Redis-клиент) — Java-библиотека. Она не знает про Kotlin корутины. Её async API возвращает `CompletableFuture` (Java).

Корутины — Kotlin. Они работают с `suspend` функциями.

`await()` — мост между этими мирами.

### Как работает

```kotlin
import kotlinx.coroutines.future.await   // extension из kotlinx-coroutines-jdk8

// Lettuce возвращает Java Future
val future: RedisFuture<String> = redisCommands.get(key)
// RedisFuture implements CompletionStage (как CompletableFuture)

// await() превращает Future в suspend-вызов
val result: String = future.await()
```

Под капотом `await()` делает:

```kotlin
// Упрощённая реализация
suspend fun <T> CompletableFuture<T>.await(): T {
    // Если Future уже завершён — вернуть результат сразу
    if (isDone) {
        return get()   // не блокирует, результат уже есть
    }

    // Иначе — приостановить корутину
    return suspendCancellableCoroutine { continuation ->
        // Зарегистрировать callback
        this.whenComplete { result, exception ->
            if (exception != null) {
                continuation.resumeWithException(exception)
            } else {
                continuation.resume(result)
            }
        }
    }
}
```

`suspendCancellableCoroutine` — примитив, который:
1. Приостанавливает корутину
2. Даёт тебе объект `continuation`
3. Ты вызываешь `continuation.resume()` когда результат готов

### Другие мосты

```kotlin
// Java CompletableFuture → Kotlin suspend
future.await()

// Reactor Mono → Kotlin suspend
mono.awaitSingle()        // из kotlinx-coroutines-reactor

// Reactor Flux → Kotlin Flow
flux.asFlow()

// Kotlin suspend → Java CompletableFuture
GlobalScope.future { suspendFunction() }
```

---

## 14. runBlocking в тестах — ловушка

### Проблема с expression-body

```kotlin
// ПЛОХО — тест может быть пропущен!
@Test
fun `test something`() = runBlocking {
    val result = someService.doSomething()
    result shouldBe expected   // kotest shouldBe возвращает значение, не Unit
}
```

`runBlocking { ... }` возвращает последнее выражение из блока. `shouldBe` возвращает значение (не `Unit`). JUnit ожидает, что тестовый метод возвращает `void` (`Unit`). Если возвращается не `Unit` — JUnit **молча пропускает** тест!

### Решение

```kotlin
// ХОРОШО — явно указываем Unit
@Test
fun `test something`() = runBlocking<Unit> {
    val result = someService.doSomething()
    result shouldBe expected   // теперь runBlocking<Unit> принудительно вернёт Unit
}
```

### Альтернативное решение

```kotlin
// Тоже ОК — блочное тело, return type = Unit по умолчанию
@Test
fun `test something`() {
    runBlocking {
        val result = someService.doSomething()
        result shouldBe expected
    }
}
```

Это задокументировано в нашем MEMORY.md — реальный баг, который мы ловили.

---

## 15. Частые ошибки

### 1. Блокирующий вызов в корутине

```kotlin
// ПЛОХО
suspend fun getUser(): User {
    return jdbcTemplate.queryForObject(...)   // БЛОКИРУЕТ поток!
}

// ХОРОШО — оборачиваем в IO dispatcher
suspend fun getUser(): User {
    return withContext(Dispatchers.IO) {
        jdbcTemplate.queryForObject(...)
    }
}
```

### 2. GlobalScope — утечка корутин

```kotlin
// ПЛОХО — корутина живёт "вечно", не привязана к lifecycle
GlobalScope.launch {
    sendNotification()
}

// ХОРОШО — привязана к scope
scope.launch {
    sendNotification()
}
```

### 3. Ловить CancellationException

```kotlin
// ПЛОХО — глотаем отмену, корутина не отменится
try {
    suspendFunction()
} catch (e: Exception) {   // CancellationException — тоже Exception!
    log.error("Error", e)
    // корутина продолжает работать, хотя должна была отмениться
}

// ХОРОШО — пробрасываем отмену
try {
    suspendFunction()
} catch (e: CancellationException) {
    throw e   // пробрасываем!
} catch (e: Exception) {
    log.error("Error", e)
}
```

### 4. async без await

```kotlin
// ПЛОХО — исключение потеряно!
scope.launch {
    val deferred = async { riskyOperation() }
    // Забыли await() — если riskyOperation() упадёт, исключение проглочено
}

// ХОРОШО
scope.launch {
    val deferred = async { riskyOperation() }
    deferred.await()   // исключение пробросится
}
```

### 5. Вызов .get() вместо .await()

```kotlin
// ПЛОХО — блокирует поток внутри корутины
suspend fun getQuote(): String {
    return redis.getAsync(key).get()      // Thread.sleep() по сути!
}

// ХОРОШО — приостанавливает корутину
suspend fun getQuote(): String {
    return redis.getAsync(key).await()    // поток свободен
}
```

### 6. Exception Handling — где летят ошибки

#### launch vs async — ошибки идут разными путями

Это ловушка, о которую спотыкаются все. Запомни:

```kotlin
// launch — исключение пробрасывается СРАЗУ в parent scope
scope.launch {
    throw IOException("Boom")
    // → исключение летит в parent Job
    // → если SupervisorJob — только эта корутина падает
    // → если обычный Job — все сёстры отменяются
}

// async — исключение ОТКЛАДЫВАЕТСЯ до вызова await()
val deferred = scope.async {
    throw IOException("Boom")
    // → исключение СОХРАНЯЕТСЯ в Deferred
    // → parent НЕ узнает об ошибке, пока не вызвать await()
}
// ... позже:
deferred.await()  // ← вот тут IOException пробросится
```

**НО!** Есть нюанс: если `async` используется внутри `coroutineScope` (structured concurrency), исключение всё равно пробросится в parent, даже без `await()`:

```kotlin
coroutineScope {
    val d = async { throw IOException("Boom") }
    // Даже без d.await() — coroutineScope получит исключение!
}
```

#### CoroutineExceptionHandler — "пожарный выход"

Когда исключение долетело до верха и никто его не поймал — срабатывает `CoroutineExceptionHandler`. Но у него два ограничения: работает только с `launch` и только на **корневом scope**:

```kotlin
val handler = CoroutineExceptionHandler { ctx, exception ->
    log.error("Uncaught exception in ${ctx[CoroutineName]}", exception)
    // Тут можно отправить в Sentry, записать в лог, etc.
}

// РАБОТАЕТ — handler на корневом scope
val scope = CoroutineScope(SupervisorJob() + handler)
scope.launch {
    throw IOException("Boom")  // → handler поймает
}

// НЕ РАБОТАЕТ — handler на дочерней корутине (игнорируется)
scope.launch {
    launch(handler) {  // handler тут БЕСПОЛЕЗЕН
        throw IOException("Boom")  // → полетит в parent, не в handler
    }
}
```

**Правило:** устанавливай handler на **корневом scope**, не на дочерних корутинах. На дочерних он бесполезен — ошибка полетит мимо него, к родителю.

#### Полная картина обработки исключений

```
Исключение в корутине
├── CancellationException?
│   ├── ДА → "нормальная отмена", parent НЕ считает это ошибкой
│   └── НЕТ → "реальная ошибка"
│       ├── async? → сохраняется в Deferred, пробрасывается при await()
│       └── launch? → летит в parent Job
│           ├── Parent = SupervisorJob?
│           │   ├── ДА → только эта корутина падает, сёстры живы
│           │   │       → CoroutineExceptionHandler (если есть)
│           │   └── НЕТ → parent отменяет ВСЕХ детей
│           │           → parent сам бросает исключение НАВЕРХ
│           └── Root scope? → CoroutineExceptionHandler (если есть)
│                           → Иначе → Thread.uncaughtExceptionHandler
```

---

## 16. Шпаргалка

### Ключевые слова

| Что | Означает |
|-----|---------|
| `suspend fun` | Функция может приостановиться (но не обязана) |
| `launch { }` | Запусти корутину, результат не нужен → Job |
| `async { }` | Запусти корутину, результат нужен → Deferred\<T\> |
| `runBlocking { }` | Заблокируй поток и выполни корутину → T |
| `delay(ms)` | Приостановить корутину на N мс (не блокирует поток!) |
| `yield()` | Уступить поток другим корутинам + проверить отмену |
| `ensureActive()` | Проверить отмену (бросает CancellationException) |
| `withContext(dispatcher)` | Выполнить блок на другом dispatcher'е |
| `coroutineScope { }` | Создать scope, дождаться всех детей (fail-fast) |
| `supervisorScope { }` | Как coroutineScope, но дети независимы |
| `.await()` | Приостановиться до готовности Deferred/Future |
| `withTimeout(ms) { }` | Ограничить время выполнения блока |
| `flow { emit() }` | Создать холодный асинхронный поток |
| `channel.send/receive` | Горячий поток с backpressure |

### Когда что использовать

```
Нужно запустить корутину без результата?        → launch
Нужно запустить и получить результат?           → async + await
Нужно вызвать suspend из обычного кода?         → runBlocking
Нужно переключить поток?                        → withContext
Нужно дождаться Java Future без блокировки?     → .await()
Нужно запустить параллельно?                    → несколько async + await всех
Нужно ограничить время жизни корутин?           → CoroutineScope
Нужен таймаут на операцию?                      → withTimeout / withTimeoutOrNull
Нужен поток значений (0..N)?                    → Flow
Нужен producer-consumer?                        → Channel
Дети должны быть независимы?                    → SupervisorJob / supervisorScope
Ограничить параллельность на dispatcher?         → limitedParallelism()
```

### Dispatchers

```
CPU-вычисления (парсинг, сортировка)    → Dispatchers.Default
Блокирующий IO (JDBC, файлы)            → Dispatchers.IO
Non-blocking IO (Lettuce, Ktor)         → любой (Default по умолчанию)
```

### Аналогии

```
Thread              ≈ машина (дорогая, тяжёлая)
Coroutine           ≈ пассажир (лёгкий, много на одну машину)
Dispatcher          ≈ дорога (какая машина поедет)
CoroutineContext    ≈ паспорт пассажира (имя, маршрут, обработчик ЧП)
suspend             ≈ "могу подождать на остановке"
Continuation        ≈ закладка в книге (где остановился + переменные)
await()             ≈ "жду автобус, но не стою столбом — читаю книгу"
.get()              ≈ "стою столбом и жду автобус"
CoroutineScope      ≈ маршрут (начало и конец, все пассажиры доедут)
SupervisorJob       ≈ маршрут, где каждый пассажир едет независимо
cancel()            ≈ "маршрут отменён, все выходят"
Flow                ≈ конвейерная лента (элементы по одному)
Channel             ≈ труба между цехами (producer-consumer)
withTimeout         ≈ "если автобус не придёт за 5 минут — ухожу"
```