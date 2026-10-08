package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/**
 * Вызовы одного инструмента в ходе: сколько, сколько байт результата ушло
 * в контекст модели, сколько с ошибкой и сколько повторов — тот же вызов
 * без правки между ними ([ToolTracker]).
 */
data class ToolCount(
    val calls: Long = 0,
    val resultBytes: Long = 0,
    val errors: Long = 0,
    val duplicates: Long = 0,
    val duplicateBytes: Long = 0,
) {
    init {
        require(calls >= 0 && resultBytes >= 0 && errors >= 0 && duplicates >= 0 && duplicateBytes >= 0) { "счётчики — не отрицательные" }
    }

    operator fun plus(o: ToolCount) = ToolCount(
        calls + o.calls, resultBytes + o.resultBytes, errors + o.errors, duplicates + o.duplicates, duplicateBytes + o.duplicateBytes,
    )

    companion object {
        val ZERO = ToolCount()
    }
}

/**
 * Работа инструментов в ходе глазами оркестратора (D-41;
 * `docs/EFFICIENCY.md`, §2): замер по потоку `stream-json`, а не слова
 * провайдера. Байты результатов — точные (UTF-8 текста `tool_result`,
 * который получила модель); токены из них — оценка [tokens].
 *
 * [byTool] — вызовы в контексте самого хода; вызовы внутри субагентов —
 * отдельно ([subagentCalls], [subagentResultBytes]): их вывод в контекст
 * хода не попадает, только итог субагента. [filesRead], [filesEdited] —
 * сколько разных файлов прочитано и изменено (сами пути — [TurnFiles]).
 * [unmatchedResults] — результаты без
 * известного вызова (разбор подхвачен посреди хода после перезапуска):
 * тогда суммы — нижняя граница.
 */
data class TurnTools(
    val byTool: Map<String, ToolCount> = emptyMap(),
    val subagentCalls: Long = 0,
    val subagentResultBytes: Long = 0,
    val filesRead: Long = 0,
    val filesEdited: Long = 0,
    val largestResultBytes: Long = 0,
    val unmatchedResults: Long = 0,
) {
    val total: ToolCount get() = byTool.values.fold(ToolCount.ZERO, ToolCount::plus)

    val empty: Boolean get() = byTool.isEmpty() && subagentCalls == 0L && unmatchedResults == 0L

    companion object {
        val EMPTY = TurnTools()

        /** Оценка токенов из байт UTF-8 — как у оценки prompt (D-40, п. 1): байты / 3 вверх. */
        fun tokens(bytes: Long): Long = (bytes + 2) / 3
    }
}

/**
 * Файлы, которые ход читал и правил (D-41), — пути, как их передала
 * модель, в порядке первого обращения, до [MAX] в каждом списке. Из них
 * оркестратор строит рабочий набор задачи для handoff; в журнал пути не
 * пишутся.
 */
data class TurnFiles(val read: List<String> = emptyList(), val edited: List<String> = emptyList()) {
    companion object {
        val EMPTY = TurnFiles()

        /** Путей в списке — не больше: рабочий набор, а не опись дерева. */
        const val MAX = 200
    }
}

/**
 * Счёт вызовов инструментов хода по событиям потока (D-41). Повтор —
 * вызов с тем же входом, что и прежний, когда между ними ничто не могло
 * изменить его результат:
 *
 * - чтение файла — не было правки этого файла и команды с неизвестным
 *   действием (`Bash`, инструменты MCP);
 * - поиск и прочие чтения — не было ни одной правки и такой команды.
 *
 * Сомнение — не повтор: счёт повторов — нижняя граница, а не догадка
 * (D-13). Правки, `TodoWrite`, субагенты и итог хода не считаются
 * повторами никогда. Субагенты ведут свой контекст: их вызовы не
 * сравниваются с вызовами хода.
 */
class ToolTracker {
    private data class Pending(val tool: String, val duplicate: Boolean, val subagent: Boolean)

