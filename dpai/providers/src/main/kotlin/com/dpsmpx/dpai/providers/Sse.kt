package com.dpsmpx.dpai.providers

import java.io.IOException

/** Событие Server-Sent Events: имя (`event:`) и данные (строки `data:` через перевод строки). */
data class SseEvent(val event: String?, val data: String)

/**
 * Разбор потока SSE (WHATWG, «Server-sent events»): поля `event` и
 * `data`, комментарий `:`, пустая строка — конец события; `id` и `retry`
 * не нужны — повтора с места нет. Последнее событие без пустой строки в
 * конце тоже отдаётся: так заканчивают поток некоторые совместимые
 * серверы.
 */
object Sse {
    /** Читает события из [next], пока [onEvent] возвращает `true` и поток не кончился. */
    @Throws(IOException::class)
    fun read(next: () -> String?, onEvent: (SseEvent) -> Boolean) {
        var event: String? = null
        val data = StringBuilder()
        var hasData = false
        fun dispatch(): Boolean {
            if (!hasData) {
                event = null
                return true
            }
            val e = SseEvent(event, data.toString())
            event = null
            data.setLength(0)
            hasData = false
            return onEvent(e)
        }
        while (true) {
            val line = next() ?: break
            if (line.isEmpty()) {
                if (!dispatch()) return
                continue
            }
            if (line.startsWith(":")) continue
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
            when (field) {
                "event" -> event = value
                "data" -> {
                    if (hasData) data.append('\n')
                    data.append(value)
                    hasData = true
                }
            }
        }
        dispatch()
    }
}
