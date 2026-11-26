// Провайдеры моделей по ключу (D-98): AIProvider, сеть со списком адресов, переписка чата.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":accounts"))
    api(project(":contexts"))
    testImplementation(project(":testkit"))
}
