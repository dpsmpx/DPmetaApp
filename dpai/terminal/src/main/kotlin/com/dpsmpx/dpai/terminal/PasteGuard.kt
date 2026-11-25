package com.dpsmpx.dpai.terminal

/**
 * Когда вставка в терминал — с вопросом (аудит UI/UX 5 ноября 2026,
 * терминал:A-7; как `paste_actions confirm` у kitty и предупреждение Windows
 * Terminal): несколько строк, а программа не включила вставку в скобках
 * (2004), — каждая строка выполнится сразу; или текст больше [ASK_CHARS].
 */
object PasteGuard {
    /** Порог большой вставки, знаков (16 КиБ, как у kitty). */
    const val ASK_CHARS = 16 * 1024

    fun needsConfirm(text: String, bracketed: Boolean): Boolean =
        (!bracketed && lines(text) > 1) || text.length > ASK_CHARS

    /** Сколько строк уйдёт: последний перевод строки в конце — тоже строка (Enter). */
    fun lines(text: String): Int = text.replace("\r\n", "\n").count { it == '\n' || it == '\r' } + 1
    /** Скрытые управляющие знаки в вопросе OSC 52 видны, сам буфер не изменяется (Fable:N-05). */
    fun visible(text: String, limit: Int): String = buildString {
        require(limit >= 0)
        for (c in text.take(limit)) {
            if ((c.isISOControl() && c != '\n' && c != '\t') || Character.getType(c) == Character.FORMAT.toInt())
                append("\\u" + c.code.toString(16).padStart(4, '0'))
            else append(c)
        }
    }

}
