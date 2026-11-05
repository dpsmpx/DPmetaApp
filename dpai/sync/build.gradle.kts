// Облачная синхронизация (ТЗ, §4.1; D-107): зашифрованная на устройстве копия
// состояния контура в приватном репозитории GitHub — шифр, архив с
// оглавлением, восстановление при запуске, клиент Git Data API. Ядро без
// облака работает (облако — не обязательная часть).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":storage"))
    api(project(":providers"))
    testImplementation(project(":testkit"))
}
