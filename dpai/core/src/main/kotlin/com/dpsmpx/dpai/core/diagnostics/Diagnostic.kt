package com.dpsmpx.dpai.core.diagnostics

/**
 * Одна находка: код, серьёзность, сообщение, предмет, причины (D-05).
 *
 * Сообщение и предмет проходят [Redactor] при создании: в диагностику не
 * попадает секрет, даже если вызывающий подставил текст снаружи (T8,
 * инвариант `docs/DATA_MODEL.md`, §5). [causes] сохраняют причинную цепочку
 * ТЗ, §5.1: «сессия упала» несёт внутри «хост недоступен», а то — «SSH не
 * соединился».
 *
 * [subject] — где: путь в документе (`$.tasks[0].id`), сущность
 * (`session ses_…`) или место в тексте (`строка 3, столбец 7`).
 */
class Diagnostic private constructor(
    val code: DiagCode,
    val message: String,
    val subject: String?,
    val severity: Severity,
    val causes: List<Diagnostic>,
    val error: ErrorSummary?,
) {
    val category: Category get() = code.category
    val isError: Boolean get() = severity == Severity.ERROR

    /**
     * Класс повтора с учётом причин: собственный класс кода, если он известен;
     * у обёртки с `UNKNOWN` — по причинам: хоть одна неповторяемая — не
     * повторять, все повторяемые — повторять, иначе неизвестно.
     */
    val retryability: Retryability
        get() {
            if (code.retry != Retryability.UNKNOWN || causes.isEmpty()) return code.retry
            val inner = causes.map { it.retryability }
            return when {
                Retryability.NON_RETRYABLE in inner -> Retryability.NON_RETRYABLE
                inner.all { it == Retryability.RETRYABLE } -> Retryability.RETRYABLE
                else -> Retryability.UNKNOWN
            }
        }

    /** Исходные причины — листья цепочки; у диагностики без причин — она сама. */
    fun rootCauses(): List<Diagnostic> = if (causes.isEmpty()) listOf(this) else causes.flatMap { it.rootCauses() }

    fun withCause(cause: Diagnostic): Diagnostic = Diagnostic(code, message, subject, severity, causes + cause, error)

    fun withCauses(more: List<Diagnostic>): Diagnostic = Diagnostic(code, message, subject, severity, causes + more, error)

    /** Строка для журнала и отчёта: серьёзность, код, предмет, сообщение. */
    fun render(): String = buildString {
        append(severity.name).append(' ').append(code.name)
        if (subject != null) append(" at ").append(subject)
        append(": ").append(message)
        if (error != null) append(" [").append(error.type).append(error.message?.let { ": $it" } ?: "").append(']')
    }

    /** Эта диагностика и её причины с отступом; глубина ограничена. */
    fun renderWithCauses(maxDepth: Int = 8): List<String> {
        val out = mutableListOf<String>()
        fun walk(d: Diagnostic, depth: Int) {
            out += "  ".repeat(depth) + (if (depth > 0) "└ " else "") + d.render()
            if (depth < maxDepth) d.causes.forEach { walk(it, depth + 1) }
        }
        walk(this, 0)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Diagnostic && code == other.code && message == other.message && subject == other.subject &&
            severity == other.severity && causes == other.causes && error == other.error

    override fun hashCode(): Int = listOf(code, message, subject, severity, causes, error).hashCode()

    override fun toString(): String = render()

    companion object {
        fun of(
            code: DiagCode,
            message: String,
            subject: String? = null,
            severity: Severity = code.defaultSeverity,
            causes: List<Diagnostic> = emptyList(),
        ): Diagnostic = Diagnostic(
            code,
            Redactor.STANDARD.redact(message),
            subject?.let(Redactor.STANDARD::redact),
            severity,
            causes,
            null,
        )

        fun error(code: DiagCode, message: String, subject: String? = null): Diagnostic =
            of(code, message, subject, Severity.ERROR)

        fun warning(code: DiagCode, message: String, subject: String? = null): Diagnostic =
            of(code, message, subject, Severity.WARNING)

        fun info(code: DiagCode, message: String, subject: String? = null): Diagnostic =
            of(code, message, subject, Severity.INFO)

        /** Исключение как причина: тип и отредактированное сообщение, без стека (D-05). */
        fun fromThrowable(code: DiagCode, message: String, t: Throwable, subject: String? = null): Diagnostic =
            Diagnostic(
                code,
                Redactor.STANDARD.redact(message),
                subject?.let(Redactor.STANDARD::redact),
                code.defaultSeverity,
                emptyList(),
                ErrorSummary.of(t),
            )
    }
}

/** Сводка исключения без стека и без секретов: тип и отредактированное сообщение. */
data class ErrorSummary(val type: String, val message: String?) {
    companion object {
        fun of(t: Throwable): ErrorSummary =
            ErrorSummary(t::class.qualifiedName ?: "Throwable", t.message?.let(Redactor.STANDARD::redact))
    }
}

/** Сбор находок по ходу шага. */
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

    fun error(code: DiagCode, message: String, subject: String? = null) = add(Diagnostic.error(code, message, subject))

    fun warning(code: DiagCode, message: String, subject: String? = null) = add(Diagnostic.warning(code, message, subject))

    fun info(code: DiagCode, message: String, subject: String? = null) = add(Diagnostic.info(code, message, subject))
}
