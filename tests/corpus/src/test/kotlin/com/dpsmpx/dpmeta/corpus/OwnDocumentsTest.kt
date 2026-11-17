package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.formats.DecisionLog
import com.dpsmpx.dpmeta.formats.BriefFormat
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.CommandFormat
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.formats.CommandSpec
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.formats.Roadmap
import com.dpsmpx.dpmeta.formats.StatusBlocks
import com.dpsmpx.dpmeta.formats.TodoList
import com.dpsmpx.dpmeta.model.Place
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Свои документы DPmetaApp разбираются теми же разборщиками (ТЗ, I-01,
 * I-06): реестр подсистемы DPmeta, журнал решений, `TODO.md`, ROADMAP и
 * блок состояния README. Падение — формат ушёл от разборщика.
 */
class OwnDocumentsTest {
    private val repo = File(System.getProperty("corpus.repo") ?: error("нет corpus.repo"))

    private fun text(path: String) = File(repo, path).readText()

    private fun <T> Outcome<T>.ok(what: String): T = when (this) {
        is Outcome.Success -> value
        is Outcome.Failure -> fail("$what: ${diagnostics.map { it.render() }}")
    }

    @Test
    fun registryOfDpmetaParses() {
        val r = RegistryFormat.parse(text("dpmeta/tools/ecosystem.toml")).ok("реестр DPmeta")
        val app = r.byName("DPmetaApp") ?: fail("в реестре нет DPmetaApp")
        assertEquals(Place.CONTROL, app.place)
        assertEquals("dpsmpx/DPmetaApp", app.slug.toString())
        assertTrue(r.projects.size >= 10, "проектов в реестре: ${r.projects.size}")
    }

    @Test
    fun ownDecisionLogParsesWithRegistryPattern() {
        val r = RegistryFormat.parse(text("dpmeta/tools/ecosystem.toml")).ok("реестр DPmeta")
        val source = r.byName("DPmetaApp")!!.decisions ?: fail("у DPmetaApp в реестре нет журнала")
        val log = DecisionLog.parse(text(source.path), source.regex().ok("шаблон")).ok("журнал решений")
        val ids = log.entries.map { it.id }
        assertEquals("D-01", ids.first())
        val numbers = ids.map { it.removePrefix("D-").toInt() }
        assertEquals((1..numbers.size).toList(), numbers, "номера журнала — подряд")
        assertTrue(log.entries.all { it.status != null }, "у каждого решения — статус")
    }

    /** Шаблоны поручений (F-23): шесть из ТЗ на месте, каждый разбирается и собирается без пустых мест. */
    @Test
    fun ownBriefsParseAndRender() {
        val files = File(repo, "config/briefs").listFiles { f -> f.extension == "md" }.orEmpty().sortedBy { it.name }
        val keys = files.map { it.nameWithoutExtension }.toSet()
        for (k in listOf("continue-branch", "roadmap-task", "answer-question", "fix-checks", "dpmeta-pass", "dpmeta-doc-audit")) {
            assertTrue(k in keys, "нет шаблона $k")
        }
        val builtIn = mapOf("project" to "DPmetaApp", "repo" to "dpsmpx/DPmetaApp", "base" to "main")
        for (f in files) {
            val b = BriefFormat.parse(f.readText(), f.nameWithoutExtension, "config/briefs/${f.name}").ok(f.name)
            val text = b.render(builtIn, b.fields.associate { it.name to "значение ${it.name}" }).ok("${f.name}: сборка")
            assertTrue("{{" !in text && "}}" !in text, "${f.name}: в тексте осталось место")
            b.fields.forEach { assertTrue("значение ${it.name}" in text, "${f.name}: поле ${it.name} не подставлено") }
        }
    }

