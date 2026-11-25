package com.dpsmpx.dpai.cli

/** Недоверенный текст задачи не исполняет OSC/CSI в терминале ПК (Fable:O-11). JSON печатается без изменений. */
internal object ConsoleText {
    fun visible(text: String): String = buildString {
        for (c in text) {
            if ((c.isISOControl() && c != '\n' && c != '\t') || Character.getType(c) == Character.FORMAT.toInt())
                append("\\u" + c.code.toString(16).padStart(4, '0'))
            else append(c)
        }
    }
}
