// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Clave de release fuera del repo. Ruta configurable con -PsigningProps=... o la variable
// de entorno LIQUID_RECORDER_SIGNING; si no existe, la release se firma con la clave de debug.
val signingProps = (findProperty("signingProps") as String?)
    ?: System.getenv("LIQUID_RECORDER_SIGNING")
    ?: "${System.getProperty("user.home")}/.android-keys/liquid-recorder.properties"
val releaseSigning = file(signingProps).takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "dev.liquidrecorder"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.liquidrecorder"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.2"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        // Como la release (optimizada) pero instalable junto a ella como dev.liquidrecorder.debug.
        create("profile") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-profile"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkDependencies = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    // F-Droid: sin el bloque de dependencias firmado por Google dentro del APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.backdrop)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
}

// Sin perfiles ART dentro del APK: sin profileinstaller no se usan fuera de Google Play, y F-Droid
// los desaconseja porque pueden hacer que el build no sea reproducible.
tasks.matching { it.name.contains("ArtProfile") }.configureEach { enabled = false }
