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
        private fun withoutComments(text: String): String = buildString {
            var at = 0
            while (at < text.length) {
                val start = text.indexOf("<!--", at)
                if (start < 0) { append(text, at, text.length); break }
                append(text, at, start)
                val end = text.indexOf("-->", start + 4)
                if (end < 0) { append(text, start, text.length); break }
                at = end + 3
            }
        }

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
                val body = withoutComments(lines.subList(i + 1, end).joinToString("\n")).trim()
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
