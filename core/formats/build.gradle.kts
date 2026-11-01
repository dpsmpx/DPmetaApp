// Разборщики форматов экосистемы (ТЗ, §6.2, §10): подмножество TOML, реестр
// DPmeta, журналы и списки процессов. У каждого — синтетический корпус
// «ловит / пропускает»; итог — типы :core:model и JSON ядра dpai/.
plugins {
    id("dpai.kotlin-library")
}

dependencies {
    api(project(":core:model"))
}
