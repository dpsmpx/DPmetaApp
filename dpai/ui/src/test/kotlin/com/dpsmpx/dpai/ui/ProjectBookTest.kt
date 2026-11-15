package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Рабочие проекты (D-179): список, выбранный проект, каталог журнала и чтение прежнего одиночного документа. */
class ProjectBookTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(77))
    private val target = ids.next(IdKinds.TARGET)
    private fun project(name: String) = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target, "/root/$name", "/root/trees-$name", "main", emptyList())

    @Test
    fun legacySingleProjectBecomesFirstWithLiveJournal() {
        val old = project("galdhold")
        val read = assertIs<Outcome.Success<Projects>>(Projects.fromJson(ProjectBook.toJson(old))).value
        assertEquals(listOf(old), read.projects)
        assertEquals(old, read.current)
        assertEquals("live", read.journalDir(old.project), "прежний контур — на прежнем месте журнала")
    }

    @Test
    fun listKeepsSelectionAndJournalPlaces() {
        val a = project("a")
        val b = project("b")
        val c = project("c")
        var p = assertIs<Outcome.Success<Projects>>(Projects.EMPTY.put(a)).value
        assertEquals(a.project, p.liveProject, "первый проект без прежнего журнала получает live/")
        p = assertIs<Outcome.Success<Projects>>(p.put(b)).value
        assertEquals(b, p.current, "добавленный — выбран")
        assertEquals("live-projects/${b.project.value}", p.journalDir(b.project))
        p = assertIs<Outcome.Success<Projects>>(p.select(a.project)).value
        assertEquals(a, p.current)
        // Убранный проект с live/ этот каталог не освобождает: новый проект пишет в свой.
        p = p.remove(a.project)
        assertEquals(b, p.current)
        p = assertIs<Outcome.Success<Projects>>(p.put(c)).value
        assertEquals("live-projects/${c.project.value}", p.journalDir(c.project))
        assertEquals(a.project, p.liveProject)
        val back = assertIs<Outcome.Success<Projects>>(Projects.fromJson(Projects.toJson(p))).value
        assertEquals(p, back)
        // Замена по id не добавляет второй; тот же репозиторий вторым проектом — отказ.
        val edited = b.copy(integrationBranch = "develop")
        assertEquals(listOf(edited, c), assertIs<Outcome.Success<Projects>>(p.put(edited)).value.projects)
        assertIs<Outcome.Failure>(p.put(project("x").copy(repository = c.repository)))
        assertIs<Outcome.Failure>(p.put(project("x").copy(root = c.root + "/")), "тот же каталог на том же target — дубликат, хотя id новые")
        assertIs<Outcome.Failure>(p.select(ids.next(IdKinds.PROJECT)))
        assertNull(Projects.EMPTY.current)
    }

    @Test
    fun brokenDocumentsAreRefused() {
        val a = project("a")
        val twice = JsonObject(Projects.toJson(Projects(listOf(a, a))).fields)
        assertIs<Outcome.Failure>(Projects.fromJson(twice), "проект дважды")
        assertIs<Outcome.Failure>(Projects.fromJson(JsonObject(Projects.toJson(Projects(listOf(a))).fields + ("version" to JsonNumber.of(2)))))
        assertIs<Outcome.Failure>(Projects.fromJson(JsonObject(mapOf("format" to JsonString("dpai.other"), "version" to JsonNumber.of(1)))))
        var many = Projects.EMPTY
        repeat(Projects.MAX) { many = assertIs<Outcome.Success<Projects>>(many.put(project("p$it"))).value }
        assertIs<Outcome.Failure>(many.put(project("лишний")))
    }
}
