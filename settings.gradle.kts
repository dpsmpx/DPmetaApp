// Сборка DPmetaApp из корня (D-28, D-29): подсистема «AI» и ядро — включённая
// сборка dpai/; модули DPmetaApp — core/ корня (ТЗ, §6.2) на ядре dpai/,
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
    // Плагины Android и Compose — convention-плагины dpai/build-logic (DP_AI_Orcestrator:D-102):
    // с одного classpath с Kotlin, без своих версий здесь.
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

// Нужен и при подстановке Android-библиотеки включённой сборки: без SDK
// dpai/ не объявляет :android, поэтому ссылаться на него нельзя.
val androidSdk = run {
    val local = file("local.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")?.replace("""\:""", ":")?.replace("""\\""", "/")
    listOf(local, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT")).firstOrNull { !it.isNullOrBlank() }?.takeIf { file(it).isDirectory }
}

// Ядро dpai/ — по координатам com.dpsmpx.dpai:<модуль>: зависимость модуля корня
// заменяется проектом включённой сборки. Подсистема «AI» от модулей корня не
// зависит (DP_AI_Orcestrator:D-55) — замен в обратную сторону нет.
includeBuild("dpai") {
    dependencySubstitution {
        // ui — чистый Kotlin подсистемы «AI»: пульт берёт оттуда разбор Markdown (D-53: «AI» — часть приложения).
        for (m in listOf("core", "storage", "security", "notifications", "testkit", "ui")) {
            substitute(module("com.dpsmpx.dpai:$m")).using(project(":$m"))
        }
        // Оболочка «AI» — библиотека (DP_AI_Orcestrator:D-103): её берёт приложение корня :shell (D-53).
        if (androidSdk != null) substitute(module("com.dpsmpx.dpai:android")).using(project(":android"))
    }
}

// Модули DPmetaApp (ТЗ, §6.2) — появляются вместе со своим первым кодом.
include(":core:model")
include(":core:formats")
include(":core:net")
include(":core:github")
include(":core:metrics")
include(":core:sync")
include(":core:git")
include(":core:actions")
include(":core:notify")
include(":tests:corpus")

// Пульт DPmetaApp (этап 3): Android-библиотека :app — экраны на Compose, хранилище с
// Keystore, блокировка; приложение :shell (D-53) — один APK: пульт первым экраном,
// оболочка «AI» вкладкой. Только при найденном Android SDK, как оболочка dpai/android:
// без SDK модули корня собираются и проверяются как прежде.
if (androidSdk != null) include(":app", ":shell")
