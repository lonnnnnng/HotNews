plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.juhe.hotnews"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.juhe.hotnews"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("VERSION_CODE").orElse("2").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orElse("0.1.1").get()
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storePath = providers.gradleProperty("HOTNEWS_STORE_FILE").orNull
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("HOTNEWS_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("HOTNEWS_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("HOTNEWS_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