    /**
     * Реестр команд (F-40, F-43; D-82): разбирается, проекты — из реестра DPmeta, не из
     * архива; уровень 1 — только `repo.ff`, и он — для любого проекта.
     */
    @Test
    fun ownCommandsParseAndNameRegistryProjects() {
        val r = RegistryFormat.parse(text("dpmeta/tools/ecosystem.toml")).ok("реестр DPmeta")
        val commands = CommandFormat.parse(text("config/commands.toml"), "config/commands.toml").ok("config/commands.toml").commands
        assertTrue(commands.size >= 5, "команд: ${commands.size}")
        for (c in commands) {
            if (c.anyProject) continue
            val p = r.byName(c.project) ?: fail("${c.id}: проекта ${c.project} нет в реестре")
            assertTrue(p.place.shown, "${c.id}: проект ${c.project} — в архиве")
        }
        assertEquals(listOf("repo.ff"), commands.filter { it.level == 1 }.map { it.id })
        assertTrue(commands.first { it.id == "repo.ff" }.project == CommandSpec.ANY)
    }

    /**
     * Каталог событий и правила тревог (F-37, F-51; D-85): разбираются; в каталоге —
     * все типы ТЗ, §8.1, дополнение D-142 и каждый вид ленты; правила F-37 сохранены.
     */
    @Test
    fun ownEventsAndAlertsParse() {
        val catalog = EventCatalogFormat.parse(text("config/events.toml"), "config/events.toml").ok("config/events.toml")
        val tz = text("docs/direction/TECHNICAL_TASK.md")
        val section = tz.substringAfter("### 8.1 Каталог событий").substringBefore("\n## ")
        val named = Regex("^\\| ([^|]+) \\|", RegexOption.MULTILINE).findAll(section)
            .flatMap { m -> Regex("`([a-z][a-z0-9_.]*)`").findAll(m.groupValues[1]).map { it.groupValues[1] } }.toSet()
        assertTrue(named.size >= 40, "типов в §8.1: ${named.size}")
        val ai = setOf("ai.info", "ai.progress", "ai.success", "ai.system", "ai.warning", "ai.blocked", "ai.failure")
        assertEquals(named + ai, catalog.types.map { it.type }.toSet(), "каталог — ровно типы ТЗ, §8.1 и дополнение D-142; обязательных категорий в нём нет")
        for (t in com.dpsmpx.dpmeta.metrics.Feed.TYPES.values) assertTrue(catalog.type(t) != null, "вид ленты $t — не в каталоге")
        val rules = AlertFormat.parse(text("config/alerts.toml"), catalog, "config/alerts.toml").ok("config/alerts.toml").rules
        assertEquals(23, rules.size, "14 правил F-37, 6 обычных правил ИИ D-142, уведомления Claude из слушателя D-155, ответ чата и PR маршрута D-161")
        assertTrue(rules.any { it.id == "app.claude" && it.on == "app.notification" }, "правило слушателя D-155")
        assertTrue(rules.any { it.id == "chat.done" && it.on == "chat.answer.done" } && rules.any { it.id == "routine.pr" && it.on == "pr.opened" }, "правила F-58 (D-161)")
        assertTrue(rules.all { it.enabled })
        assertEquals(listOf("claude.input"), rules.filter { !it.quiet }.map { it.id }, "звучит в тихие часы — только «Claude ждёт»")
    }

    @Test
    fun ownTodoRoadmapAndReadmeParse() {
        val todo = TodoList.parse(text("TODO.md")).ok("TODO.md")
        assertTrue(todo.sections.any { it.forAuthor }, "в TODO.md нет раздела «За автором»")
        val roadmap = Roadmap.parse(text("ROADMAP/ROADMAP.md")).ok("ROADMAP")
        assertTrue(roadmap.stages.size >= 9, "этапов: ${roadmap.stages.size}")
        assertTrue(roadmap.stages.first { it.title.startsWith("Этап 0") }.flag == com.dpsmpx.dpmeta.formats.Roadmap.Flag.DONE)
        StatusBlocks.parse(text("README.md")).ok("README")
    }
}
