package com.dpsmpx.dpai.ui

/**
 * Один путь закреплённых загрузок установщиков (DPmetaApp:D-193):
 * проверенный архив переживает сбой распаковки и повторную установку.
 * Скрипт не содержит секретов; адреса/суммы передаются обычными argv оболочки.
 */
object ComponentDownloads {
    val shell: String = checkNotNull(javaClass.getResourceAsStream("/com/dpsmpx/dpai/ui/component_cache.sh")) {
        "ресурс кэша компонентов отсутствует"
    }.bufferedReader().use { it.readText() }
}
