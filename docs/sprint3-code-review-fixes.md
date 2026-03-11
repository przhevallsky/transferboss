# Sprint 3 — Code Review Fixes

Код ревью Sprint 3 выявило 8 проблем в Go notification-gateway и Kotlin transfer-service. Ниже — что было исправлено и почему.

---

## 1. [CRITICAL] DLT handler не логирует причину ошибки

**Файлы:** `PaymentEventConsumer.kt`, `PayoutEventConsumer.kt`

**Проблема:** `@DltHandler` принимал только `message` и `topic`. Когда сообщение попадало в Dead Letter Topic, в логах не было информации о причине — невозможно диагностировать почему event провалил все retry.

**Было:**
```kotlin
@DltHandler
fun handleDlt(message: String, @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String) {
    log.error("Payment event sent to DLT: topic={}, message={}", topic, message)
}
```

**Стало:**
```kotlin
@DltHandler
fun handleDlt(
    message: String,
    @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
    @Header(KafkaHeaders.EXCEPTION_MESSAGE) exceptionMsg: String?
) {
    log.error("Payment event sent to DLT: topic={}, exception={}, message={}", topic, exceptionMsg, message)
}
```

**Почему:** Spring Kafka записывает exception message в заголовок `kafka_exception-message`. Без этого параметра DLT — чёрная дыра: видно что event провалился, но не видно почему. Параметр nullable (`String?`) на случай если заголовок отсутствует.

---

## 2. [CRITICAL] Go handler всегда возвращает nil — ошибки доставки теряются

**Файл:** `internal/handler/handler.go`

**Проблема:** `DeliveryHandler.Handle()` всегда возвращал `nil`, даже если все каналы доставки упали. Consumer коммитил offset → сообщение потеряно навсегда.

**Было:**
```go
// после цикла по каналам
return nil
```

**Стало:**
```go
var (
    attempted int
    failed    int
    errs      []error
)
// ... в цикле считаем attempted и failed ...

if attempted > 0 && failed == attempted {
    return fmt.Errorf("all %d delivery channels failed: %w", failed, errors.Join(errs...))
}
return nil
```

**Почему:** Partial success — OK по спеке (push упал, но SMS дошёл — сообщение доставлено). Но если ВСЕ каналы упали — это полный провал, consumer не должен коммитить offset. Теперь error возвращается только при total failure, что позволяет consumer'у повторить обработку.

**Тесты:** Добавлены `TestDeliveryHandler_AllChannelsFail_ReturnsError` и `TestDeliveryHandler_SingleChannelFails_ReturnsError`. Существующий тест partial failure переименован в `TestDeliveryHandler_PartialFailure_ReturnsNil`.

---

## 3. [MEDIUM] paymentId/payoutId UUID.fromString без валидации

**Файлы:** `PaymentEventConsumer.kt`, `PayoutEventConsumer.kt`

**Проблема:** `UUID.fromString(event.paymentId)` и `UUID.fromString(event.payoutId)` вызывались внутри транзакции без try-catch. Если внешний сервис прислал невалидный UUID — `IllegalArgumentException` пробрасывался наверх, Spring Kafka считал это retriable ошибкой и повторял бесконечно (до DLT).

**Было:**
```kotlin
if (event.paymentId != null) {
    transfer.paymentId = UUID.fromString(event.paymentId)
}
```

**Стало:**
```kotlin
if (event.paymentId != null) {
    transfer.paymentId = try {
        UUID.fromString(event.paymentId)
    } catch (e: IllegalArgumentException) {
        throw NonRetriableConsumerException("Invalid paymentId format: ${event.paymentId}", e)
    }
}
```

**Почему:** Невалидный UUID — это проблема данных, а не транзиентная ошибка. Retry не поможет. `NonRetriableConsumerException` исключён из retry через `exclude = [NonRetriableConsumerException::class]` в `@RetryableTopic`, поэтому сообщение сразу уйдёт в DLT без бесполезных повторов.

---

