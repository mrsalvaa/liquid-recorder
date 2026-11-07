// SPDX-License-Identifier: GPL-3.0-or-later
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Lógica sin Android: codificadores WAV y FLAC, FFT y espectrograma, medidor de nivel, picos de la
// onda, marcadores, cortes y nombres. Se prueba en la JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
}

// Si el sistema tiene `flac` y `ffprobe`, las pruebas los usan para validar los archivos generados.
tasks.test {
    systemProperty("liquid.tmp", layout.buildDirectory.dir("test-audio").get().asFile.absolutePath)
    // Herramienta manual: ./gradlew :core:test -Pliquid.clean=archivo.f32 (ver CleanPipelineTest).
    for (key in listOf("liquid.clean", "liquid.extra")) {
        (project.findProperty(key) as String?)?.let {
            systemProperty(key, it)
            outputs.upToDateWhen { false }
        }
    }
    testLogging { showStandardStreams = project.hasProperty("liquid.clean") }
}
