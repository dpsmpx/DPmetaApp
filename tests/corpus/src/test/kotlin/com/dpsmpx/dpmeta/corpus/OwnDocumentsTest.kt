package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.formats.DecisionLog
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
