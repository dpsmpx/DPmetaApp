// Сборка DPmetaApp из корня (D-28, D-29): подсистема «AI» и ядро — включённая
// сборка dpai/; модули DPmetaApp — core/ и cli/ корня (ТЗ, §6.2) на ядре dpai/,
// с его convention-плагинами и каталогом версий — второго набора нет.
pluginManagement {
    includeBuild("dpai/build-logic")
    repositories {
        // Google Maven — только для групп Android (как в dpai/settings.gradle.kts).
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
    // Версия AGP — из каталога dpai/ (один каталог, D-28): плагин библиотеки :app.
    val agp = file("dpai/gradle/libs.versions.toml").readLines()
        .first { it.startsWith("agp = ") }.substringAfter("\"").substringBefore("\"")
    plugins {
        id("com.android.library") version agp
    }
    // Плагин библиотеки — из того же модуля AGP, что и плагин приложения: отдельный
    // маркер не нужен (набор build_tools облачной сессии держит модуль, а не маркер).
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:$agp")
        }
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
    versionCatalogs {
        create("libs") { from(files("dpai/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "DPmetaApp"

// Ядро dpai/ — по координатам com.dpsmpx.dpai:<модуль>: зависимость модуля корня
// заменяется проектом включённой сборки. Подсистема «AI» от модулей корня не
// зависит (DP_AI_Orcestrator:D-55) — замен в обратную сторону нет.
includeBuild("dpai") {
    dependencySubstitution {
        for (m in listOf("core", "storage", "security", "notifications", "testkit")) {
            substitute(module("com.dpsmpx.dpai:$m")).using(project(":$m"))
        }
    }
}

// Модули DPmetaApp (ТЗ, §6.2) — появляются вместе со своим первым кодом.
include(":core:model")
include(":core:formats")
include(":core:net")
include(":core:github")
include(":core:metrics")
include(":core:sync")
include(":cli")
include(":tests:corpus")

// Пульт DPmetaApp (этап 3): Android-библиотека :app — экраны на Compose, хранилище с
// Keystore, блокировка. Только при найденном Android SDK, как оболочка dpai/android:
// без SDK модули корня собираются и проверяются как прежде. В оболочку подключается
// отдельным шагом (docs/ARCHITECTURE.md, «Пульт»).
val androidSdk = run {
    val local = file("local.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.replace("""\:""", ":")?.replace("""\\""", "/")
    listOf(local, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).firstOrNull { !it.isNullOrBlank() }?.takeIf { file(it).isDirectory }
}
if (androidSdk != null) include(":app")
