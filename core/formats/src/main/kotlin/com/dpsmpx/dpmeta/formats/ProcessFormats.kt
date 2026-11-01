package com.dpsmpx.dpmeta.formats

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Разобранный документ процесса: [schema] — имя нормализованного вида
 * (поле `schema` наблюдения, ТЗ, §6.3), [toJson] — его содержимое.
 */
interface ProcessDocument {
    val schema: String

    fun toJson(): JsonObject
}

/**
 * Журнал решений (ТЗ, I-06): записи — заголовки, начинающиеся с номера по
 * шаблону из реестра (`D-12 — …`, `Р3. …`, `M-01 — …`). Статус — первое
 * слово после `**Статус.**` в теле записи, если он есть.
 */
data class DecisionLog(val entries: List<Entry>) : ProcessDocument {
    data class Entry(val id: String, val title: String, val status: String?, val line: Int)

    override val schema: String get() = SCHEMA

    override fun toJson(): JsonObject = JsonObject.of(
        "count" to JsonNumber.of(entries.size),
        "entries" to JsonArray(
            entries.map { e ->
                JsonObject(
                    linkedMapOf<String, JsonValue>("id" to JsonString(e.id), "title" to JsonString(e.title), "line" to JsonNumber.of(e.line))
                        .apply { e.status?.let { put("status", JsonString(it)) } },
                )
            },
        ),
    )

    companion object {
        const val SCHEMA = "process.decisions.v1"
        private val STATUS = Regex("^\\*\\*Статус\\.?\\*\\*\\.?\\s*([\\p{L}-]+)")

        /**
         * [pattern] — шаблон номера из реестра. Нет ни одной записи —
         * `FORMAT_UNRECOGNIZED`: другой шаблон или другой формат заголовков
         * не выдаётся за пустой журнал.
         */
        fun parse(text: String, pattern: Regex, source: String = "DECISIONS.md"): Outcome<DecisionLog> {
            val head = Regex("^(?:\\*\\*)?(${pattern.pattern})(?:\\*\\*)?(?:\\s*[—–:.-]\\s*|\\s+|$)(.*)$")
            val prose = Markdown.prose(text)
            val entries = mutableListOf<Entry>()
            val warnings = mutableListOf<Diagnostic>()
            val seen = HashSet<String>()
            for (h in Markdown.headings(text)) {
                if (h.level < 2) continue
                val m = head.find(h.text) ?: continue
                val id = m.groupValues[1]
                if (!seen.add(id)) {
                    warnings += Diagnostic.warning(DiagCode.VALUE_INVALID, "номер $id записан второй раз", "$source:${h.line}")
                    continue
                }
                val status = prose.asSequence()
                    .dropWhile { it.number <= h.line }
                    .takeWhile { !it.text.startsWith("#") }
                    .firstNotNullOfOrNull { STATUS.find(it.text.trim())?.groupValues?.get(1)?.lowercase() }
                entries += Entry(id, Markdown.plain(m.groupValues[2]), status, h.line)
            }
            if (entries.isEmpty()) {
                return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "нет заголовков с номером по шаблону «${pattern.pattern}»", source)
            }
            return Outcome.success(DecisionLog(entries), warnings)
        }
    }
}

/**
 * `TODO.md` (ТЗ, I-06): разделы второго уровня и их пункты верхнего уровня
 * списка. Метка сложности — `М`, `С`, `Т`, `К` в обратных кавычках в начале
 * пункта; флажок `[ ]`/`[x]` — если есть. Раздел «За автором» — вопросы
 * автору (F-04).
 */
data class TodoList(val sections: List<Section>) : ProcessDocument {
    data class Item(val title: String, val label: String?, val done: Boolean, val line: Int)

    data class Section(val title: String, val items: List<Item>) {
        /** «За автором», «Вопросы автору», «Ждёт автора» — то, что решает автор. */
        val forAuthor: Boolean get() = title.contains("автор", ignoreCase = true)
    }

    val open: Int get() = sections.sumOf { s -> s.items.count { !it.done } }
    val forAuthor: Int get() = sections.filter { it.forAuthor }.sumOf { s -> s.items.count { !it.done } }

    override val schema: String get() = SCHEMA

