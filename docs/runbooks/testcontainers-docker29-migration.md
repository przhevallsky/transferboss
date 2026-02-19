# Testcontainers + Docker Engine 29: Проблема и Решение

## Содержание

1. [Описание проблемы](#1-описание-проблемы)
2. [Корневая причина](#2-корневая-причина)
3. [Шаги решения](#3-шаги-решения)
   - [Шаг 1. Обновление Testcontainers до 2.x](#шаг-1-обновление-testcontainers-до-2x)
   - [Шаг 2. Миграция импортов и API](#шаг-2-миграция-импортов-и-api)
   - [Шаг 3. Spring Boot BOM override](#шаг-3-spring-boot-bom-override)
   - [Шаг 4. Kafka: переход на EmbeddedKafka](#шаг-4-kafka-переход-на-embeddedkafka)
   - [Шаг 5. Исправление сериализации в Pricing Service](#шаг-5-исправление-сериализации-в-pricing-service)
4. [Затронутые файлы](#4-затронутые-файлы)
5. [Уроки](#5-уроки)

---

## 1. Описание проблемы

CI pipeline на GitHub Actions перестал проходить для всех трёх сервисов (Transfer, Outbox, Pricing).
Все тесты, использующие Testcontainers, падали с одной и той же ошибкой:

```
java.lang.IllegalStateException at DockerClientProviderStrategy.java:277
```

При добавлении `--info` логирования в Gradle обнаружилось более детальное сообщение:

```
client version 1.32 is too old. Minimum supported API version is 1.44
```

Тесты на локальных машинах могли работать (если установлен Docker Engine < 29), но на
GitHub Actions runners (Ubuntu latest) Docker Engine обновился до версии **29.x**, что и
вызвало несовместимость.

---

## 2. Корневая причина

### Docker Engine 29 поднял минимальную версию API

Docker Engine 29 (выпущен в начале 2025) установил **минимальную версию Docker API = 1.44**.
Все клиенты, использующие более старые версии API, получают отказ при подключении.

### Testcontainers 1.x использует жёстко зашитую версию API 1.32

Testcontainers 1.x (включая 1.19.x, 1.20.x, 1.21.x) использует библиотеку `docker-java`,
которая при подключении к Docker daemon отправляет запросы с версией API **1.32**.
Эта версия зашита в исходном коде `docker-java` и не конфигурируется через environment variables
(например, `DOCKER_API_VERSION` игнорируется).

```
Клиент (TC 1.x)                    Docker Engine 29
────────────────                    ────────────────
API version: 1.32  ──────────────> Minimum API: 1.44
                   <───── REJECT ─ "client version 1.32 is too old"
```

### Почему раньше работало

GitHub Actions runners используют Ubuntu с предустановленным Docker Engine. До обновления
до Docker 29 runners использовали Docker Engine 24-27, которые поддерживали API version 1.32.
После обновления runners на Docker 29 — все TC 1.x тесты сломались.

---

## 3. Шаги решения

### Шаг 1. Обновление Testcontainers до 2.x

**Проблема:** Testcontainers 1.x в принципе не может работать с Docker Engine 29+.

**Решение:** Обновление до Testcontainers 2.0.2, который использует обновлённую версию
`docker-java` с поддержкой Docker API 1.44+.

**Файл:** `gradle/libs.versions.toml`

```toml
# Было:
testcontainers = "1.20.1"

# Стало:
testcontainers = "2.0.2"
```

**Важно:** В TC 2.x изменились имена артефактов Maven — все модули получили префикс `testcontainers-`:

| TC 1.x артефакт | TC 2.x артефакт |
|---|---|
| `org.testcontainers:postgresql` | `org.testcontainers:testcontainers-postgresql` |
| `org.testcontainers:kafka` | `org.testcontainers:testcontainers-kafka` |
| `org.testcontainers:mongodb` | `org.testcontainers:testcontainers-mongodb` |
| `org.testcontainers:junit-jupiter` | `org.testcontainers:testcontainers-junit-jupiter` |

Поэтому в version catalog нужно было обновить не только версию, но и `module` координаты:

```toml
# Было:
testcontainers-postgresql = { module = "org.testcontainers:postgresql", version.ref = "testcontainers" }

# Стало:
testcontainers-postgresql = { module = "org.testcontainers:testcontainers-postgresql", version.ref = "testcontainers" }
```

---

### Шаг 2. Миграция импортов и API

**Проблема:** В TC 2.x контейнерные классы переехали из пакета `org.testcontainers.containers`
в модуль-специфичные пакеты. Также некоторые классы изменили свой API.

#### 2a. Миграция пакетов

| Старый пакет (TC 1.x) | Новый пакет (TC 2.x) |
|---|---|
| `org.testcontainers.containers.PostgreSQLContainer` | `org.testcontainers.postgresql.PostgreSQLContainer` |
| `org.testcontainers.containers.KafkaContainer` | `org.testcontainers.kafka.KafkaContainer` |
| `org.testcontainers.containers.MongoDBContainer` | `org.testcontainers.mongodb.MongoDBContainer` |

#### 2b. Удаление generic type parameter

В TC 1.x `PostgreSQLContainer` был generic: `PostgreSQLContainer<SELF>`. В коде часто
использовалось `PostgreSQLContainer<*>`.

В TC 2.x generic parameter убран — `PostgreSQLContainer` больше не параметризован:

```kotlin
// Было (TC 1.x):
val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

// Стало (TC 2.x):
val postgres = PostgreSQLContainer("postgres:16-alpine")
```

Без этого исправления Kotlin компилятор выдаёт:
```
No type arguments expected for class PostgreSQLContainer
```

**Затронутые файлы:**
- `services/transfer-service/src/test/.../IntegrationTestBase.kt`
- `services/outbox-service/src/test/.../IntegrationTestBase.kt`
- `services/pricing-service/src/test/.../IntegrationTestBase.kt`

---

### Шаг 3. Spring Boot BOM override

**Проблема:** После обновления до TC 2.x — Pricing Service (Ktor) заработал, а Transfer Service
и Outbox Service (Spring Boot) продолжали падать с **той же** ошибкой Docker API 1.32.

**Причина:** Spring Boot использует плагин `io.spring.dependency-management`, который через
свой BOM управляет версиями зависимостей. Spring Boot 3.3.4 включает Testcontainers 1.x в
своём BOM.

Даже если в version catalog указана версия `2.0.2` для модулей (`testcontainers-postgresql`),
**транзитивная зависимость** на core модуль `org.testcontainers:testcontainers` подтягивается
Spring Boot BOM как **1.x**. Именно в core модуле живёт `DockerClientProviderStrategy`,
который и отправляет запрос с API version 1.32.

```
                     Version Catalog          Spring Boot BOM
                     ───────────────          ───────────────
testcontainers-      → 2.0.2 ✓               (не знает, нет в BOM)
  postgresql

testcontainers       → (transitive)           → 1.19.x ✗ ← вот проблема!
  (core)                                        Spring BOM побеждает
```

**Pricing Service не пострадал** потому что это Ktor-приложение **без** плагина
`spring-dependency-management`. Версии резолвились корректно из version catalog.

**Решение:** Явно импортировать TC BOM в блоке `dependencyManagement` каждого Spring Boot сервиса:

```kotlin
// services/transfer-service/build.gradle.kts
dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:...")
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

// services/outbox-service/build.gradle.kts
dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}
```

Это гарантирует, что **все** TC артефакты (включая core `testcontainers`) резолвятся как 2.0.2,
а не переопределяются Spring Boot BOM.

> **Примечание:** Использование `platform(libs.testcontainers.bom)` в блоке `dependencies`
> (Gradle-native BOM) **не помогает**, потому что `spring-dependency-management` плагин имеет
> приоритет при разрешении версий. Нужно использовать именно блок `dependencyManagement`.

---

### Шаг 4. Kafka: переход на EmbeddedKafka

**Проблема:** После исправления шагов 1-3, Transfer Service и Pricing Service заработали,
но Outbox Service продолжал падать — теперь уже с другой ошибкой:

```
ContainerLaunchException: Timed out waiting for log output matching
  '.*Transitioning from RECOVERY to RUNNING.*'

There are no stdout/stderr logs available for the failed container
```

Kafka контейнер (пробовали как `ConfluentKafkaContainer` с `confluentinc/cp-kafka:7.6.0`,
так и `KafkaContainer` с `apache/kafka:3.8.0`) стартовал на GitHub Actions runner, но
**не выводил никаких логов** в stdout/stderr. Контейнер создавался, Docker image пуллился
успешно, но после запуска — тишина. Через 3 минуты (увеличенный таймаут) TC объявлял
таймаут, так как не дождался ожидаемого лог-сообщения.

Контейнеры PostgreSQL, MongoDB, Redis при этом стартовали и работали без проблем
на тех же runners.

**Решение:** Заменить Testcontainers Kafka на `@EmbeddedKafka` из `spring-kafka-test`.

`@EmbeddedKafka` запускает брокер Kafka **в том же JVM процессе**, без Docker.
Это стандартный подход для интеграционных тестов Spring Kafka:

```kotlin
// Было:
@Testcontainers
abstract class IntegrationTestBase {
    companion object {
        val kafka = KafkaContainer("apache/kafka:3.8.0").apply { start() }

        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }
}

// Стало:
@Testcontainers
@EmbeddedKafka(partitions = 1, topics = ["transfer.events"])
abstract class IntegrationTestBase {
    companion object {
        // Kafka конфигурируется автоматически через @EmbeddedKafka
        // spring.kafka.bootstrap-servers устанавливается Spring'ом
    }
}
```

Из зависимостей Outbox Service был убран `testcontainers-kafka` (больше не нужен),
а `spring-kafka-test` остался (он уже был).

---

### Шаг 5. Исправление сериализации в Pricing Service

**Проблема:** После того как Docker заработал и тесты Pricing Service впервые прошли,
обнаружился **скрытый баг** (существовавший до всех наших изменений, но ранее не
проявлявшийся, т.к. тесты вообще не запускались из-за Docker ошибки):

```
HealthRouteTest > corridors endpoint returns mock data() FAILED
    io.kotest.assertions.AssertionFailedError at HealthRouteTest.kt:36
```

**Причина:** Эндпоинт `GET /api/v1/corridors` использовал `call.respond()` с объектом
`Map<String, Any>`:

```kotlin
call.respond(HttpStatusCode.OK, mapOf(
    "corridors" to listOf(
        mapOf("source_country" to "GB", "active" to true, ...)
    )
))
```

Ktor использует `kotlinx.serialization` для Content Negotiation. Но `kotlinx.serialization`
**не может сериализовать `Map<String, Any>`**, потому что для типа `Any` нет сериализатора.
Это приводило к 500 Internal Server Error вместо 200 OK.

**Решение:** Заменить `call.respond()` с `Map` на `call.respondText()` с JSON, построенным
через `buildJsonObject` из `kotlinx.serialization`:

```kotlin
val body = buildJsonObject {
    putJsonArray("corridors") {
        addJsonObject {
            put("source_country", "GB")
            put("destination_country", "PL")
            put("send_currency", "GBP")
            put("receive_currency", "PLN")
            putJsonArray("delivery_methods") { add("BANK_TRANSFER") }
            put("active", true)
        }
    }
}
call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.OK)
```

---

## 4. Затронутые файлы

| Файл | Изменение |
|---|---|
| `gradle/libs.versions.toml` | Версия TC 2.0.2 + новые имена артефактов |
| `services/transfer-service/build.gradle.kts` | TC BOM в `dependencyManagement` |
| `services/outbox-service/build.gradle.kts` | TC BOM в `dependencyManagement`, убран `testcontainers-kafka` |
| `services/transfer-service/.../IntegrationTestBase.kt` | Новый import пакет, убран `<*>` |
| `services/outbox-service/.../IntegrationTestBase.kt` | `@EmbeddedKafka` вместо TC Kafka |
| `services/pricing-service/.../IntegrationTestBase.kt` | Новый import пакет для `MongoDBContainer` |
| `services/pricing-service/.../plugins/Routing.kt` | `buildJsonObject` + `respondText` |
| `.github/workflows/ci.yml` | TC env vars (DOCKER_HOST, RYUK_DISABLED) |

---

## 5. Уроки

1. **Docker API версии — breaking change.** Docker Engine 29 убрал поддержку API < 1.44.
   Это ломает любые Docker-клиенты (не только Testcontainers), использующие старые версии API.

2. **Spring Boot BOM может переопределять ваши версии.** Даже если в version catalog
   указана версия 2.x для конкретного модуля, `spring-dependency-management` плагин
   может подтянуть core-зависимость из Spring Boot BOM как 1.x. Всегда проверяйте
   `./gradlew dependencies` при обновлении библиотек в Spring Boot проектах.

3. **Testcontainers Kafka на CI ненадёжен.** Kafka контейнеры (как Confluent, так и Apache)
   могут молча падать на GitHub Actions runners без каких-либо логов. `@EmbeddedKafka` —
   надёжная альтернатива для Spring Kafka тестов, не зависящая от Docker.

4. **Тесты могут скрывать баги.** Если тесты не запускаются (из-за инфраструктурных проблем),
   баги в production коде остаются незамеченными. Починка тестовой инфраструктуры может
   выявить ранее скрытые проблемы (как баг с kotlinx.serialization в Pricing Service).
