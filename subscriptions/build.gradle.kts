// Подписки как версионированные объекты: загрузка, целостность,
// разбор, нормализация, фильтрация серверов.
plugins {
    id("dppass.kotlin-library")
}

dependencies {
    api(project(":config"))
}

dependencies {
    testImplementation(libs.kotlinx.coroutines.core)
}