    override fun toJson(): JsonObject = JsonObject.of(
        "open" to JsonNumber.of(open),
        "forAuthor" to JsonNumber.of(forAuthor),
        "sections" to JsonArray(
            sections.map { s ->
                JsonObject.of(
                    "title" to JsonString(s.title),
                    "forAuthor" to JsonBool(s.forAuthor),
                    "items" to JsonArray(
                        s.items.map { i ->
                            JsonObject(
                                linkedMapOf<String, JsonValue>(
                                    "title" to JsonString(i.title),
                                    "done" to JsonBool(i.done),
                                    "line" to JsonNumber.of(i.line),
                                ).apply { i.label?.let { put("label", JsonString(it)) } },
                            )
                        },
                    ),
                )
            },
        ),
    )

    companion object {
        const val SCHEMA = "process.todo.v1"
        private val ITEM = Regex("^[-*+][ \\t]+(?:\\[([ xX+])][ \\t]+)?(?:`([МСТК])`[ \\t]+)?(.*)$")
        private val BOLD = Regex("^\\*\\*(.+?)\\*\\*")

        fun parse(text: String, source: String = "TODO.md"): Outcome<TodoList> {
            val sections = Markdown.sections(text, 2).map { (h, lines) ->
                val items = lines.mapNotNull { l ->
                    val m = ITEM.find(l.text) ?: return@mapNotNull null
                    val rest = m.groupValues[3].trim()
                    val title = BOLD.find(rest)?.groupValues?.get(1) ?: rest
                    Item(Markdown.plain(title).trimEnd('.', ':'), m.groupValues[2].ifEmpty { null }, m.groupValues[1].isNotBlank(), l.number)
                }
                Section(Markdown.plain(h.text), items)
            }
            if (sections.isEmpty()) return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "нет разделов второго уровня («## …»)", source)
            return Outcome.success(TodoList(sections))
        }
    }
}

/**
 * ROADMAP (ТЗ, I-06): этапы — заголовки с флажком `[+]`/`[x]` (сделано),
 * `[ ]` (нет), `[~]` (идёт); шаги — пункты списка с тем же флажком под
 * заголовком. Заголовок без флажка, под которым есть шаги с флажками, —
 * этап без своего флажка.
 */
data class Roadmap(val stages: List<Stage>) : ProcessDocument {
    enum class Flag(val code: String) { DONE("done"), OPEN("open"), ACTIVE("active"), NONE("none") }

    data class Stage(val title: String, val flag: Flag, val stepsDone: Int, val stepsTotal: Int, val line: Int)

    val stagesDone: Int get() = stages.count { it.flag == Flag.DONE }

    override val schema: String get() = SCHEMA

    override fun toJson(): JsonObject = JsonObject.of(
        "stagesDone" to JsonNumber.of(stagesDone),
        "stagesTotal" to JsonNumber.of(stages.size),
        "stepsDone" to JsonNumber.of(stages.sumOf { it.stepsDone }),
        "stepsTotal" to JsonNumber.of(stages.sumOf { it.stepsTotal }),
        "stages" to JsonArray(
            stages.map { s ->
                JsonObject.of(
                    "title" to JsonString(s.title),
                    "flag" to JsonString(s.flag.code),
                    "stepsDone" to JsonNumber.of(s.stepsDone),
                    "stepsTotal" to JsonNumber.of(s.stepsTotal),
                    "line" to JsonNumber.of(s.line),
                )
            },
        ),
    )

    companion object {
        const val SCHEMA = "process.roadmap.v1"
        private val HEAD_FLAG = Regex("(?:^|\\s)\\[([ xX+~])](?:\\s|$)")
        private val ANCHOR = Regex("<a [^>]*>\\s*</a>")
        private val STEP = Regex("^[ \\t]*[-*+][ \\t]+\\[([ xX+~])]")

        private fun flag(c: String): Flag = when (c) {
            "+", "x", "X" -> Flag.DONE
            "~" -> Flag.ACTIVE
            else -> Flag.OPEN
        }

        fun parse(text: String, source: String = "ROADMAP.md"): Outcome<Roadmap> {
            val stages = mutableListOf<Stage>()
            var title: String? = null
            var headFlag = Flag.NONE
            var line = 0
            var done = 0
            var total = 0
            fun flush() {
                val t = title ?: return
                if (headFlag != Flag.NONE || total > 0) stages += Stage(t, headFlag, done, total, line)
            }
            for (l in Markdown.prose(text)) {
                val h = Markdown.headings(l.text).firstOrNull()
                if (h != null && h.level >= 2) {
                    flush()
                    val text = h.text.replace(ANCHOR, " ")
                    val f = HEAD_FLAG.find(text)
                    headFlag = f?.let { flag(it.groupValues[1]) } ?: Flag.NONE
                    title = Markdown.plain(text.replace(HEAD_FLAG, " ")).replace(Regex("\\s+"), " ").trim()
                    line = l.number
                    done = 0
                    total = 0
                    continue
                }
                val s = STEP.find(l.text) ?: continue
                total++
                if (flag(s.groupValues[1]) == Flag.DONE) done++
            }
            flush()
            if (stages.isEmpty()) return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "нет этапов и шагов с флажками «[ ]», «[x]», «[+]»", source)
            return Outcome.success(Roadmap(stages))
        }
    }
}

