// Android-приложение DPpass: оболочка над DPpassCore (D-02, D-17).
// Подключается, только если найден Android SDK (settings.gradle.kts).
plugins {
    id("dppass.android-app")
}

android {
    namespace = "com.dpsmpx.dppass"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // applicationId фиксируется окончательно перед первым выпуском (D-01,
        // отложенное решение «название»): после выпуска его не сменить.
        applicationId = "com.dpsmpx.dppass"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-dev"
    }

    buildTypes {
        release {
            // Подпись и R8 — к этапу выпуска (ROADMAP, этап 5); до него
            // релизная сборка не публикуется (CLAUDE.md, «Чего не делать»).
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Модули ядра — тоже под проверкой уровней API Android (minSdk 26).
        checkDependencies = true
        // Новые версии зависимостей проверяются по D-18, а не lint’ом из сети.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":connection"))
    implementation(libs.kotlinx.coroutines.core)
}