    private val counts = LinkedHashMap<String, ToolCount>()
    private val pending = HashMap<String, Pending>()
    private val seen = HashSet<String>()
    private val fileEpoch = HashMap<String, Int>()
    private val read = LinkedHashSet<String>()
    private val edited = LinkedHashSet<String>()
    private var unknownEpoch = 0
    private var writeEpoch = 0
    private var subCalls = 0L
    private var subBytes = 0L
    private var largest = 0L
    private var unmatched = 0L

    fun onUse(use: ClaudeEvent.ToolUse, subagent: Boolean) {
        val input = use.input
        val file = input?.let(::fileOf)
        if (file != null) {
            if (use.name in WRITERS) edited.add(file).also { cap(edited) } else if (use.name in READERS) read.add(file).also { cap(read) }
        }
        if (subagent) {
            subCalls++
            use.id?.let { pending[it] = Pending(use.name, duplicate = false, subagent = true) }
            return
        }
        val duplicate = input != null && use.name !in NEVER_DUPLICATE && use.name !in WRITERS && !seen.add(key(use.name, input, file))
        when {
            use.name in WRITERS -> {
                writeEpoch++
                if (file != null) fileEpoch[file] = (fileEpoch[file] ?: 0) + 1
            }
            use.name !in READERS && use.name !in NO_EFFECT -> unknownEpoch++
        }
        counts[use.name] = (counts[use.name] ?: ToolCount.ZERO) + ToolCount(calls = 1, duplicates = if (duplicate) 1 else 0)
        use.id?.let { pending[it] = Pending(use.name, duplicate, subagent = false) }
    }

    fun onResult(r: ClaudeEvent.ToolResult) {
        val p = r.id?.let { pending.remove(it) }
        if (p == null) {
            unmatched++
            return
        }
        if (p.subagent) {
            subBytes += r.bytes
            return
        }
        largest = maxOf(largest, r.bytes)
        counts[p.tool] = counts.getValue(p.tool) + ToolCount(
            resultBytes = r.bytes, errors = if (r.isError) 1 else 0, duplicateBytes = if (p.duplicate) r.bytes else 0,
        )
    }

    fun result(): TurnTools = TurnTools(counts.toMap(), subCalls, subBytes, read.size.toLong(), edited.size.toLong(), largest, unmatched)

    fun files(): TurnFiles = TurnFiles(read.toList(), edited.toList())

    /** Ключ сравнения: вход без описания, с эпохой изменений, которые могли повлиять на результат. */
    private fun key(tool: String, input: JsonObject, file: String?): String {
        val norm = if (tool == BASH) {
            (input["command"] as? JsonString)?.value?.trim()?.replace(SPACES, " ") ?: CanonicalJson.write(input)
        } else {
            CanonicalJson.write(JsonObject(input.fields.filterKeys { it != "description" }))
        }
        val epoch = if (file != null && tool in READERS) "$unknownEpoch/f${fileEpoch[file] ?: 0}" else "$unknownEpoch/w$writeEpoch"
        return "$tool\u0000$epoch\u0000$norm"
    }

    private fun fileOf(input: JsonObject): String? =
        FILE_KEYS.firstNotNullOfOrNull { (input[it] as? JsonString)?.value }?.takeIf { it.isNotBlank() }

    private fun cap(set: LinkedHashSet<String>) {
        if (set.size > TurnFiles.MAX) set.remove(set.last())
    }

    companion object {
        private const val BASH = "Bash"

        /** Правят файл (`file_path`, `notebook_path`). */
        val WRITERS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

        /** Только читают: результат меняют лишь правки. */
        val READERS = setOf("Read", "Grep", "Glob", "LS", "NotebookRead", "WebFetch", "WebSearch")

        /** Ничего не меняют в файлах и не ищут: не повтор и не правка. */
        val NO_EFFECT = setOf("TodoWrite", "StructuredOutput", "ExitPlanMode")

        /** Никогда не повтор: субагент, состояние модели, итог хода. */
        val NEVER_DUPLICATE = NO_EFFECT + setOf("Agent", "Task")

        private val FILE_KEYS = listOf("file_path", "notebook_path")
        private val SPACES = Regex("\\s+")
    }
}
