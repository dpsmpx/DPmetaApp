package com.dpsmpx.dppass.diagnostics

/**
 * Одна находка: код, серьёзность, сообщение, место и исходная причина.
 *
 * [message] пишется без секретов: вызывающий подставляет только
 * несекретные значения или прогоняет их через [Redactor]. [causes]
 * сохраняют исходную причину (D-22): «не удалось активировать» несёт
 * внутри «ядро отвергло конфигурацию», а то — исходную ошибку ядра.
 */
data class Diagnostic(
    val code: DiagCode,
    val message: String,
    val path: String? = null,
    val severity: Severity = code.defaultSeverity,
    val causes: List<Diagnostic> = emptyList(),
    val error: ErrorSummary? = null,
) {
    val kind: FailureKind get() = code.kind
    val isError: Boolean get() = severity == Severity.ERROR

    /** Строка для журнала и отчёта: код, место, сообщение. */
    fun render(): String = buildString {
        append(severity.name).append(' ').append(code.name)
        if (path != null) append(" at ").append(path)
        append(": ").append(message)
    }

    fun withCause(cause: Diagnostic): Diagnostic = copy(causes = causes + cause)

    /**
     * Строки для человека: эта диагностика и её причины с отступом, со
     * сводкой исключения (уже отредактированной). Глубина ограничена.
     */
    fun renderWithCauses(maxDepth: Int = 8): List<String> {
        val out = mutableListOf<String>()
        fun walk(d: Diagnostic, depth: Int) {
            val err = d.error?.let { " [${it.type}${it.message?.let { m -> ": $m" } ?: ""}]" } ?: ""
            out += "  ".repeat(depth) + d.render() + err
            if (depth < maxDepth) d.causes.forEach { walk(it, depth + 1) }
        }
        walk(this, 0)
        return out
    }

    companion object {
        fun error(code: DiagCode, message: String, path: String? = null): Diagnostic =
            Diagnostic(code, message, path, Severity.ERROR)

        fun warning(code: DiagCode, message: String, path: String? = null): Diagnostic =
            Diagnostic(code, message, path, Severity.WARNING)

        fun info(code: DiagCode, message: String, path: String? = null): Diagnostic =
            Diagnostic(code, message, path, Severity.INFO)

        /** Исключение как причина: тип и отредактированное сообщение. */
        fun fromThrowable(code: DiagCode, message: String, t: Throwable, redactor: Redactor = Redactor()): Diagnostic =
            Diagnostic(code, message, error = ErrorSummary.of(t, redactor))
    }
}

/**
 * Сводка исключения без стека и без секретов: тип и отредактированное
 * сообщение. Стек остаётся в локальном журнале разработчика, но не в
 * диагностике, которая уходит в отчёт.
 */
data class ErrorSummary(val type: String, val message: String?) {
    companion object {
        fun of(t: Throwable, redactor: Redactor): ErrorSummary =
            ErrorSummary(t::class.qualifiedName ?: "Throwable", t.message?.let(redactor::redact))
    }
}

/** Сбор находок по ходу шага; путь — в нотации `$.profiles[0].port`. */
class DiagnosticCollector {
    private val items = mutableListOf<Diagnostic>()

    val all: List<Diagnostic> get() = items.toList()
    val hasErrors: Boolean get() = items.any { it.isError }

    fun add(d: Diagnostic) {
        items += d
    }

    fun addAll(ds: Iterable<Diagnostic>) {
        items += ds
    }

    fun error(code: DiagCode, message: String, path: String? = null) = add(Diagnostic.error(code, message, path))
    fun warning(code: DiagCode, message: String, path: String? = null) = add(Diagnostic.warning(code, message, path))
    fun info(code: DiagCode, message: String, path: String? = null) = add(Diagnostic.info(code, message, path))

    fun <T> result(value: () -> T): Outcome<T> =
        if (hasErrors) Outcome.Failure(all) else Outcome.Success(value(), all)
}
