package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Процессы на машине, где работает этот код: на контроллере (`cli`) или на
 * target под агентом (D-03, D-26). Остановка убирает всё дерево
 * ([ProcessTree]); вход процесса закрывается сразу после [ProcessSpec.stdin],
 * чтобы процесс не ждал ввода вечно.
 */
class LocalProcessHost(private val clock: Clock = Clock.SYSTEM) : ProcessHost {
    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        val builder = ProcessBuilder(spec.command)
        spec.workDir?.let { builder.directory(File(it)) }
        spec.environment?.let { env ->
            val target = builder.environment()
            target.clear()
            target.putAll(env)
        }
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return Outcome.Failure(
                listOf(Diagnostic.fromThrowable(DiagCode.PROCESS_START_FAILED, "${spec.program}: не запустился", e)),
            )
        }
        try {
            process.outputStream.use { out -> spec.stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        } catch (_: IOException) {
            // Процесс закрыл вход сам: это не отказ запуска, а его поведение — оно видно по выводу и коду.
        }
        val readers = listOf(
            reader(process.inputStream, OutputStream.STDOUT, onOutput),
            reader(process.errorStream, OutputStream.STDERR, onOutput),
        )
        return Outcome.success(Local(process, readers, clock))
    }

    private class Local(private val process: Process, private val readers: List<Thread>, private val clock: Clock) : RunningProcess {
        override val pid: Long? = runCatching { process.pid() }.getOrNull()

        override val isAlive: Boolean get() = process.isAlive

        override fun awaitExit(timeout: Duration): Int? {
            if (!process.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)) return null
            drain()
            return process.exitValue()
        }

        override fun stop(grace: Duration): StopReport {
            val r = ProcessTree.stop(process.toHandle(), grace, clock)
            drain()
            return StopReport(if (process.isAlive) null else process.exitValue(), r.forced, r.survivors)
        }

        /** Вывод дочитывается до конца, но не дольше секунды: поток может держать открытым живой внук. */
        private fun drain() = readers.forEach { it.join(READER_DRAIN.inWholeMilliseconds) }
    }

    companion object {
        /** Предел длины строки вывода: длиннее — обрезается с отметкой (память, ТЗ, §116). */
        const val MAX_LINE: Int = 64 * 1024

        private val READER_DRAIN = 1.seconds

        private fun reader(input: InputStream, stream: OutputStream, onOutput: (OutputLine) -> Unit): Thread =
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
                        } else if (buf.length < MAX_LINE) {
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
}
