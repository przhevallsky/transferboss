# @Order на @RestControllerAdvice — когда нужна, когда нет

## Что делает @Order

`@Order(Ordered.HIGHEST_PRECEDENCE)` задаёт приоритет выполнения **между несколькими** `@RestControllerAdvice` классами. Чем ниже число — тем выше приоритет (`HIGHEST_PRECEDENCE = Integer.MIN_VALUE`).

## Когда НЕ нужна

Если в проекте **один** `@RestControllerAdvice` — аннотация бесполезна, конкурировать не с кем.

Также `@Order` **не влияет** на приоритет между `@RestControllerAdvice` и встроенными Spring-обработчиками (`DefaultHandlerExceptionResolver`). Это разные механизмы — `@ExceptionHandler` методы в `@RestControllerAdvice` всегда имеют приоритет над дефолтными resolver'ами Spring.

## Когда НУЖНА

Когда есть **несколько** `@RestControllerAdvice` классов, и важно контролировать порядок:

```kotlin
@RestControllerAdvice
@Order(1)
class SecurityExceptionHandler {
    @ExceptionHandler(AccessDeniedException::class)
    fun handle(...) { ... }
}

@RestControllerAdvice
@Order(2)
class GlobalExceptionHandler {
    @ExceptionHandler(Exception::class)  // catch-all
    fun handle(...) { ... }
}
```

Без `@Order` Spring может вызвать catch-all из `GlobalExceptionHandler` раньше, чем специфичный `SecurityExceptionHandler`, и `AccessDeniedException` вернёт 500 вместо 403.

## Наш случай (TransferBoss)

В `GlobalExceptionHandler` стоит `@Order(Ordered.HIGHEST_PRECEDENCE)`, но у нас один handler — аннотация избыточна. Оставлена как перестраховка на случай появления второго handler'а в будущем.