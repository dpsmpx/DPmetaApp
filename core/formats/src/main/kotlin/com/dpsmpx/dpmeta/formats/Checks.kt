package com.dpsmpx.dpmeta.formats

/**
 * Итог раздела «Проверки» описания PR (ТЗ, F-15): зелёный, красный или
 * неизвестно. Не распознано — «неизвестно», а не «зелёный». Отчёт
 * `./dpv report --md` DP-Verify (I-04) распознаётся по `PASS`/`FAIL`.
 */
object Checks {
    enum class Verdict(val code: String, val title: String) {
        GREEN("green", "зелёный"),
        RED("red", "красный"),
        UNKNOWN("unknown", "неизвестно"),
    }

    data class Result(val verdict: Verdict, val line: String?)

    private val RED = listOf(Regex("✗"), Regex("красн", RegexOption.IGNORE_CASE), Regex("\\bFAIL", RegexOption.IGNORE_CASE), Regex("не прош", RegexOption.IGNORE_CASE), Regex("упал", RegexOption.IGNORE_CASE))
    private val GREEN = listOf(Regex("✓"), Regex("зелён", RegexOption.IGNORE_CASE), Regex("\\bPASS\\b"), Regex("\\bOK\\b"))
    private val NOT_RUN = Regex("не запуска|не провер|пропущ", RegexOption.IGNORE_CASE)
    private val COUNT = Regex("(\\d+)\\s+из\\s+(\\d+)")

    /** По описанию PR; нет раздела «Проверки» — неизвестно. */
    fun of(body: String?): Result {
        val text = body?.let { PrDescription.parse(it).valueOrNull()?.checks }?.takeIf { it.isNotBlank() } ?: return Result(Verdict.UNKNOWN, null)
        val line = text.lineSequence().map { it.trim().removePrefix("- ").trim() }.firstOrNull { it.isNotEmpty() }
        val counts = COUNT.findAll(text).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        val verdict = when {
            RED.any { it.containsMatchIn(text) } || counts.any { (a, b) -> a < b } -> Verdict.RED
            NOT_RUN.containsMatchIn(text) -> Verdict.UNKNOWN
            GREEN.any { it.containsMatchIn(text) } || (counts.isNotEmpty() && counts.all { (a, b) -> a == b }) -> Verdict.GREEN
            else -> Verdict.UNKNOWN
        }
        return Result(verdict, line)
    }
}
