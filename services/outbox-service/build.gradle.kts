plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.testcontainers:testcontainers-bom:${libs.versions.testcontainers.get()}")
    }
}

dependencies {
    // Spring
    implementation(libs.spring.boot.starter.web)         // Health endpoints
    implementation(libs.spring.boot.starter.data.jpa)     // Reading outbox table
    implementation(libs.spring.boot.starter.actuator)

    // Kafka
    implementation(libs.spring.kafka)

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // Database (read-only access to outbox table — same DB as Transfer Service)
    runtimeOnly(libs.postgresql.driver)
    // Flyway NOT needed — migrations are managed by Transfer Service.
    // Outbox Service only reads/updates the outbox table.

    // Observability
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logback.logstash.encoder)

    // Tests
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "org.mockito")
    }
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.spring.kafka.test)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
