package com.dpsmpx.dpmeta.notify

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.model.Event

/**
 * Уведомления других приложений (F-57; D-155): из уведомления пакета белого списка —
 * событие `app.notification` (пакет, заголовок, текст) для тех же правил. Всё вне
 * списка, свои уведомления, постоянные и сводки групп — `null` сразу: не сохраняется
 * и не попадает в журнал (ТЗ, T-16). Android здесь нет — признаки приходят готовыми.
 */
object AppNotifications {
    const val TYPE = "app.notification"

    /** Приложение Claude — в белом списке по умолчанию (ответ автора, D-155). */
    const val CLAUDE = "com.anthropic.claude"
    val DEFAULT_PACKAGES: List<String> = listOf(CLAUDE)

    const val MAX_TITLE = 200
    const val MAX_TEXT = 500
    const val MAX_PACKAGES = 50

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    fun packageValid(name: String): Boolean = name.length <= 200 && PACKAGE.matches(name)

    /**
     * Событие из уведомления или `null`. [key] — ключ уведомления системы: новое
     * уведомление с тем же ключом заменяет прежнее, а не множит карточки.
     */
    fun event(
        pkg: String,
        title: String?,
        text: String?,
        atMillis: Long,
        key: String,
        allowed: Set<String>,
        own: String,
        ongoing: Boolean,
        groupSummary: Boolean,
    ): Event? {
        if (pkg == own || pkg !in allowed || ongoing || groupSummary) return null
        val t = clean(title, MAX_TITLE)
        val body = clean(text, MAX_TEXT)
        if (t.isEmpty() && body.isEmpty()) return null
        return Event(
            TYPE, Timestamp(atMillis), null, body.ifEmpty { t },
            fields = buildMap { put("package", pkg); if (t.isNotEmpty()) put("title", t) },
            key = "$pkg:${key.hashCode().toUInt().toString(16)}",
        )
    }

    /** Список пакетов из ввода (по строке или через запятую): неверное имя — в [Packages.bad]. */
    data class Packages(val ok: List<String>, val bad: List<String>)

    fun packages(text: String): Packages {
        val items = text.split('\n', ',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val (ok, bad) = items.partition(::packageValid)
        return Packages(ok.take(MAX_PACKAGES), bad)
    }

    /** Без управляющих знаков и лишних пробелов, не длиннее [max]. */
    private fun clean(s: String?, max: Int): String =
        s.orEmpty().map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(max)
}
