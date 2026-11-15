package com.dpsmpx.dpmeta.formats

/**
 * Облачная сессия Claude Code в тексте (F-58; D-161): ссылка
 * `https://claude.ai/code/session_…`. Claude Code в облаке ставит её в описание PR,
 * который открывает сессия; по ней пульт узнаёт PR своего запуска маршрута, а по ссылке
 * из ответа запуска — саму сессию. Только вид `session_` и латиница с цифрами: другое
 * не угадывается.
 */
object SessionLink {
    private val LINK = Regex("https://claude\\.ai/code/(session_[A-Za-z0-9]{8,80})(?![A-Za-z0-9_])")

    /** Id первой сессии в [text]; нет ссылки — `null`. */
    fun of(text: String?): String? = text?.let { LINK.find(it)?.groupValues?.get(1) }
}
