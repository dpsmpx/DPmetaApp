package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.model.Place

/**
 * Компактная карта D-139. Состав берётся из реестра, роли — из принятого
 * docs/direction/PROJECT_ROLES.md. Список клонов сюда не добавляется.
 * Это назначение проектов, а не вывод о готовности функций или их сборке в APK.
 */
object ProjectMap {
    /** Основные места реестра по умолчанию; периферия доступна отдельным выбором. */
    fun groups(source: List<Screens.Group>, includePeriphery: Boolean): List<Screens.Group> =
        source.filter { it.place != Place.ARCHIVE && (includePeriphery || it.place != Place.PERIPHERY) }

    fun role(name: String): String = when (name) {
        "DPmetaApp" -> "Мобильная студия разработки с ИИ"
        "DP_AI_Orcestrator", "dpai" -> "Ядро DPmetaApp: ИИ-сессии, исполнение, рабочие деревья"
        "DPmeta" -> "Карта основных проектов, контракты и связи студии"
        "DP-Verify" -> "Самостоятельный upstream → общая папка инструментов на target"
        "DPpass" -> "Канал к домашнему ПК; мост приложений в плане"
        "DPvisualisation" -> "Полезные данные и простые графики для студии; отбор в работе"
        "Galdhold" -> "Первый сквозной проект для разработки и приёмки IDE"
        "DPlang", "DPlang IDE" -> "Самостоятельная разработка; интеграция после готовности языка"
        "SocialMediaImage" -> "Отдельная студия контента и управления сайтом; свой репозиторий"
        "DPprofile" -> "Компонент SocialMediaImage"
        "DPlearn" -> "Учебный проект; отдельное мобильное приложение в плане"
        "DP_game_market_analytic_system" -> "Самостоятельная аналитика рынка игр; низкий приоритет"
        "DP_Clock" -> "Самостоятельный проект; DP_Repeated_Tasks входит в него"
        "DP_Repeated_Tasks" -> "Алгоритм DP_Clock"
        "DPmonetization" -> "Будущая часть SocialMediaImage"
        else -> "Проект из реестра DPmeta"
    }
}
