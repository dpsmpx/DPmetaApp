package com.dpsmpx.dpai.contexts

/** Вид записи истории сессии (`docs/CONTEXT_MODEL.md`, §4). */
enum class HistoryKind { NOTE, DECISION, VERIFIED_FACT, PROBLEM, PROBLEM_RESOLVED, NEXT_ACTION }

/**
 * Запись истории со ссылкой на источник — событие журнала или файл (ТЗ,
 * §5.6): из сводки всегда можно вернуться к исходнику.
 */
data class HistoryItem(val kind: HistoryKind, val text: String, val ref: String)

/** Факт сводки и откуда он взят. */
data class Fact(val text: String, val refs: List<String>)

/** Сводка: решения, проверенные факты, открытые проблемы, следующее действие. */
data class Summary(val decisions: List<Fact>, val verified: List<Fact>, val openProblems: List<Fact>, val nextAction: Fact?) {
    fun render(): String = buildString {
        fun block(title: String, facts: List<Fact>) {
            if (facts.isEmpty()) return
            appendLine("$title:")
            facts.forEach { appendLine("- ${it.text} [${it.refs.joinToString()}]") }
        }
        block("Решения", decisions)
        block("Проверенные факты", verified)
        block("Открытые проблемы", openProblems)
        nextAction?.let { append("Следующее действие: ${it.text} [${it.refs.joinToString()}]") }
    }.trimEnd()
}

/**
 * Сжатие истории (ТЗ, §26): сырая история → решения → проверенные факты →
 * открытые проблемы → следующее действие. Без модели и без догадок (D-22):
 * одинаковые записи сводятся в один факт со всеми ссылками; решённая
 * проблема уходит из открытых; заметки в сводку не идут, но остаются в
 * исходной истории — сводка источник не заменяет.
 */
object Compaction {
    fun compact(history: List<HistoryItem>): Summary {
        fun merge(kind: HistoryKind): List<Fact> =
            history.filter { it.kind == kind }.groupBy { it.text.trim() }.map { (text, items) -> Fact(text, items.map { it.ref }.distinct()) }
        val resolved = history.filter { it.kind == HistoryKind.PROBLEM_RESOLVED }.map { it.text.trim() }.toSet()
        val next = history.lastOrNull { it.kind == HistoryKind.NEXT_ACTION }?.let { Fact(it.text.trim(), listOf(it.ref)) }
        return Summary(
            decisions = merge(HistoryKind.DECISION),
            verified = merge(HistoryKind.VERIFIED_FACT),
            openProblems = merge(HistoryKind.PROBLEM).filter { it.text !in resolved },
            nextAction = next,
        )
    }
}
