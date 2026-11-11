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
    private val files = ConcurrentHashMap<String, FileDraft>()

    class FileDraft(val original: ByteArray, val editor: com.dpsmpx.dpai.ui.CodeEditorBuffer)

    fun file(key: String): FileDraft? = files[key]

    /** Черновик не вытесняется: девятый файл можно начать после сохранения/отмены одного из восьми. */
    @Synchronized
    fun startFile(key: String, original: ByteArray, text: String): FileDraft? {
        files[key]?.let { return it }
        if (files.size >= 8) return null
        return FileDraft(original, com.dpsmpx.dpai.ui.CodeEditorBuffer(text)).also { files[key] = it }
    }

    fun dropFile(key: String) { files.remove(key) }

    fun get(key: String): String? = map[key]

    /** Пустой или `null` — черновика нет. */
    fun set(key: String, text: String?) {
        if (text.isNullOrEmpty()) map.remove(key) else map[key] = text
    }

    fun chat(session: String): String = "chat:$session"
}
