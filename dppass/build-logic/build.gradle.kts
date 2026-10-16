plugins {
    `kotlin-dsl`
}

dependencies {
    // Плагины, которые применяют convention-плагины. Версии — из
    // gradle/libs.versions.toml (один источник для сборки и проверок).
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle.plugin)

    // Логика сборки с собственными тестами (IdentityTest, D-47): запускаются
    // там, где доступен Android-инструментарий (как и шаг Android hostcheck).
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach { useJUnitPlatform() }
