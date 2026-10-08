package com.dpsmpx.dpai.orchestrator.efficiency

import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.TurnStatus

/**
 * Рабочий набор задачи (D-41; `docs/EFFICIENCY.md`, §4): что её сессии
 * читали и правили — по вызовам инструментов в потоке, а не по словам
 * модели, — и итог последнего завершённого хода. Свежая сессия
 * (контрольная точка) получает его в handoff и начинает с известных
 * файлов, а не с поиска по репозиторию заново.
 *
 * Пути — относительно рабочего дерева [root]; файлы вне него (кеши,
 * временные каталоги, домашний каталог) не берутся: к задаче они не
 * относятся, а путь домашнего каталога в prompt не нужен.
 */
class WorkingSet(private val root: String) {
    private val edited = LinkedHashSet<String>()
    private val reads = LinkedHashMap<String, Int>()

    /** Итог последнего завершённого хода — заявление исполнителя (D-23), через `Redactor` (D-14). */
    var summary: String? = null
        private set

    fun add(turn: ClaudeTurn, maxSummaryChars: Int) {
        turn.files.edited.mapNotNull(::relative).forEach { edited += it }
        turn.files.read.mapNotNull(::relative).forEach { reads[it] = (reads[it] ?: 0) + 1 }
        if (turn.status == TurnStatus.COMPLETED) {
            turn.text?.trim()?.takeIf { it.isNotEmpty() }?.let { t ->
                val r = Redactor.STANDARD.redact(t)
                summary = if (r.length <= maxSummaryChars) r else r.take(maxSummaryChars) + "… (обрезано)"
            }
        }
    }

    /**
     * До [max] путей: сначала изменённые (по порядку первой правки), затем
     * прочитанные — чаще читаемые раньше; кроме [known] (уже названы в
     * handoff как изменённые по `git status`).
     */
    fun files(max: Int, known: Collection<String>): List<String> {
        val skip = known.toSet()
        val readOnly = reads.entries.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Int>>> { it.value.value }.thenBy { it.index })
            .map { it.value.key }
        return (edited + readOnly).asSequence().distinct().filter { it !in skip }.take(max).toList()
    }

    private fun relative(path: String): String? {
        val p = path.replace('\\', '/')
        val r = root.replace('\\', '/').trimEnd('/')
        if (r.isEmpty() || r == ".") return p.removePrefix("./").takeIf { !it.startsWith("/") && !it.startsWith("..") }
        return p.takeIf { it.startsWith("$r/") }?.removePrefix("$r/")?.takeIf { it.isNotEmpty() }
    }
}
