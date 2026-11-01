// Типы предметной области DPmetaApp (ТЗ, §6.2, §6.3): ссылки на репозитории,
// наблюдения, уровни действий. Только ядро dpai/ — ID, время, хеши, JSON,
// Outcome; Android-типов нет (D-03).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api("com.dpsmpx.dpai:core")
}
