package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.diagnostics.Severity
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.BriefFormat
import com.dpsmpx.dpmeta.formats.Checklist
import com.dpsmpx.dpmeta.formats.CommandFormat
import com.dpsmpx.dpmeta.formats.DecisionLog
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.formats.PrDescription
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.formats.Roadmap
import com.dpsmpx.dpmeta.formats.StatusBlocks
import com.dpsmpx.dpmeta.formats.TodoList
import com.dpsmpx.dpmeta.formats.Toml
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Корпус «ловит / пропускает» (ТЗ, §10, N-13): у каждого образца —
 * `.expect` рядом. `passes` — разбор удался, `summary` совпал; `catches` —
 * отказ с кодом `error` и местом, содержащим `at`.
 */
class CorpusTest {
    private val root = File(System.getProperty("corpus.samples") ?: error("нет corpus.samples"))

    private fun expect(sample: File): Map<String, String> =
        File(sample.parentFile, sample.nameWithoutExtension + ".expect").readLines()
            .filter { it.isNotBlank() }
            .associate { it.substringBefore(": ") to it.substringAfter(": ") }

    private fun parse(format: String, text: String, e: Map<String, String>): Outcome<String> = when (format) {
        "brief" -> BriefFormat.parse(text, "sample", "").map { b ->
            "${b.title} " + b.fields.joinToString(",") { it.name + if (it.multiline) "*" else "" }.ifEmpty { "-" } +
                " places=" + Regex("\\{\\{[a-z][a-z0-9_]*}}").findAll(b.body).count()
        }
        "command" -> CommandFormat.parse(text, "").map { r ->
            r.commands.joinToString(",") { c ->
                "${c.id}=${c.project}" + (c.workdir?.let { "/$it" } ?: "") + ":L${c.level}:${c.plan.code}:${c.timeoutMinutes}m:" +
                    c.packages.joinToString("+").ifEmpty { "-" } + ":" + c.line
            }
        }
        "events" -> EventCatalogFormat.parse(text, "").map { c ->
            c.types.joinToString(",") { t ->
                "${t.type}:" + t.sources.joinToString("+") { it.code } + ":${t.channel.code}:" +
                    t.fields.joinToString("+").ifEmpty { "-" } + "|" + t.numbers.joinToString("+").ifEmpty { "-" }
            }
        }
        // Правила разбираются против каталога (D-85): синтетический — samples/alerts/catalog.toml.
        "alerts" -> AlertFormat.parse(text, EventCatalogFormat.parse(File(root, "alerts/catalog.toml").readText(), "catalog").valueOrNull()!!, "").map { r ->
            r.rules.joinToString(",") { x ->
                "${x.id}:${x.on}:${x.action.code}" + (x.channel?.let { ":${it.code}" } ?: "") +
                    (if (x.importance.code != "default") ":${x.importance.code}" else "") +
                    (x.project?.let { "@$it" } ?: "") + (x.contains?.let { "~$it" } ?: "") + (x.regex?.let { "/${it.pattern}/" } ?: "") +
                    (x.number?.let { n -> "#${n.field}" + (n.atLeast?.let { ">=$it" } ?: "") + (n.atMost?.let { "<=$it" } ?: "") } ?: "") +
                    (if (x.days.isEmpty()) "" else x.days.sorted().joinToString("+", "[", "]")) +
                    (x.debounce?.let { "${it.limit}/${it.windowMinutes}" } ?: "") +
                    (if (!x.quiet) "!loud" else "") + (if (!x.enabled) "!off" else "")
            }
        }
        "toml" -> Toml.parse(text, "").map { CanonicalJson.write(it) }
        "registry" -> RegistryFormat.parse(text, "").map { r ->
            r.projects.joinToString(",") { "${it.name}:${it.place.code}" + (it.dir?.let { d -> "@$d" } ?: "") } + " shown=${r.shown.size}"
        }
        "decisions" -> DecisionLog.parse(text, Regex(e.getValue("pattern")), "").map { d ->
            d.entries.joinToString(",") { "${it.id}=${it.status ?: "-"}" }
        }
        "todo" -> TodoList.parse(text, "").map { t ->
            "open=${t.open} forAuthor=${t.forAuthor}" + t.sections.joinToString(",") { "${it.title}=${it.items.size}" }.let { if (it.isEmpty()) "" else " $it" }
        }
        "checklist" -> Checklist.parse(text).map { c ->
            "done=${c.done} total=${c.total} open=" + c.open.joinToString("|") { "${it.text}@${it.line}" }.ifEmpty { "-" }
        }
        "roadmap" -> Roadmap.parse(text, "").map { r ->
            "stages=${r.stages.size} done=${r.stagesDone} " +
                r.stages.joinToString(",") { "${it.title}:${it.flag.code}:${it.stepsDone}/${it.stepsTotal}" }
        }
        "status" -> StatusBlocks.parse(text, "").map { s ->
            s.blocks.joinToString(",") { "${it.name}=${it.text}" }.ifEmpty { "-" }
        }
        "pr" -> PrDescription.parse(text, "").map { p ->
            "template=${p.toJson()["template"]} " + p.sections.keys.joinToString(",").ifEmpty { "-" }
        }
        else -> fail("неизвестный формат корпуса: $format")
    }

    private fun <T, R> Outcome<T>.map(f: (T) -> R): Outcome<R> = when (this) {
        is Outcome.Failure -> this
        is Outcome.Success -> Outcome.success(f(value), diagnostics)
    }

    @Test
    fun everySampleBehavesAsExpected() {
        val formats = root.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue(formats.size >= 7, "в корпусе меньше форматов, чем разборщиков")
        var checked = 0
        val errors = mutableListOf<String>()
        for (format in formats) {
            for (kind in listOf("passes", "catches")) {
                val dir = File(format, kind)
                val samples = dir.listFiles { f -> f.isFile && f.extension != "expect" }?.sortedBy { it.name }.orEmpty()
                for (sample in samples) {
                    val e = expect(sample)
                    val name = "${format.name}/$kind/${sample.name}"
                    val r = parse(format.name, sample.readText(), e)
                    checked++
                    try {
                    if (kind == "passes") {
                        if (r !is Outcome.Success) fail("$name: ожидался разбор, получено ${r.diagnostics.map { it.render() }}")
                        assertEquals(e.getValue("summary"), r.value, name)
                        val warning = r.diagnostics.firstOrNull { it.severity == Severity.WARNING }?.code?.name
                        assertEquals(e["warning"], warning, "$name: предупреждение")
                    } else {
                        if (r !is Outcome.Failure) fail("$name: ожидался отказ ${e["error"]}, а разбор удался")
                        val d = r.diagnostics.first()
                        assertEquals(e.getValue("error"), d.code.name, name)
                        e["at"]?.let { at -> assertTrue(d.subject.orEmpty().contains(at), "$name: место «${d.subject}» без «$at»") }
                    }
                    } catch (x: AssertionError) {
                        errors += x.message ?: name
                    }
                }
            }
            assertTrue(File(format, "passes").listFiles()!!.isNotEmpty(), "${format.name}: нет образцов passes")
        }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
        assertTrue(checked >= 30, "образцов $checked — корпус обеднел")
    }
}
