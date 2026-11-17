// Приложение Android (D-102, D-103; DPmetaApp:D-53): единый APK DPmetaApp::shell.
// Плагины — с classpath build-logic, как Kotlin. Здесь же — подпись пакета
// `com.dpsmpx.dpai` прежним ключом выпуска для установки поверх старых сборок,
// упаковка библиотек Linux-среды, байт-код Java 17. Личность пакета и версия — в модуле.
import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

extensions.configure<ApplicationExtension> {
    // Ключ выпуска — у автора вне репозитория (docs/PROCESS.md, §6): путь и пароли — свойства
    // dpai.release.* в ~/.gradle/gradle.properties или ORG_GRADLE_PROJECT_*; относительный путь — от
    // каталога Gradle пользователя, одинаково для обеих сборок. Нет свойств — release подписан
    // отладочным ключом (для замеров на устройстве, docs/TESTING.md, §9), не для раздачи.
    val releaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("dpai.release.$it").orNull }
    if (releaseKey.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = File(releaseKey.getValue("storeFile")!!).let { if (it.isAbsolute) it else File(gradle.gradleUserHomeDir, it.path) }
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
            // Схема v3 (ТЗ, T-10; обзор угроз 15 ноября 2026): с ней ключ можно сменить ротацией, не теряя
            // обновлений; v2 — для проверки установщиком. Ручная подпись (apksigner) и ApkSign — тоже v3.
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        // proot оболочки «AI» — из каталога библиотек, распакованным на диск: запуск из каталога данных
        // при targetSdk 29+ запрещён (docs/research/LINUX_IN_APP.md, §1).
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}
