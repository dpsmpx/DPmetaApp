// LocalAIService (ТЗ, §77, §78; D-105): подсказки локальной модели — сводка,
// порядок контекста, классы строк журнала, отклонения, сводка уведомлений,
// категория задачи. Только модели на этом устройстве или в частной сети;
// ядро и планировщик от модели не зависят.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    api(project(":providers"))
    testImplementation(project(":testkit"))
}
