package com.dpsmpx.dpai.android

import java.util.concurrent.ConcurrentHashMap

/**
 * Начатое и не сохранённое — черновик сообщения сессии и правка файла —
 * переживает смену вкладки и сессии (аудит UI/UX 5 ноября 2026, AI:A-2,
 * AI:A-13). Держится в памяти процесса, а не в сохранённом состоянии окна:
 * правка файла бывает до 2 МиБ, а сохранённое состояние Android столько не
 * несёт. Смерть процесса черновик не переживает — как и вкладки терминала
 * (D-27). В журнал и на диск не пишется.
 */
object Drafts {
    private val map = ConcurrentHashMap<String, String>()

    fun get(key: String): String? = map[key]

    /** Пустой или `null` — черновика нет. */
    fun set(key: String, text: String?) {
        if (text.isNullOrEmpty()) map.remove(key) else map[key] = text
    }

    fun chat(session: String): String = "chat:$session"
}
