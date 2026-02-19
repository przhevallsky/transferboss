import com.google.protobuf.gradle.*

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
    application
}

application {
    mainClass.set("com.transferhub.pricing.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

// ── Protobuf Code Generation ──────────────────────────────────────────────────
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.asProvider().get()}"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpc.kotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}

dependencies {
    // ── Ktor Server ──────────────────────────────────────────────────
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.metrics.micrometer)

    // ── Serialization ────────────────────────────────────────────────
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)

    // ── gRPC ─────────────────────────────────────────────────────────
    implementation(libs.grpc.netty)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin)

    // ── Kotlin Coroutines ────────────────────────────────────────────
    implementation(libs.kotlinx.coroutines.core)

    // ── MongoDB (Kotlin Coroutine Driver) ────────────────────────────
    implementation(libs.mongodb.driver.kotlin.coroutine)

    // ── Redis (Lettuce) ──────────────────────────────────────────────
    implementation(libs.lettuce.core)
    implementation(libs.kotlinx.coroutines.reactor)

    // ── Observability ────────────────────────────────────────────────
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.logback.logstash.encoder)

    // ── Testing ──────────────────────────────────────────────────────
    testImplementation(libs.grpc.testing)
    testImplementation(libs.grpc.inprocess)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.mongodb)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
