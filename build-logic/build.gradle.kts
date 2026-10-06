plugins {
    `kotlin-dsl`
}

dependencies {
    // Плагины, которые применяют convention-плагины. Версии — из
    // gradle/libs.versions.toml (один источник для сборки и проверок).
    // Android Gradle Plugin здесь нет: модуль android подключается только
    // при Android SDK и сам объявляет плагин (docs/ARCHITECTURE.md, §2).
    implementation(libs.kotlin.gradle.plugin)
}
