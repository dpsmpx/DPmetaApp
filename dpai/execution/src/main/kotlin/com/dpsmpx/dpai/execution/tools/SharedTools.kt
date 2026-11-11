package com.dpsmpx.dpai.execution.tools

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess

/**
 * Общие инструменты на POSIX target (D-122, DPmetaApp:D-89).
 * Только пути и имя Python; учётные данные Git здесь не хранятся.
 * Контроллер устанавливается отдельно, полный DP-Verify — его изменяемая Git-копия.
 */
data class SharedTools(val repository: String, val controller: String, val python: String = "python3") {
    init {
        require(absolute(repository) && absolute(controller)) { "папка и контроллер — абсолютные POSIX-пути без «..» и управляющих символов" }
        require(python.isNotBlank() && python.none { it.code < 32 }) { "нужно имя программы Python" }
    }

    fun toJson(): JsonObject = JsonObject.of(
        *FormatHeader.write(FORMAT, VERSION),
        "repository" to JsonString(repository), "controller" to JsonString(controller), "python" to JsonString(python),
    )

    /**
     * Команда остаётся argv, без shell. Исходный prompt и окружение слота сохраняются.
     * Права ревьюера не повышаются: режим read-only Codex/Gemini не меняется.
     */
    fun prepare(spec: ProcessSpec, executor: Executor): ProcessSpec {
        val command = spec.command.toMutableList()
        when (executor) {
            Executor.CLAUDE -> command.addAll(listOf("--add-dir", repository))
            Executor.CODEX -> {
                // Опция exec ставится до подкоманды resume. Дополнение через add-dir
                // сохраняет ранее настроенные writable_roots и режим read-only.
                val at = command.indexOf("exec").let { if (it < 0) command.size else it + 1 }
                command.addAll(at, listOf("--add-dir", repository))
            }
            Executor.GEMINI -> command.addAll(listOf("--include-directories", repository))
        }
        val context = "Общие инструменты проверки DP-Verify находятся в $repository. " +
            "Основные проверки выбирай по документации проекта. Если проект подключён к DP-Verify, используй dpv на PATH: он защищает версию на время проверки. " +
            "Другие инструменты запускай через DP_VERIFY_PYTHON и DP_VERIFY_CONTROLLER с --repo DP_VERIFY_HOME run -- командой; " +
            "правки инструментов — через edit вместо run. " +
            "В паузах папка может обновляться. Сначала прочитай инструкции инструментов; синхронизацией с GitHub управляет DPmetaApp."
        return spec.copy(
            command = listOf(python, controller, "--repo", repository, "session", "--") + command,
            stdin = context + "\n\n" + spec.stdin.orEmpty(),
        )
    }

    companion object {
        const val FORMAT = "dpai.shared-tools"
        const val VERSION = 1
        /** Метка раннера, а не переменная пользовательского слота. Старый агент её просто передаёт. */
        const val EXECUTOR_ENV = "DPAI_EXECUTOR"

        private fun absolute(value: String): Boolean = value.startsWith('/') && value.length > 1 &&
            value.none { it.code < 32 } && value.split('/').none { it == "." || it == ".." }

        fun parse(value: JsonObject): Outcome<SharedTools> {
            val diagnostics = DiagnosticCollector()
            val fields = JsonFields.root(value, diagnostics) ?: return Outcome.Failure(diagnostics.all)
            FormatHeader.read(fields, FORMAT, 1..VERSION, diagnostics) ?: return Outcome.Failure(diagnostics.all)
            val repository = fields.string("repository")
            val controller = fields.string("controller")
            val python = fields.string("python")
            fields.finish()
            if (diagnostics.hasErrors || repository == null || controller == null || python == null) return Outcome.Failure(diagnostics.all)
            return runCatching { SharedTools(repository, controller, python) }.fold(
                { Outcome.success(it) },
                { Outcome.failure(DiagCode.VALUE_INVALID, it.message ?: "неверная настройка общих инструментов", FORMAT) },
            )
        }
    }
}

/** Применяет настройку агента ко всем исполнителям; пробы входа и служебные команды не помечены. */
class SharedToolsHost(
    private val delegate: ProcessHost,
    private val posix: Boolean = java.io.File.separatorChar != '\\',
    private val load: () -> Outcome<SharedTools?>,
) : ProcessHost {
    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        val marker = spec.environment?.get(SharedTools.EXECUTOR_ENV) ?: return delegate.start(spec, onOutput)
        val executor = Executor.entries.firstOrNull { it.name == marker }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "неизвестный исполнитель общих инструментов", SharedTools.EXECUTOR_ENV)
        return when (val configured = load()) {
            is Outcome.Failure -> configured
            is Outcome.Success -> if (configured.value != null && !posix) {
                Outcome.failure(DiagCode.UNSUPPORTED, "общие инструменты пока поддерживают только POSIX target", SharedTools.FORMAT)
            } else delegate.start(configured.value?.prepare(spec, executor) ?: spec, onOutput)
        }
    }
}
