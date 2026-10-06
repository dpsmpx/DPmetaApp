// Модули DP_AI_Orcestrator. Граница между ними — граница ответственности
// (docs/ARCHITECTURE.md, §2): зависимости между модулями проверяет
// компилятор, а допустимые направления — tools/verify/check_modules.py.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "DP_AI_Orcestrator"

// Ядро: чистый Kotlin/JVM, собирается и проверяется без Android SDK (D-02).
include(
    ":core",
    ":testkit",
    ":storage",
    ":security",
    ":execution",
    ":accounts",
    ":git",
    ":contexts",
    ":tasks",
    ":sessions",
    ":verification",
    ":monitoring",
    ":notifications",
    ":ecosystem",
    ":connectors",
    ":analytics",
    ":orchestrator",
    ":ui",
    ":cli",
)

// Межмодульные тесты.
include(":tests:integration")

// Модуль android (оболочка приложения) подключается на этапе 11, когда
// появится интерфейс, и только при найденном Android SDK — как в DPpass
// (docs/ARCHITECTURE.md, §2). До этого его здесь нет: непроверяемой
// сборки в репозитории не держим.