## 4. [MEDIUM] Dockerfile Go version mismatch

**Файл:** `services/notification-gateway/Dockerfile`

**Проблема:** Dockerfile использовал `golang:1.22-alpine`, а `go.mod` — `go 1.23.0`. Сборка работала благодаря forward compatibility, но это несоответствие могло привести к тонким багам если код использует фичи Go 1.23.

**Было:** `FROM golang:1.22-alpine AS builder`
**Стало:** `FROM golang:1.23-alpine AS builder`

**Почему:** Build-образ должен соответствовать версии в go.mod. Go 1.23 добавил новые stdlib функции и изменения в runtime — лучше собирать тем же тулчейном что указан в go.mod.

---

## 5. [MEDIUM] log.Fatal() в горутинах HTTP серверов

**Файл:** `cmd/main.go`

**Проблема:** `log.Fatal()` вызывает `os.Exit(1)` напрямую, минуя defer'ы и graceful shutdown. Если health или metrics HTTP сервер падал (например, порт занят), процесс убивался без вызова `consumer.Close()` — Kafka consumer не коммитил offsets, не отправлял LeaveGroup.

**Было:**
```go
go func() {
    if err := healthSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
        log.Fatal().Err(err).Msg("health HTTP server failed")
    }
}()
```

**Стало:**
```go
serverErrCh := make(chan error, 2)

go func() {
    if err := healthSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
        log.Error().Err(err).Msg("health HTTP server failed")
        serverErrCh <- err
    }
}()

// main goroutine:
select {
case <-ctx.Done():
    log.Info().Msg("shutdown signal received, draining...")
case err := <-serverErrCh:
    log.Error().Err(err).Msg("HTTP server error, initiating shutdown...")
    stop()
}
```

**Почему:** Ошибка HTTP сервера теперь проходит через тот же graceful shutdown путь что и SIGTERM — consumer корректно закрывается, offsets коммитятся, LeaveGroup отправляется. Буфер канала 2 — по одному на каждый HTTP сервер.

---

## 6. [LOW] Отсутствует .dockerignore

**Файл:** `services/notification-gateway/.dockerignore` (новый)

**Проблема:** Без `.dockerignore` инструкция `COPY . .` в Dockerfile копировала в build context всё: тесты, `bin/`, `.git`, `Makefile` и т.д. Это увеличивало размер context'а и время сборки.

**Содержимое:**
```
bin/
*.test
.git
.gitignore
.dockerignore
Dockerfile
Makefile
README.md
```

---

## 7. [LOW] Makefile — отсутствует clean target

**Файл:** `services/notification-gateway/Makefile`

**Добавлено:**
```makefile
clean:
	rm -rf bin/
```

**Почему:** Стандартная практика — возможность очистить артефакты сборки одной командой.

---

## 8. [LOW] notification.delivery topic в неправильной секции create-topics.sh

**Файл:** `infra/docker/kafka/init/create-topics.sh`

**Проблема:** Топик `notification.delivery` был добавлен в секцию "Retry and Dead Letter Topics", хотя это основной рабочий топик, потребляемый Notification Gateway.

**Было:**
```bash
# Retry and Dead Letter Topics
create_topic "notification.delivery"  6  $SEVEN_DAYS_MS
create_topic "transfers.notification.retry" ...
```

**Стало:**
```bash
create_topic "identity.user.blocked"  3  $SEVEN_DAYS_MS

# Topics consumed by Notification Gateway
create_topic "notification.delivery"  6  $SEVEN_DAYS_MS

# Retry and Dead Letter Topics
create_topic "transfers.notification.retry" ...
```

**Почему:** Организация скрипта должна отражать архитектуру. `notification.delivery` — это production topic с event'ами для доставки, а не retry/DLT инфраструктура.

---

## Verification

- `go build ./...` — OK
- `go vet ./...` — OK
- `go test ./...` — 6/6 passed (handler: 6, sender: 2)
- `./gradlew :services:transfer-service:test` — BUILD SUCCESSFUL
