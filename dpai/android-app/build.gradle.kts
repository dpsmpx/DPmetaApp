// Отдельная сборка подсистемы «AI» (DPmetaApp:D-35, DPmetaApp:D-53; D-103): тонкое
// приложение над библиотекой `android` — личность пакета и версия; подпись и упаковка —
// convention-плагин dpai.android-application, общий с приложением DPmetaApp.
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
