// Общие описания и вызовы нынешних инструментов (D-215), без своего worker.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":verification"))
    implementation(project(":storage"))
    testImplementation(project(":testkit"))
}
