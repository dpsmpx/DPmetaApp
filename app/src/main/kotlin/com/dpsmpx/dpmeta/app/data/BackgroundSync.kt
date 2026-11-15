package com.dpsmpx.dpmeta.app.data

/**
 * Фоновое обновление пульта (D-149, частично заменяет D-18) — без Android: интервал,
 * выбор клонов и строка итога. Задачу ставит [com.dpsmpx.dpmeta.app.PultSyncJob].
 */
object BackgroundSync {
    /** Интервалы в «Ещё», минуты: 0 — выключено; система не даёт чаще 15. */
    val INTERVALS: List<Int> = listOf(0, 15, 30, 60)
    const val DEFAULT = 30

    /** Сохранённый интервал или по умолчанию; чужое число — по умолчанию. */
    fun interval(stored: Int?): Int = stored?.takeIf { it in INTERVALS } ?: DEFAULT

    fun title(minutes: Int): String = if (minutes == 0) "Выключено" else "$minutes мин"

    /**
     * Клоны для фонового запуска: новые клоны создаёт только «Обновить» под службой
     * переднего плана (D-78) — если какого-то отмеченного ещё нет, клоны не трогаются.
     */
    fun clones(selected: Set<String>, missing: Boolean): Set<String> = if (missing) emptySet() else selected

    /** Итог фонового запуска для «Ещё». */
    sealed interface Result {
        val text: String

        data class Done(val line: String) : Result { override val text get() = "готово: $line" }
        data object TokenLocked : Result { override val text get() = "пропуск: токен закрыт — телефон давно не разблокировали" }
        data object NoToken : Result { override val text get() = "пропуск: нет токена GitHub" }
        data object Busy : Result { override val text get() = "пропуск: синхронизация уже идёт" }
        data class Failed(val why: String) : Result { override val text get() = "не вышло: $why" }
    }

    /** Строка «Ещё»: время запуска (по поясу показа) и итог. */
    fun line(at: String, result: String): String = "Последний фоновый запуск: $at — $result"
}
