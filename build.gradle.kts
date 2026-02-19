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
