package com.dpsmpx.dpmeta.app.ui

/**
 * Ссылки справки внутрь приложения (D-112): `app:<раздел>` и
 * `app:settings?q=<поиск>`. Список разделов один — для экрана и для теста
 * справки: ссылка на несуществующий раздел не дойдёт до сборки.
 */
object HelpLinks {
    const val SCHEME = "app:"
    const val SUMMARY = "summary"
    const val PROJECTS = "projects"
    const val IDE = "ide"
    const val AI = "ai"
    const val SETTINGS = "settings"

    /** Окно знакомства (D-150). */
    const val INTRO = "intro"

    /** Мастер нового проекта (D-150). */
    const val NEW_PROJECT = "new-project"

    val SECTIONS: Set<String> = setOf(SUMMARY, PROJECTS, IDE, AI, SETTINGS, INTRO, NEW_PROJECT)

    /** Раздел ссылки; не `app:` или неизвестный раздел — `null`. */
    fun section(url: String): String? = url.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.substringBefore('?')?.takeIf { it in SECTIONS }

    /** Поиск для документа настроек: `app:settings?q=токен` → `токен`; нет — пусто. */
    fun query(url: String): String = url.substringAfter("?q=", "")
}
