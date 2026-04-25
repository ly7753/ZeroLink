plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.zero.link.controlled"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zero.link.controlled"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":lib-common"))
    implementation(project(":core-domain"))
    implementation(project(":core-infrastructure"))
    implementation(project(":core-shizuku-server"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

// 跨端默认签名配置 (Cross-Platform Default Signature)
android {
    signingConfigs {
        create("crossPlatform") {
            storeFile = rootProject.file("keystore/zerolink.jks")
            storePassword = "zerolink"
            keyAlias = "zerolink"
            keyPassword = "zerolink"
        }
    }
    buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("crossPlatform")
    buildTypes.getByName("release").signingConfig = signingConfigs.getByName("crossPlatform")
}
