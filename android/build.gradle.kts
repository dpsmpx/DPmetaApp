// Оболочка Android (D-02, D-34): Compose рисует состояния экранов из ui;
// логики здесь нет. Подключается только при Android SDK
// (settings.gradle.kts). Плагин Android объявляет сам модуль, а не
// build-logic: без Google Maven ядро собирается (docs/ARCHITECTURE.md, §2).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dpsmpx.dpai.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dpsmpx.dpai"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    buildTypes {
        release {
            // Ключа выпуска пока нет: release подписан отладочным ключом —
            // только для замеров кадров на устройстве (docs/TESTING.md, §9).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":jsch"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}
