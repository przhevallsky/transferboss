# Ktor Routing, DSL и как устроена регистрация эндпоинтов

## Часть 1. Что такое DSL

### Определение

**DSL (Domain-Specific Language)** — предметно-ориентированный язык. Это язык, созданный для решения задач в конкретной области, в отличие от **GPL (General-Purpose Language)** — языка общего назначения (Java, Kotlin, Python).

Примеры DSL, которые ты уже знаешь:
- **SQL** — DSL для работы с базами данных (`SELECT * FROM users WHERE age > 18`)
- **HTML** — DSL для разметки веб-страниц (`<div><p>Hello</p></div>`)
- **Regex** — DSL для поиска по шаблонам (`\d{3}-\d{4}`)
- **Gradle Kotlin DSL** — DSL для описания сборки (`dependencies { implementation("...") }`)
- **Dockerfile** — DSL для описания контейнеров (`FROM`, `RUN`, `COPY`)

### Внутренний DSL vs Внешний DSL

| Тип | Определение | Примеры |
|---|---|---|
| **Внешний DSL** | Свой синтаксис, свой парсер | SQL, HTML, Regex, YAML |
| **Внутренний DSL** | Написан **внутри** основного языка, используя его синтаксис | Ktor routing, Gradle Kotlin DSL, Jetpack Compose |

Ktor routing — это **внутренний DSL на Kotlin**. Выглядит как отдельный язык, но на самом деле это обычный Kotlin-код, использующий:
- **Лямбды с получателем** (lambda with receiver)
- **Extension-функции**
- **Trailing lambda** синтаксис

### Как Kotlin позволяет создавать DSL

Три фичи Kotlin, которые делают DSL возможным:

#### 1. Trailing lambda — лямбда за скобками

```kotlin
// Обычный вызов функции с лямбдой:
get("/health", { call.respondText("OK") })

// Trailing lambda — лямбда ПОСЛЕ скобок (если последний параметр):
get("/health") { call.respondText("OK") }

// Если лямбда — единственный параметр, скобки можно убрать:
routing { /* маршруты */ }
```

Это просто синтаксический сахар Kotlin. `routing { }` — это вызов функции `routing()`, куда передаётся лямбда.

#### 2. Lambda with receiver — лямбда с получателем

```kotlin
// Обычная лямбда:
val greet = { name: String -> "Hello, $name" }

// Лямбда с получателем (receiver = Route):
val setupRoutes: Route.() -> Unit = {
    // внутри this = Route
    get("/hello") { call.respondText("Hi") }  // this.get(...)
}
```

`Route.() -> Unit` означает: "лямбда, внутри которой `this` — это объект `Route`". Поэтому внутри `routing { }` можно вызывать `get()`, `post()`, `route()` — это всё методы `Route`.

#### 3. Extension functions — функции-расширения

```kotlin
// Добавляем "метод" к существующему классу
fun Application.configureRouting() { ... }  // this = Application
fun Route.quoteRoutes() { ... }             // this = Route
```

### Как всё собирается вместе

```kotlin
// routing — функция, принимающая лямбду с получателем Route
fun Application.routing(configuration: Route.() -> Unit) { ... }

// get — extension-функция на Route, принимающая лямбду с получателем RoutingContext
fun Route.get(path: String, body: suspend RoutingContext.() -> Unit) { ... }

// Когда ты пишешь:
routing {                              // this = Route
    get("/health") {                   // this = RoutingContext (внутри которого есть call)
        call.respondText("OK")         // call — свойство RoutingContext
    }
}

// Компилятор видит:
Application.routing(fun Route.() {
    this.get("/health", fun RoutingContext.() {
        this.call.respondText("OK")
    })
})
```

Вот почему это выглядит как отдельный язык, но на самом деле — **обычные вызовы функций**.

---

## Часть 2. Регистрация vs Вызов эндпоинтов

### Ключевое понимание

Код внутри `routing { }` выполняется в **два этапа**:

```
Этап 1: СТАРТ ПРИЛОЖЕНИЯ (один раз)
─────────────────────────────────────
routing {
    get("/health/live") { ... }    // ← регистрирует маршрут в дереве
    get("/metrics") { ... }        // ← регистрирует маршрут в дереве
    quoteRoutes(pricingService)    // ← регистрирует маршруты из QuoteRoutes.kt
}
// Результат: в памяти построено дерево маршрутов


Этап 2: ПРИХОД HTTP-ЗАПРОСА (каждый раз)
─────────────────────────────────────
Клиент → GET /health/live
         │
         ▼
Ktor ищет в дереве маршрутов: /health/live → найден!
         │
         ▼
Выполняет лямбду: { call.respondText("OK") }
         │
         ▼
Клиент ← 200 OK "OK"
```

