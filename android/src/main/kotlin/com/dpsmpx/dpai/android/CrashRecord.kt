package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File

/**
 * Последний сбой приложения — в `files/last-crash.txt`: на части
 * устройств (Realme C21Y, D-33) системный журнал выключен, и без этой
 * записи причину падения не узнать. Только локальный файл через `Vfs`
 * (D-24): наружу ничего не уходит. Записываются тип, сообщение и кадры
 * стека — имена классов, не данные пользователя; обработчик системы
 * вызывается после записи как прежде.
 */
object CrashRecord {
    const val FILE = "last-crash.txt"

    /** Запись падения в журнал приложения; задаёт экран при запуске. */
    @Volatile
    var onFatal: ((String) -> Unit)? = null

    fun install(dir: File) {
        val vfs = NioVfs(dir.toPath())
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { vfs.write(FILE, describe(thread.name, error).toByteArray()) }
            // И в журнал приложения — сразу и всегда (D-43): последняя строка файла — падение и этап запуска.
            runCatching { onFatal?.invoke("${error.javaClass.name}: ${error.message}") }
            previous?.uncaughtException(thread, error)
        }
    }

    fun describe(thread: String, error: Throwable): String = buildString {
        appendLine("поток: $thread")
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 8) {
            appendLine((if (depth == 0) "" else "причина: ") + "${e.javaClass.name}: ${e.message}")
            e.stackTrace.take(40).forEach { appendLine("\tat $it") }
            e = e.cause?.takeIf { it !== e }
            depth++
        }
    }
}
