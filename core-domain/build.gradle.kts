plugins {
    id("java-library")
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
}

dependencies {
    implementation(project(":lib-common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
