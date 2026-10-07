// Модули DP_AI_Orcestrator. Граница между ними — граница ответственности
// (docs/ARCHITECTURE.md, §2): зависимости между модулями проверяет
// компилятор, а допустимые направления — tools/verify/check_modules.py.
pluginManagement {
    includeBuild("build-logic")
    repositories {
        // Google Maven — только для групп Android (D-34, T11): ничего
        // другого оттуда не приходит.
        google {
            content {
                includeGroupByRegex("""com\.android(\..*)?""")
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.google\.(android|testing)\..*""")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("""com\.android(\..*)?""")
                includeGroupByRegex("""androidx\..*""")
                includeGroupByRegex("""com\.google\.(android|testing)\..*""")
            }
        }
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

// Модуль android (оболочка приложения, D-34) — только при найденном
// Android SDK, как в DPpass (docs/ARCHITECTURE.md, §2): без SDK ядро
// собирается и проверяется как прежде.
val androidSdk = run {
    val local = file("local.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.replace("""\:""", ":")?.replace("""\\""", "/")
    (local ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.takeIf { file(it).isDirectory }
}
if (androidSdk != null) include(":android")
