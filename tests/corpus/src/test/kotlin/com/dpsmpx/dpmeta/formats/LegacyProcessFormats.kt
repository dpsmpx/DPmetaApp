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

        /**
         * Разделы «## …»; пункты до первого раздела (или весь список, если
         * разделов нет, как у `DPprofile:TODO.md` с метками `[этап N]`) —
         * раздел без заголовка. Пустой `TODO.md` — законно: всё сделано.
         */
        fun parse(text: String, @Suppress("UNUSED_PARAMETER") source: String = "TODO.md"): Outcome<TodoList> {
            fun items(lines: List<Markdown.Line>) = lines.mapNotNull { l ->
                val m = ITEM.find(l.text) ?: return@mapNotNull null
                val rest = m.groupValues[3].trim()
                val title = BOLD.find(rest)?.groupValues?.get(1) ?: rest
                Item(Markdown.plain(title).trimEnd('.', ':'), m.groupValues[2].ifEmpty { null }, m.groupValues[1].isNotBlank(), l.number)
            }
            val sections = Markdown.sections(text, 2).map { (h, lines) -> Section(Markdown.plain(h.text), items(lines)) }
            val firstHeading = sections.firstOrNull()?.let { Markdown.sections(text, 2).first().first.line } ?: Int.MAX_VALUE
            val loose = items(Markdown.prose(text).filter { it.number < firstHeading })
            return Outcome.success(TodoList(if (loose.isEmpty()) sections else listOf(Section("", loose)) + sections))
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
            if (stages.isEmpty()) stages += table(text)
            if (stages.isEmpty()) {
                return Outcome.failure(DiagCode.FORMAT_UNRECOGNIZED, "нет этапов: ни флажков «[ ]», «[x]», «[+]», ни таблицы со столбцом «Состояние»", source)
            }
            return Outcome.success(Roadmap(stages))
        }

        private val CELL_SPLIT = Regex("(?<!\\\\)\\|")

        private fun cells(line: String): List<String>? {
            val t = line.trim()
            if (!t.startsWith("|")) return null
            return t.removePrefix("|").removeSuffix("|").split(CELL_SPLIT).map { Markdown.plain(it) }
        }

        /**
         * Таблица этапов со столбцом «Состояние» (`DPpass:docs/direction/ROADMAP/README.md`,
         * `SocialMediaImage:ROADMAP/ROADMAP.md`, блок README подсистемы «AI»).
         * Состояние начинается с «выполнен», «сделано», «готово» — сделано;
         * говорит «в работе», «идёт» или «сделано» не в начале — идёт; иначе — нет.
         * Берётся первая такая таблица.
         */
        private fun table(text: String): List<Stage> {
            val lines = Markdown.prose(text)
            for ((i, l) in lines.withIndex()) {
                val head = cells(l.text) ?: continue
                val col = head.indexOfFirst { it.equals("Состояние", ignoreCase = true) }
                if (col < 1) continue
                val sep = lines.getOrNull(i + 1)?.let { cells(it.text) } ?: continue
                if (!sep.all { it.trim().matches(Regex(":?-{3,}:?")) }) continue
                val out = mutableListOf<Stage>()
                for (row in lines.drop(i + 2)) {
                    val c = cells(row.text) ?: break
                    if (c.size <= col) continue
                    val name = c[0].trim().let { if (it.all(Char::isDigit) && c.size > 1) "Этап $it. ${c[1].trim()}" else it }
                    val state = c[col].trim().lowercase()
                    val flag = when {
                        DONE_WORDS.any { state.startsWith(it) } -> Flag.DONE
                        ACTIVE_WORDS.any { it in state } -> Flag.ACTIVE
                        else -> Flag.OPEN
                    }
                    out += Stage(name, flag, 0, 0, row.number)
                }
                if (out.isNotEmpty()) return out
            }
            return emptyList()
        }

        private val DONE_WORDS = listOf("выполнен", "сделано", "готово")
        private val ACTIVE_WORDS = listOf("в работе", "идёт", "идет", "сделано")
    }
}

