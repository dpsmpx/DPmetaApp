plugins {
    `kotlin-dsl`
}

dependencies {
    // Плагины, которые применяют convention-плагины. Версии — из
    // gradle/libs.versions.toml (один источник для сборки и проверок).
    // Android Gradle Plugin и плагин Compose — здесь же (D-102): с одного
    // classpath с Kotlin плагин Kotlin загружается один раз. AGP берётся из
    // Google Maven, а где он закрыт — из build_tools автора
    // (DPmetaApp:tools/build_tools_install.py).
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.compose.gradle.plugin)
    implementation(libs.android.gradle.plugin)
}
