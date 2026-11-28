package com.dpsmpx.dpai.execution.process

import java.io.IOException
import java.io.InputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Вывод процесса строками — общий для [LocalProcessHost] и
 * `NativeProcessHost` и JSch (D-75, аудит O-04): строка длиннее
 * [LocalProcessHost.MAX_LINE] обрезается с отметкой, CR/LF — границы строк.
 * Без `ProcessHandle` — исполняется и на телефоне.
 */
object OutputLines {
    /** Wrapper печатает «.....10%» без CR/LF; это не JSON и не произвольный текст. */
    private val WRAPPER_METER = Regex("^\\.+(?:100|[0-9]{1,2})%$")
    /**
     * Сколько дочитывать вывод после выхода процесса: поток может держать
     * открытым живой внук. Секунды не хватало (ревью A-8): на телефоне каждая
     * строка проходит правку и запись в журнал.
     */
    val READER_DRAIN: Duration = 10.seconds

    /** Поток чтения строк [input] в [onOutput]; уже запущен. */
    fun reader(input: InputStream, stream: OutputStream, onOutput: (OutputLine) -> Unit): Thread =
        Thread { read(input, stream, onOutput) }.apply {
            name = "dpai-process-${stream.name.lowercase()}"
            isDaemon = true
            start()
        }

    /**
     * Читать с пределом во время накопления. CR публикуется сразу: curl/apk
     * обновляют прогресс без LF. CRLF остаётся одной строкой (DPmetaApp:D-193).
     */
    fun read(input: InputStream, stream: OutputStream, onOutput: (OutputLine) -> Unit) {
        input.bufferedReader(Charsets.UTF_8).use { r ->
            val buf = StringBuilder()
            var truncated = false
            var cr = false
            while (true) {
                val c = try { r.read() } catch (_: IOException) { -1 }
                if (c == '\n'.code && cr) { cr = false; continue }
                if (c == -1 || c == '\n'.code || c == '\r'.code) {
                    if (c == -1 && buf.isEmpty() && !truncated) break
                    if (c != '\r'.code || buf.isNotEmpty() || truncated) onOutput(OutputLine(stream, buf.toString(), truncated))
                    cr = c == '\r'.code
                    buf.setLength(0)
                    truncated = false
                    if (c == -1) break
                } else {
                    cr = false
                    if (buf.length < LocalProcessHost.MAX_LINE) buf.append(c.toChar()) else truncated = true
                    if (c == '%'.code && !truncated && buf.length <= 128 && buf.firstOrNull() == '.' && WRAPPER_METER.matches(buf)) {
                        onOutput(OutputLine(stream, buf.toString()))
                        buf.setLength(0)
                        cr = true
                    }
                }
            }
        }
    }

    /** Вход пишется параллельно чтению: большой prompt не блокирует start и его тайм-аут (O-17). */
    fun writer(output: java.io.OutputStream, text: String?): Thread = Thread({
        try {
            output.use { out -> text?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        } catch (_: IOException) {
            // Процесс сам закрыл вход или остановлен: итог виден по выводу и коду выхода.
        }
    }, "dpai-process-stdin").apply { isDaemon = true; start() }
}
