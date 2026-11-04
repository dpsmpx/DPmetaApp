// Отдельная сборка подсистемы «AI» (DPmetaApp:D-35, DPmetaApp:D-53; D-103): тонкое
// приложение над библиотекой `android` — личность пакета, версия, подпись и упаковка.
// Экраны, службы, нативный код и вложения — в `android`. Приложение DPmetaApp из корня
// берёт ту же библиотеку и ставится поверх этой сборки (DPmetaApp:D-54).
// Подключается только при Android SDK (settings.gradle.kts).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dpai.android-application")
}

android {
    namespace = "com.dpsmpx.dpai.androidapp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dpsmpx.dpai"
        minSdk = 30
        targetSdk = 36
        versionCode = 10
        versionName = project.version.toString()
    }

    // Ключ выпуска — у автора вне репозитория (docs/PROCESS.md, §6): путь и
    // пароли — свойства dpai.release.* в ~/.gradle/gradle.properties или
    // ORG_GRADLE_PROJECT_*. Нет их — release подписан отладочным ключом (для
    // замеров на устройстве, docs/TESTING.md, §9), не для раздачи.
    val releaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .associateWith { providers.gradleProperty("dpai.release.$it").orNull }
    if (releaseKey.values.all { it != null }) {
        signingConfigs.create("release") {
            storeFile = file(releaseKey.getValue("storeFile")!!)
            storePassword = releaseKey.getValue("storePassword")
            keyAlias = releaseKey.getValue("keyAlias")
            keyPassword = releaseKey.getValue("keyPassword")
        }
    }

    buildTypes {
        release {
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
        // proot — из каталога библиотек, распакованным на диск: запуск из каталога данных при
        // targetSdk 29+ запрещён (docs/research/LINUX_IN_APP.md, §1).
        jniLibs.useLegacyPackaging = true
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
    implementation(project(":android"))
}
