// Сеть DPmetaApp (ТЗ, §6.8; D-10): HTTP с условными запросами (ETag), бюджет
// запросов, список разрешённых адресов. Транспорт — java.net.http из JDK, без
// сторонних библиотек; Android-типов нет (D-03).
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api("com.dpsmpx.dpai:core")
}
