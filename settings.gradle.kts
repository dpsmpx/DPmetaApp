// Сборка DPmetaApp из корня (D-28, D-29): подсистема «AI» и ядро — включённая
// сборка dpai/; модули DPmetaApp — core/ и cli/ корня (ТЗ, §6.2) на ядре dpai/,
// с его convention-плагинами и каталогом версий — второго набора нет.
pluginManagement {
    includeBuild("dpai/build-logic")
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