### Что именно делает `get("/path") { ... }`

```kotlin
get("/health/live") {
    call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)
}
```

Эта строка **не** отправляет "OK". Она делает следующее:

1. Создаёт **узел** в дереве маршрутов: метод=GET, путь=/health/live
2. **Привязывает лямбду** `{ call.respondText("OK") }` к этому узлу
3. Лямбда **сохраняется в памяти** и будет вызвана только когда придёт реальный HTTP-запрос

Это как `addEventListener` в JavaScript:

```javascript
// JavaScript — регистрация обработчика (НЕ вызов)
button.addEventListener("click", () => {
    alert("Clicked!")  // выполнится ПОТОМ, при клике
})

// Ktor — регистрация обработчика (НЕ вызов)
get("/health/live") {
    call.respondText("OK")  // выполнится ПОТОМ, при HTTP-запросе
}
```

### Дерево маршрутов (Route Tree)

После выполнения `routing { }` в памяти строится такое дерево:

```
Root (/)
├── GET /health/live        → { call.respondText("OK") }
├── GET /health/ready       → { call.respondText("OK") }
├── GET /metrics            → { call.respondText(appMeterRegistry.scrape()) }
└── /api/v1
    ├── GET /corridors      → { val body = buildJsonObject { ... }; call.respondText(body) }
    ├── GET /quotes         → { /* валидация + pricingService.calculateQuote() */ }
    └── GET /quotes/{quoteId}/validate → { /* pricingService.validateQuote() */ }
```

Когда приходит запрос `GET /api/v1/quotes?source_country=US&...`, Ktor обходит дерево, находит совпадение и вызывает привязанную лямбду.

---

## Часть 3. Routing.kt vs QuoteRoutes.kt — зачем разделять

### Принцип разделения

```
plugins/Routing.kt          → СБОРКА: инфраструктурные маршруты + подключение бизнес-модулей
routes/QuoteRoutes.kt       → БИЗНЕС-ЛОГИКА: маршруты работы с котировками
routes/CorridorRoutes.kt    → (будущее) маршруты работы с коридорами
```

### Routing.kt — "корневой файл"

```kotlin
fun Application.configureRouting(pricingService: PricingService? = null) {
    routing {
        // Инфраструктура (health, metrics) — маленькие, живут тут
        get("/health/live") { call.respondText("OK") }
        get("/health/ready") { call.respondText("OK") }
        get("/metrics") { ... }

        // Бизнес-маршруты — делегируем в отдельные файлы
        if (pricingService != null) {
            quoteRoutes(pricingService)
        }
    }
}
```

Это **extension на Application** — вызывается один раз в `module()`.

### QuoteRoutes.kt — "модуль маршрутов"

```kotlin
fun Route.quoteRoutes(pricingService: PricingService) {
    route("/api/v1") {
        get("/quotes") { /* 40 строк валидации и бизнес-логики */ }
        get("/quotes/{quoteId}/validate") { /* 25 строк */ }
    }
}
```

Это **extension на Route** — встраивается в дерево маршрутов, которое уже открыто в `routing { }`.

### Почему `Application.` vs `Route.`

```kotlin
fun Application.configureRouting(...)
//   ↑ Application — корень приложения
//   Имеет доступ к install(), routing{}, environment
//   Вызывается в module() при старте

fun Route.quoteRoutes(...)
//   ↑ Route — узел в дереве маршрутов
//   Имеет доступ к get(), post(), route()
//   Вызывается ВНУТРИ routing { }, встраивает маршруты в дерево
```

`Route.quoteRoutes()` нельзя вызвать вне `routing { }`, потому что вне routing нет объекта `Route`. А `Application.configureRouting()` нельзя вызвать внутри `routing { }`, потому что там `this = Route`, а не `Application`.

### Аналогия со Spring Boot

| Ktor | Spring Boot | Роль |
|---|---|---|
| `Routing.kt` | Нет прямого аналога (Spring сканирует автоматически) | Точка сборки всех маршрутов |
| `QuoteRoutes.kt` | `QuoteController.kt` | Группа бизнес-эндпоинтов |
| `fun Route.quoteRoutes()` | `@RestController class QuoteController` | Содержит обработчики запросов |
| `get("/quotes") { }` | `@GetMapping("/quotes") fun getQuote()` | Один конкретный эндпоинт |

