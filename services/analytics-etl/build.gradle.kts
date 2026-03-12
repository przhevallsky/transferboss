plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.mgmt)
}

dependencies {
    // Spring
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)

    // Kafka
    implementation(libs.spring.kafka)

    // Kotlin
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    // JDBC + ClickHouse
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.clickhouse:clickhouse-jdbc:0.6.0:all")

    // Observability
    implementation(libs.micrometer.prometheus)
    implementation(libs.logstash.logback)

    // Tests
    testImplementation(libs.spring.boot.starter.test) {
        exclude(group = "org.mockito")
    }
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.spring.kafka.test)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
