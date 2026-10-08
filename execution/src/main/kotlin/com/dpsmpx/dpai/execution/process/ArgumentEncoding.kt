package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.nio.charset.Charset

/**
 * Аргументы процесса и кодировка платформы (D-05: смысл не меняется молча).
 *
 * На POSIX JVM кодирует аргументы командной строки в `sun.jnu.encoding` —
 * кодировку локали. При локали без UTF-8 (`POSIX`, `C`: так бывает в
 * контейнерах и у процессов, запущенных по SSH без `LANG`) символ, которого
 * в ней нет, молча становится `?`: кириллица в сообщении коммита,
 * `--append-system-prompt` или пути `--add-dir` меняет смысл, а процесс
 * запускается как ни в чём не бывало. Поэтому такой аргумент — отказ запуска
 * с подсказкой, а не искажённая команда. Промпт идёт через вход процесса в
 * UTF-8 и от локали не зависит.
 *
 * На Windows аргументы уходят в `CreateProcessW` строкой UTF-16 и проверка
 * не нужна.
 */
object ArgumentEncoding {
    /** Кодировка, в которой JVM передаёт аргументы процессам; `null` — проверка не нужна (Windows). */
    val platform: Charset? = if (Detacher.isWindows()) null else jnuCharset()

    fun check(command: List<String>, charset: Charset? = platform): Outcome<Unit> {
        if (charset == null) return Outcome.success(Unit)
        val encoder = charset.newEncoder()
        val bad = command.indexOfFirst { !encoder.canEncode(it) }
        if (bad < 0) return Outcome.success(Unit)
        return Outcome.failure(
            DiagCode.PROCESS_ARGUMENT_UNENCODABLE,
            "аргумент №$bad не выражается в кодировке ${charset.name()} (локаль без UTF-8): " +
                "запустить с LANG=C.UTF-8 или LC_ALL=C.UTF-8",
            command.firstOrNull(),
        )
    }

    private fun jnuCharset(): Charset =
        System.getProperty("sun.jnu.encoding")?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charset.defaultCharset()
}
