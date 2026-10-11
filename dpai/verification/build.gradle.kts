// Профили проверки FAST/NORMAL/FULL, доказательства, адаптер DP-Verify (этап 10).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":execution"))
    api(project(":git"))
    testImplementation(project(":testkit"))
}
