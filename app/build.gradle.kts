// Пульт DPmetaApp (этап 3; ТЗ, §6.2, §9): Android-библиотека — экраны на Compose,
// модели экранов на чистом Kotlin (снимки — тестами на JVM), хранилище с Keystore,
// блокировка (F-45). Приложение одно (D-27, D-49): библиотеку подключает оболочка
// dpai/android отдельным шагом; своего applicationId у пульта нет.
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dpsmpx.dpmeta.app"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        compose = true
    }

    testOptions.unitTests.all {
        it.useJUnitPlatform()
        // Снимки экранов на JVM: состояние — синхронизацией синтетической экосистемы корпуса.
        val corpus = rootProject.layout.projectDirectory.dir("tests/corpus/ecosystem")
        it.inputs.dir(corpus).withPropertyName("corpus")
        it.systemProperty("corpus.ecosystem", corpus.asFile.absolutePath)
        it.systemProperty("snapshots.dir", layout.projectDirectory.dir("src/test/snapshots").asFile.absolutePath)
        it.inputs.dir(layout.projectDirectory.dir("src/test/snapshots")).withPropertyName("snapshots")
    }

    // Предупреждения Lint — ошибки (CLAUDE.md, «Правила кода»).
    lint {
        warningsAsErrors = true
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core:sync"))
    implementation(project(":core:metrics"))
    implementation("com.dpsmpx.dpai:ui")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