/**
 * Блоки состояния README между метками `<!-- имя:begin -->` и
 * `<!-- имя:end -->` (ТЗ, I-06): их пишут скрипты репозиториев, приложение
 * только читает. Нет блоков — пустой список; начало без конца —
 * `FORMAT_UNRECOGNIZED`.
 */
data class StatusBlocks(val blocks: List<Block>) : ProcessDocument {
    data class Block(val name: String, val text: String, val line: Int)

    override val schema: String get() = SCHEMA

    override fun toJson(): JsonObject = JsonObject.of(
        "blocks" to JsonArray(
            blocks.map { JsonObject.of("name" to JsonString(it.name), "text" to JsonString(it.text), "line" to JsonNumber.of(it.line)) },
        ),
    )

    companion object {
        const val SCHEMA = "process.status.v1"
        private val BEGIN = Regex("^\\s*<!--\\s*([a-z0-9_.-]+):begin\\s*-->\\s*$")
        private val END = Regex("^\\s*<!--\\s*([a-z0-9_.-]+):end\\s*-->\\s*$")
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

        fun parse(text: String, source: String = "README.md"): Outcome<StatusBlocks> {
            val lines = Markdown.lines(text)
            val blocks = mutableListOf<Block>()
            var i = 0
            while (i < lines.size) {
                val b = BEGIN.find(lines[i])
                if (b == null) {
                    END.find(lines[i])?.let { return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "конец блока «${it.groupValues[1]}» без начала", "$source:${i + 1}") }
                    i++
                    continue
                }
                val name = b.groupValues[1]
                val end = (i + 1 until lines.size).firstOrNull { END.find(lines[it])?.groupValues?.get(1) == name }
                    ?: return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "блок «$name» не закрыт", "$source:${i + 1}")
                val body = lines.subList(i + 1, end).joinToString("\n").replace(COMMENT, "").trim()
                blocks += Block(name, body, i + 1)
                i = end + 1
            }
            return Outcome.success(StatusBlocks(blocks))
        }
    }
}

/**
 * Описание PR по шаблону «Сделано / Осталось / Проверки» (ТЗ, I-06, F-11):
 * разделы второго уровня. Нет одного из трёх — предупреждение, а не отказ:
 * у чужих PR шаблона может не быть.
 */
data class PrDescription(val sections: Map<String, String>) : ProcessDocument {
    val done: String? get() = sections["Сделано"]
    val remaining: String? get() = sections["Осталось"]
    val checks: String? get() = sections["Проверки"]

    override val schema: String get() = SCHEMA

    override fun toJson(): JsonObject = JsonObject.of(
        "sections" to JsonObject(LinkedHashMap(sections.mapValues { JsonString(it.value) })),
        "template" to JsonBool(REQUIRED.all { it in sections }),
    )

    companion object {
        const val SCHEMA = "process.pr.v1"
        val REQUIRED = listOf("Сделано", "Осталось", "Проверки")

        fun parse(text: String, source: String = "PR"): Outcome<PrDescription> {
            val sections = LinkedHashMap<String, String>()
            for ((h, lines) in Markdown.sections(text, 2)) {
                val title = Markdown.plain(h.text)
                if (title !in sections) sections[title] = lines.joinToString("\n") { it.text }.trim()
            }
            val missing = REQUIRED.filter { it !in sections }
            val warnings = if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf(Diagnostic.warning(DiagCode.FORMAT_UNRECOGNIZED, "нет разделов шаблона: ${missing.joinToString(", ")}", source))
            }
            return Outcome.success(PrDescription(sections), warnings)
        }
    }
}
