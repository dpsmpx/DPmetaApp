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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Агент для Termux-target — вложение APK (инструкция «Настройка» сохраняет его в «Загрузки»):
// свежий `dpai` из :cli и обёртка Claude Code для proot — версия агента всегда та же, что у приложения.
val packAgent by tasks.registering(Zip::class) {
    dependsOn(":cli:installDist")
    from(rootProject.file("cli/build/install/dpai")) { into("dpai") }
    from(rootProject.file("tools/termux/claude-proot.sh")) { into("dpai") }
    archiveFileName.set("dpai-agent.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/dpai-setup-assets"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
// addGeneratedSourceDirectory у ресурсов assets задачу в граф не включал (AGP, 9 октября 2026) — каталог и
// зависимость слияния ресурсов заданы явно.
androidComponents {
    onVariants { v -> v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/dpai-setup-assets").get().asFile.path) }
}
// Каталог читают слияние ресурсов и lint (generate…LintReportModel и др.) — все зависят от упаковки явно.
tasks.matching { it.name != packAgent.name && (it.name.startsWith("merge") && it.name.endsWith("Assets") || "Lint" in it.name) }
    .configureEach { dependsOn(packAgent) }

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
