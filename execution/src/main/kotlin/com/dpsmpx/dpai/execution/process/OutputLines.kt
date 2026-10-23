package com.dpsmpx.dpai.execution.process

import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Вывод процесса строками — общий для [LocalProcessHost] и
 * `NativeProcessHost` (Linux-среда в приложении, D-61): строка длиннее
 * [LocalProcessHost.MAX_LINE] обрезается с отметкой, `\r` в конце снимается.
 * Без `ProcessHandle` — исполняется и на телефоне.
 */
object OutputLines {
    /**
     * Сколько дочитывать вывод после выхода процесса: поток может держать
     * открытым живой внук. Секунды не хватало (ревью A-8): на телефоне каждая
     * строка проходит правку и запись в журнал.
     */
    val READER_DRAIN: Duration = 10.seconds

    /** Поток чтения строк [input] в [onOutput]; уже запущен. */
    fun reader(input: InputStream, stream: OutputStream, onOutput: (OutputLine) -> Unit): Thread =
        Thread {
            input.bufferedReader(Charsets.UTF_8).use { r ->
                val buf = StringBuilder()
                var truncated = false
                while (true) {
                    val c = try {
                        r.read()
                    } catch (_: IOException) {
                        -1
                    }
                    if (c == -1 || c == '\n'.code) {
                        if (c == -1 && buf.isEmpty() && !truncated) break
                        onOutput(OutputLine(stream, buf.toString().removeSuffix("\r"), truncated))
                        buf.setLength(0)
                        truncated = false
                        if (c == -1) break
                    } else if (buf.length < LocalProcessHost.MAX_LINE) {
                        buf.append(c.toChar())
                    } else {
                        truncated = true
                    }
                }
            }
        }.apply {
            name = "dpai-process-${stream.name.lowercase()}"
            isDaemon = true
            start()
        }
}
