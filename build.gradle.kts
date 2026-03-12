plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.mgmt) apply false
}

allprojects {
    group = "com.swiftpay"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("org.jetbrains.kotlin.jvm")) {
            apply(plugin = "jacoco")

            tasks.withType<JacocoReport> {
                reports {
                    xml.required.set(true)
                    html.required.set(true)
                }
            }

            tasks.withType<Test> {
                finalizedBy(tasks.withType<JacocoReport>())
            }
        }
    }
}
