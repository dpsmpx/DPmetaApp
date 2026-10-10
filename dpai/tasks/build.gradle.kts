// Задачи, очередь, приоритеты, DAG зависимостей, политики, планировщик (этап 7).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":security"))
    api(project(":accounts"))
    api(project(":execution"))
    testImplementation(project(":testkit"))
}
