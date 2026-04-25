plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.zero.link.shizuku.server"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 基础协程与工具
    implementation(project(":lib-common"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    
    // JSON 序列化
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
