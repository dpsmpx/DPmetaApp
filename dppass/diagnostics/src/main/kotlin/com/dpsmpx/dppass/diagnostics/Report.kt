package com.dpsmpx.dppass.diagnostics

/**
 * Локальный диагностический отчёт: разделы «ключ — значение»,
 * находки и хвост журнала. Создаётся на устройстве и никуда не
 * отправляется сам (D-12): отдать его кому-то — действие пользователя.
 *
 * Всё содержимое при выводе ещё раз проходит [Redactor] — даже то, что
 * уже было отредактировано: отчёт — то, что чаще всего уходит наружу.
 */
class DiagnosticReport private constructor(
    private val title: String,
    private val sections: List<Pair<String, List<Pair<String, String>>>>,
    private val diagnostics: List<Diagnostic>,
    private val log: List<LogEvent>,
) {
    fun render(redactor: Redactor = Redactor(Redactor.Level.STRICT)): String = buildString {
        appendLine("# ${redactor.redact(title)}")
        for ((name, rows) in sections) {
            appendLine()
            appendLine("## ${redactor.redact(name)}")
            for ((k, v) in rows) appendLine("- ${redactor.redact(k)}: ${redactor.redact(v)}")
        }
        if (diagnostics.isNotEmpty()) {
            appendLine()
            appendLine("## Diagnostics")
            for (d in diagnostics) renderDiagnostic(d, 0, redactor)
        }
        if (log.isNotEmpty()) {
            appendLine()
            appendLine("## Log")
            for (e in log) appendLine(redactor.redact(e.render()))
        }
    }

    private fun StringBuilder.renderDiagnostic(d: Diagnostic, depth: Int, redactor: Redactor) {
        append("  ".repeat(depth)).append("- ").appendLine(redactor.redact(d.render()))
        d.error?.let { append("  ".repeat(depth + 1)).appendLine("error: ${it.type}: ${redactor.redact(it.message ?: "")}") }
        for (c in d.causes) renderDiagnostic(c, depth + 1, redactor)
    }

    class Builder(private val title: String) {
        private val sections = mutableListOf<Pair<String, List<Pair<String, String>>>>()
        private val diagnostics = mutableListOf<Diagnostic>()
        private val log = mutableListOf<LogEvent>()

        fun section(name: String, vararg rows: Pair<String, String>) = apply { sections += name to rows.toList() }
        fun diagnostics(ds: List<Diagnostic>) = apply { diagnostics += ds }
        fun log(events: List<LogEvent>) = apply { log += events }
        fun build(): DiagnosticReport = DiagnosticReport(title, sections.toList(), diagnostics.toList(), log.toList())
    }
}
