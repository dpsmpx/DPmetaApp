// Оболочка приложения на JVM: те же модули, что у Android, без UI.
plugins {
    id("dppass.kotlin-application")
}

dependencies {
    implementation(project(":connection"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.dpsmpx.dppass.cli.MainKt")
    applicationName = "dppass-cli"
}
