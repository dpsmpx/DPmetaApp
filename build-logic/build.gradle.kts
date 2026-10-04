plugins {
    `kotlin-dsl`
}

dependencies {
    // Плагины, которые применяют convention-плагины. Версии — из
    // gradle/libs.versions.toml (один источник для сборки и проверок).
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)
}
