// Сохранённое состояние: атомарная запись, журнал событий, снимки,
// миграции, резервные копии, обнаружение повреждений (этап 2).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core"))
    testImplementation(project(":testkit"))
}
