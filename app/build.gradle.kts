plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.fold7.validation"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.fold7.validation"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-independent"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
}

dependencyLocking { lockAllConfigurations() }
