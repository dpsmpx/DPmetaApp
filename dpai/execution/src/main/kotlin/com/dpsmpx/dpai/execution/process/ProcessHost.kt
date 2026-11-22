package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Что запустить (`docs/EXECUTION_MODEL.md`, §5). Аргументы не содержат
 * секретов (D-14): секрет — только в [environment]. [environment] —
 * окружение целиком (`null` — унаследовать окружение хоста); целиком,
 * потому что окружение слота собирается из разрешённого набора (D-29).
 */
data class ProcessSpec(
    val command: List<String>,
    val workDir: String? = null,
    val environment: Map<String, String>? = null,
    val stdin: String? = null,
) {
    init {
        require(command.isNotEmpty()) { "пустая команда" }
    }

    /** Для журнала и диагностики: имя программы без аргументов — аргументы могут быть длинными. */
    val program: String get() = command.first()
}

/** Поток вывода процесса. */
enum class OutputStream { STDOUT, STDERR }

/** Строка вывода; слишком длинная строка обрезается ([LocalProcessHost.MAX_LINE]). */
data class OutputLine(val stream: OutputStream, val text: String, val truncated: Boolean = false)

/**
 * Как процесс остановлен: [exitCode] — код выхода, если процесс успел
 * выйти; [forced] — понадобилось принудительное завершение; [survivors] —
 * идентификаторы процессов дерева, которые пережили остановку. Пустой
 * [survivors] — сирот не осталось (ТЗ, §119).
 */
data class StopReport(val exitCode: Int?, val forced: Boolean, val survivors: List<Long>) {
    val clean: Boolean get() = survivors.isEmpty()
}

/** Работающий процесс. Остановка убирает всё его дерево, а не только корень. */
interface RunningProcess {
    /** Идентификатор процесса ОС, если он известен. */
    val pid: Long?

    val isAlive: Boolean

    /** Ждать выхода не дольше [timeout]; `null` — ещё работает. */
    fun awaitExit(timeout: Duration): Int?

    /** Мягкая остановка всего дерева, через [grace] — принудительная. */
    fun stop(grace: Duration = DEFAULT_GRACE): StopReport

    companion object {
        val DEFAULT_GRACE: Duration = 5.seconds
    }
}

/**
 * Место, где запускаются процессы: локальная машина, target по SSH, mock.
 * Запуск процессов — только в модуле `execution` (D-03).
 */
interface ProcessHost {
    /**
     * Запустить; строки вывода приходят в [onOutput] из потоков чтения —
     * обработчик должен быть потокобезопасным.
     */
    fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit = {}): Outcome<RunningProcess>
}

/**
 * Итог процесса, дождавшегося выхода. [truncated] — вывод длиннее предела
 * [run]: лишние строки stdout/stderr отброшены или строка обрезана читателем.
 */
data class Completed(val exitCode: Int, val stdout: List<String>, val stderr: List<String>, val truncated: Boolean = false) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * Запустить и дождаться выхода не дольше [timeout]. Тайм-аут — остановка
 * всего дерева и `PROCESS_TIMEOUT`; пережившие остановку процессы —
 * `PROCESS_ORPHANS_LEFT` причиной (ТЗ, §119).
 */
fun ProcessHost.run(
    spec: ProcessSpec,
    timeout: Duration,
    grace: Duration = RunningProcess.DEFAULT_GRACE,
    /** Предел stdout в знаках: память на телефоне не зависит от размера вывода (ревью 17 октября 2026). */
    maxStdoutChars: Int = 1024 * 1024,
    maxStderrChars: Int = 1024 * 1024,
    /** Сигнал только этого вызова (D-216); null сохраняет прежнее ожидание одним вызовом. */
    cancelled: (() -> Boolean)? = null,
    clock: Clock = Clock.SYSTEM,
): Outcome<Completed> {
    require(maxStdoutChars >= 0 && maxStderrChars >= 0) { "пределы вывода неотрицательны" }
    if (cancelled?.invoke() == true) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "запуск процесса отменён")
    val out = mutableListOf<String>()
    val err = mutableListOf<String>()
    val lock = Any()
    var outChars = 0L
    var errChars = 0L
    var outCut = false
    var errCut = false
    var lineCut = false
    val started = start(spec) { line ->
        synchronized(lock) {
            lineCut = lineCut || line.truncated
            val cost = line.text.length.toLong() + 1 // Пустые строки тоже занимают память (O-05).
            if (line.stream == OutputStream.STDOUT) {
                // После первой отброшенной строки — только отбрасывать: вывод не рвётся посередине.
                if (outCut || outChars + cost > maxStdoutChars) {
                    outCut = true
                } else {
                    outChars += cost
                    out += line.text
                }
            } else {
                if (errCut || errChars + cost > maxStderrChars) {
                    errCut = true
                } else {
                    errChars += cost
                    err += line.text
                }
            }
        }
    }
    val process = when (started) {
        is Outcome.Failure -> return started
        is Outcome.Success -> started.value
    }
    var wasCancelled = false
    val code = if (cancelled == null) process.awaitExit(timeout) else {
        val begun = clock.monotonicNanos()
        var result: Int? = null
        while (result == null) {
            if (cancelled()) { wasCancelled = true; break }
            val remaining = timeout - (clock.monotonicNanos() - begun).nanoseconds
            if (remaining <= Duration.ZERO) break
            result = process.awaitExit(minOf(100.milliseconds, remaining))
        }
        result
    }
    if (code == null) {
        val report = process.stop(grace)
        val causes = if (report.clean) emptyList() else listOf(orphans(report, spec))
        return Outcome.Failure(
            listOf(Diagnostic.error(if (wasCancelled) DiagCode.CANCELLED_BY_USER else DiagCode.PROCESS_TIMEOUT,
                if (wasCancelled) "${spec.program}: вызов отменён, запрошена остановка своего процесса" else "${spec.program}: не завершился за $timeout, остановлен", causes = causes)),
        )
    }
    // Выход корня ещё не значит, что потоки чтения дочитали вывод: их дожидается awaitExit.
    return synchronized(lock) { Outcome.success(Completed(code, out.toList(), err.toList(), outCut || errCut || lineCut)) }
}

internal fun orphans(report: StopReport, spec: ProcessSpec): Diagnostic =
    Diagnostic.error(
        DiagCode.PROCESS_ORPHANS_LEFT,
        "${spec.program}: после остановки живы процессы ${report.survivors.joinToString()}",
    )
