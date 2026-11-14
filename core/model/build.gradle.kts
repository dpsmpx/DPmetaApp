// Типы предметной области DPmetaApp (ТЗ, §6.2, §6.3): ссылки на репозитории,
// наблюдения, уровни действий. Ядро dpai/ — ID, время, хеши, JSON, Outcome;
// RE2/J — безопасные пользовательские regex (D-141); Android-типов нет (D-03).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api("com.dpsmpx.dpai:core")
    // Пользовательские выражения без backtracking; тип движка не раскрывается в API.
    implementation(libs.re2j)
}
