package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Процессы на машине, где работает этот код: на контроллере (`cli`) или на
 * target под агентом (D-03, D-26). Остановка убирает всё дерево
 * ([ProcessTree]); вход процесса закрывается сразу после [ProcessSpec.stdin],
 * чтобы процесс не ждал ввода вечно.
 */
class LocalProcessHost(private val clock: Clock = Clock.SYSTEM) : ProcessHost {
    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        val encodable = ArgumentEncoding.check(spec.command)
        if (encodable is Outcome.Failure) return encodable
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
        val readers = listOf(
            OutputLines.reader(process.inputStream, OutputStream.STDOUT, onOutput),
            OutputLines.reader(process.errorStream, OutputStream.STDERR, onOutput),
            OutputLines.writer(process.outputStream, spec.stdin),
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
            // Супервизор — лидер своего сеанса: и фоновые потомки хода (A-9). Контроллер — только дерево.
            val r = ProcessTree.stop(process.toHandle(), grace, clock, ProcessTree.ownSession())
            drain()
            return StopReport(if (process.isAlive) null else process.exitValue(), r.forced, r.survivors)
        }

        /**
         * Вывод дочитывается до конца, но не дольше [OutputLines.READER_DRAIN]: поток может
         * держать открытым живой внук. Секунды не хватало (ревью A-8): на
         * телефоне каждая строка проходит правку и запись в журнал, и хвост
         * вывода — с итогом хода Claude — терялся или ложился после выхода.
         */
        private fun drain() = readers.forEach { it.join(OutputLines.READER_DRAIN.inWholeMilliseconds) }
    }

    companion object {
        /**
         * Предел длины строки вывода: длиннее — обрезается с отметкой (память,
         * ТЗ, §116). Строка `stream-json` Claude несёт результат инструмента
         * целиком — поэтому мегабайт, а не килобайты.
         */
        const val MAX_LINE: Int = 1024 * 1024
    }
}
