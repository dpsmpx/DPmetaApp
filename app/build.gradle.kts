// Пульт DPmetaApp (этап 3; ТЗ, §6.2, §9): Android-библиотека — экраны на Compose,
// модели экранов на чистом Kotlin (снимки — тестами на JVM), хранилище с Keystore,
// блокировка (F-45). Приложение одно (D-27, D-49): APK собирает модуль корня :shell
// (D-53); своего applicationId у пульта нет. Плагины — convention-плагин dpai/build-logic.
plugins {
    id("dpai.android-library")
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
        // Регрессия шаблонов уведомлений проверяет именно данные, включаемые в APK.
        it.systemProperty("corpus.repo", rootProject.layout.projectDirectory.asFile.absolutePath)
        for (name in listOf("events", "alerts")) {
            it.inputs.file(rootProject.layout.projectDirectory.file("config/$name.toml")).withPropertyName("config:$name")
        }
    }

    // Предупреждения Lint — ошибки (CLAUDE.md, «Правила кода»).
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Подавления — с причиной в самом файле (JGit, D-65).
        lintConfig = file("lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Панели проектов (F-10, D-57), шаблоны поручений (F-23), реестр команд (F-40, D-82), каталог
// событий и правила тревог (F-37, F-51; D-85) — только config/projects/*.toml, config/briefs/*.md,
// config/commands.toml, config/events.toml и config/alerts.toml корня, в ресурсах APK —
// assets/projects/<Проект>.toml, assets/briefs/<ключ>.md, assets/commands.toml, assets/events.toml
// и assets/alerts.toml; другие файлы config/ в APK не попадают.
val panelAssets = tasks.register<Sync>("panelAssets") {
    from(rootProject.layout.projectDirectory.dir("config/projects")) {
        include("*.toml")
        into("projects")
    }
    from(rootProject.layout.projectDirectory.dir("config/briefs")) {
        include("*.md")
        into("briefs")
    }
    from(rootProject.layout.projectDirectory.file("config/commands.toml"))
    from(rootProject.layout.projectDirectory.file("config/events.toml"))
    from(rootProject.layout.projectDirectory.file("config/alerts.toml"))
    into(layout.buildDirectory.dir("generated/panel-assets"))
}
androidComponents {
    onVariants { v ->
        v.sources.assets?.addStaticSourceDirectory(layout.buildDirectory.dir("generated/panel-assets").get().asFile.path)
    }
}
// Каталог читают слияние ресурсов и lint — зависят от задачи явно (как linuxFiles в dpai/android).
tasks.matching {
    it.name != panelAssets.name && ((it.name.startsWith("merge") || it.name.startsWith("package")) && it.name.endsWith("Assets") || it.name.contains("lint", ignoreCase = true))
}.configureEach { dependsOn(panelAssets) }

kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core:sync"))
    implementation(project(":core:metrics"))
    implementation(project(":core:actions"))
    implementation(project(":core:notify"))
    implementation("com.dpsmpx.dpai:ui")
    implementation("com.dpsmpx.dpai:notifications")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    // FileProvider — «Поделиться» экспортом (D-67).
    implementation(libs.androidx.core)
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
