plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
}

dependencies {
    // 基础协程支持
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // JSON 序列化
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
