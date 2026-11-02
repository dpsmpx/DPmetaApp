// Выводимое состояние и метрики DPmetaApp (ТЗ, §6.3, §8; D-34): из наблюдений
// синхронизации — состояние проектов, «кто работает», вопросы автору и точки
// истории чисел. Только :core:model и ядро dpai/ (хранение — dpai/storage).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core:model"))
    api("com.dpsmpx.dpai:storage")
}
