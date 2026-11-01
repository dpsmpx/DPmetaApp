package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * [run] (`maxStdoutChars`): лишние строки stdout отброшены.
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
    maxStdoutChars: Int = Int.MAX_VALUE,
): Outcome<Completed> {
    val out = mutableListOf<String>()
    val err = mutableListOf<String>()
    val lock = Any()
    var chars = 0L
    var cut = false
    val started = start(spec) { line ->
        synchronized(lock) {
            if (line.stream == OutputStream.STDOUT) {
                // После первой отброшенной строки — только отбрасывать: вывод не рвётся посередине.
                if (cut || chars + line.text.length > maxStdoutChars) {
                    cut = true
                } else {
                    chars += line.text.length + 1
                    out += line.text
                }
            } else {
                err += line.text
            }
        }
    }
    val process = when (started) {
        is Outcome.Failure -> return started
        is Outcome.Success -> started.value
    }
    val code = process.awaitExit(timeout)
    if (code == null) {
        val report = process.stop(grace)
        val causes = if (report.clean) emptyList() else listOf(orphans(report, spec))
        return Outcome.Failure(
            listOf(Diagnostic.of(DiagCode.PROCESS_TIMEOUT, "${spec.program}: не завершился за $timeout, остановлен", causes = causes)),
        )
    }
    // Выход корня ещё не значит, что потоки чтения дочитали вывод: их дожидается awaitExit.
    return synchronized(lock) { Outcome.success(Completed(code, out.toList(), err.toList(), cut)) }
}

internal fun orphans(report: StopReport, spec: ProcessSpec): Diagnostic =
    Diagnostic.error(
        DiagCode.PROCESS_ORPHANS_LEFT,
        "${spec.program}: после остановки живы процессы ${report.survivors.joinToString()}",
    )