В Spring контроллеры находятся через **classpath scanning** (`@ComponentScan`). В Ktor маршруты подключаются **явно** — ты сам вызываешь `quoteRoutes()` внутри `routing { }`. Если забудешь вызвать — маршруты не появятся.

---

## Часть 4. Ktor Routing — из официальной документации

### Структура (ktor.io/docs/routing-in-ktor.html)

Официальная документация описывает routing как **дерево** с двумя типами узлов:

1. **Route node** — узел маршрута (путь + метод)
2. **Handler** — обработчик (лямбда, которая вызывается при совпадении)

```kotlin
routing {                          // корень дерева
    route("/api") {                // промежуточный узел (только путь, без метода)
        route("/v1") {             // ещё один промежуточный узел
            get("/users") { }      // лист: GET /api/v1/users → handler
            post("/users") { }     // лист: POST /api/v1/users → handler
        }
    }
}
```

### Matching algorithm (как Ktor ищет маршрут)

При запросе `GET /api/v1/users`:
1. Проверяет корень `/` → есть дочерний `/api` → спускается
2. Проверяет `/api` → есть дочерний `/v1` → спускается
3. Проверяет `/api/v1` → есть дочерний `GET /users` → совпадение!
4. Вызывает привязанный handler

Если совпадение не найдено → StatusPages перехватывает → 404 Not Found.

### Path parameters (параметры пути)

```kotlin
get("/quotes/{quoteId}/validate") {
    val quoteId = call.parameters["quoteId"]  // извлекаем из URL
    // GET /quotes/abc-123/validate → quoteId = "abc-123"
}
```

`{quoteId}` — placeholder. Ktor извлекает значение и кладёт в `call.parameters`.

### Query parameters (параметры запроса)

```kotlin
get("/quotes") {
    val country = call.request.queryParameters["source_country"]
    // GET /quotes?source_country=US → country = "US"
}
```

### Respond — отправка ответа

```kotlin
// Текст
call.respondText("OK", ContentType.Text.Plain, HttpStatusCode.OK)

// JSON (через ContentNegotiation plugin — автоматическая сериализация)
call.respond(HttpStatusCode.OK, quoteResponse)  // объект → JSON автоматически

// Статус без тела
call.respond(HttpStatusCode.NoContent)
```

### Route grouping — группировка маршрутов

```kotlin
// Вместо повторения префикса:
get("/api/v1/quotes") { }
get("/api/v1/quotes/{id}/validate") { }
get("/api/v1/corridors") { }

// Группируем через route():
route("/api/v1") {
    get("/quotes") { }
    get("/quotes/{id}/validate") { }
    get("/corridors") { }
}
```

`route()` — промежуточный узел, который **не обрабатывает запросы сам**, а группирует дочерние маршруты под общим префиксом.

---

## Часть 5. Готовые ответы для собеседования

### «Что такое DSL и как Ktor его использует?»

> «DSL — предметно-ориентированный язык. Ktor использует **внутренний DSL на Kotlin** для описания маршрутов. Выглядит как отдельный язык — `routing { get("/path") { } }` — но компилируется в обычный Kotlin. Под капотом это trailing lambda + lambda with receiver + extension functions. Каждый вызов `get()` создаёт узел в дереве маршрутов и привязывает к нему обработчик.»

### «Когда выполняется код внутри routing { }?»

> «В два этапа. При старте приложения `routing { }` строит дерево маршрутов — это регистрация. Сами лямбды-обработчики вызываются позже, при каждом входящем HTTP-запросе. Это как `addEventListener` — ты регистрируешь обработчик, а он срабатывает при событии.»

### «Зачем разделять Routing.kt и QuoteRoutes.kt?»

> «Разделение ответственности. Routing.kt — корневой файл, extension на Application, собирает инфраструктурные маршруты (health, metrics) и подключает бизнес-модули. QuoteRoutes.kt — extension на Route, содержит маршруты котировок. Это аналог разных @RestController в Spring. Если не разделять — один файл на 300+ строк, который сложно читать и поддерживать.»

### «Чем отличается `fun Application.configureRouting()` от `fun Route.quoteRoutes()`?»

> «Разные receiver-типы. `Application.configureRouting()` — вызывается в module() при старте, имеет доступ к `routing { }`, `install()`. `Route.quoteRoutes()` — вызывается внутри `routing { }`, встраивает маршруты в дерево. Application — корень приложения, Route — узел в дереве маршрутов.»
