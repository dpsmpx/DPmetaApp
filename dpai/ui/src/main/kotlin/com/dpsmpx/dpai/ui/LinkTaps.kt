package com.dpsmpx.dpai.ui

/**
 * Касание внешней ссылки — во всём приложении одинаково (DPmetaApp:D-75):
 * первое копирует адрес и показывает его целиком; второе касание той же
 * ссылки не позже [againMs] открывает её в браузере телефона — только
 * `https`; `http`, `intent:`, `javascript:`, `file:` и прочие схемы не
 * открываются: адрес остаётся скопированным, на экране — почему. Время —
 * монотонное, от вызывающего (`Clock`, D-07).
 */
class LinkTaps(private val againMs: Long = AGAIN_MS) {
    /** Что сделать оболочке: скопировать и сказать, открыть или сказать, почему нет. */
    sealed interface Act {
        val url: String

        data class Copy(override val url: String, val text: String) : Act

        data class Open(override val url: String) : Act

        data class Refuse(override val url: String, val text: String) : Act
    }

    private var last: String? = null
    private var at = 0L

    fun tap(url: String, nowMs: Long): Act {
        if (url == last && nowMs - at in 0..againMs) {
            last = null
            return if (openable(url)) Act.Open(url) else Act.Refuse(url, "Открывается только https — адрес скопирован")
        }
        last = url
        at = nowMs
        return Act.Copy(url, "Скопировано: $url — коснитесь ещё раз, чтобы открыть")
    }

    companion object {
        /** Сколько ждать второго касания: дольше — снова первое (копия). */
        const val AGAIN_MS = 10_000L

        /** Открывается только `https` с хостом (T-04). */
        fun openable(url: String): Boolean {
            val u = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
            return u.scheme.equals("https", ignoreCase = true) && !u.host.isNullOrEmpty()
        }
    }
}
