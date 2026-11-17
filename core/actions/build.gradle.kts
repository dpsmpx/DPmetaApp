// Действия приложения с уровнями (ТЗ, §6.5; D-07, D-36, D-37): каталог действий,
// политика подтверждений и журнал действий. Только :core:model и ядро dpai/ —
// одобрения dpai/security, журнал dpai/storage; исполнители — у оболочек (:app).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core:model"))
    api("com.dpsmpx.dpai:storage")
    api("com.dpsmpx.dpai:security")
}
