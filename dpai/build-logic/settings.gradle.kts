// Сборка convention-плагинов DP_AI_Orcestrator: общие правила модулей в одном месте.
dependencyResolutionManagement {
    repositories {
        // Google Maven — только для групп Android (как в settings.gradle.kts): AGP (D-102).
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
