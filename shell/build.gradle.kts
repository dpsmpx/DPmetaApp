// Приложение DPmetaApp — своя оболочка (D-35), один APK (D-53, D-54): пульт :app первым экраном, оболочка «AI»
// (dpai/android, библиотека — DP_AI_Orcestrator:D-103) вкладкой. Личность пакета прежняя —
// com.dpsmpx.dpai (D-49): APK ставится поверх отдельной сборки «AI», данные и ключи Keystore
// сохраняются. Плагины — convention-плагин dpai/build-logic (DP_AI_Orcestrator:D-102).
// Подключается только при Android SDK (settings.gradle.kts).
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dpai.android-application")
}

/**
 * `versionCode` из версии DPmetaApp (D-54): `0.N.P-dev.M` → N·10000 + P·100 + M, выпуск `0.N.P` →
 * N·10000 + P·100 + 99. Растёт с этапами и сборками и всегда больше, чем у отдельной сборки «AI».
 */
fun versionCodeOf(version: String): Int {
    val m = Regex("""0\.(\d+)\.(\d+)(?:-dev\.(\d+))?""").matchEntire(version)
        ?: throw GradleException("версия $version — не 0.N.P или 0.N.P-dev.M (D-54)")
    val (stage, patch, dev) = m.destructured
    val build = if (dev.isEmpty()) 99 else dev.toInt().also { require(it in 1..98) { "dev.$it: номер сборки этапа — 1…98 (D-54)" } }
    return stage.toInt() * 10_000 + patch.toInt() * 100 + build
}

android {
    namespace = "com.dpsmpx.dpmeta.shell"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dpsmpx.dpai"
        minSdk = 30
        targetSdk = 36
        versionName = project.version.toString()
        versionCode = versionCodeOf(versionName!!)
    }

    // Ключ выпуска — тот же, что у отдельной сборки «AI» (dpai/android-app): иначе APK не встанет поверх.
    // Свойства dpai.release.* — у автора вне репозитория; нет их — release подписан отладочным ключом.
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

    // Предупреждения Lint — ошибки (CLAUDE.md, «Правила кода»). Код библиотек проверяет Lint своих
    // модулей по правилам их подсистем (пульт — :app, «AI» — dpai/android); здесь — модуль и
    // слитый манифест.
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        // targetSdk 36 — как у отдельной сборки «AI» (DP_AI_Orcestrator: docs/PROVENANCE.md, §2):
        // поднимается вместе с ней, иначе одно приложение вело бы себя по-разному в двух сборках.
        disable += "OldTargetApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // proot оболочки «AI» — из каталога библиотек, распакованным на диск
        // (DP_AI_Orcestrator: docs/research/LINUX_IN_APP.md, §1).
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":app"))
    implementation("com.dpsmpx.dpai:android")
    // DpmetaActivity наследует пульт — ComponentActivity нужна и на classpath компиляции.
    implementation(libs.activity.compose)
}
