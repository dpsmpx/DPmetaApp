// Граница ядра: ProxyEngine, BackendCompiler, матрица возможностей;
// фиктивный бэкенд для доказательства абстракции.
plugins {
    id("dppass.kotlin-library")
}

dependencies {
    api(project(":config"))
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.core)
}
