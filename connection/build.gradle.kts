// Активация с last-known-good и адаптивная политика подключения.
plugins {
    id("dppass.kotlin-library")
}

dependencies {
    api(project(":engine"))
    api(project(":network"))
    api(project(":storage"))
    api(project(":subscriptions"))
    api(project(":tunnel"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
