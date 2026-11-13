package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Readiness
import com.dpsmpx.dpmeta.metrics.Readiness.Criterion
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.metrics.SourceError
import com.dpsmpx.dpmeta.metrics.SourceSection
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Sync
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Стадия и шлюз готовности (D-64) и метрики PR этапа 4 (ТЗ, §8): у каждого
 * критерия — образец, который он ловит, и чистый образец; неизвестное — не
 * «нет».
 */
class ReadinessTest {
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())
    private val hour = 3_600_000L

    private fun section(kind: String, json: String) = kind to SourceSection(kind, "test", now, (JsonParser().parse(json) as Outcome.Success).value as JsonObject)

    private fun project(vararg sections: Pair<String, SourceSection>, errors: List<SourceError> = emptyList()) =
        ProjectState("P", "core", sections.toMap().toSortedMap(), errors)

    private val green = section("merged", """{"pulls":[{"number":1,"mergedAt":${now.epochMillis - 10 * hour},"createdAt":${now.epochMillis - 12 * hour},"checks":"green"}]}""")
    private val roadmapDone = section("roadmap", """{"stages":[{"title":"Этап 0","flag":"done","stepsDone":2,"stepsTotal":2},{"title":"Этап 1","flag":"open","stepsDone":3,"stepsTotal":3}]}""")
    private val todoClean = section("todo", """{"open":2,"forAuthor":0,"sections":[]}""")
    private val decisions = section("decisions", """{"count":4,"entries":[]}""")
    private val noPulls = section("pulls", """{"pulls":[]}""")

    private fun clean() = project(green, roadmapDone, todoClean, decisions, noPulls)

    @Test
    fun cleanProjectPassesEveryCriterion() {
        val gate = Readiness.gate(clean(), now)
        assertEquals(Criterion.entries.associateWith { Measure.observed(true) }, gate.results)
        assertEquals(Measure.observed(true), gate.ready)
        assertEquals(Criterion.entries.size, gate.passed)
        val stage = gate.stage.orNull()!!
        assertEquals(2, stage.number, "текущий — первый не сделанный")
        assertEquals("Этап 1", stage.title)
    }

    @Test
    fun eachCriterionCatchesItsOwnCase() {
        fun failing(p: ProjectState) = Readiness.gate(p, now).blocking
        assertEquals(listOf(Criterion.DECISIONS), failing(project(green, roadmapDone, todoClean, section("decisions", """{"count":0,"entries":[]}"""), noPulls)))
        assertEquals(
            listOf(Criterion.PROCESS, Criterion.AUTHOR),
            failing(project(green, roadmapDone, decisions, noPulls, errors = listOf(SourceError("todo", "GITHUB_NOT_FOUND", "нет", now)))),
            "файла нет (404) — «нет», а не неизвестно",
        )
        assertEquals(listOf(Criterion.CHECKS), failing(project(section("merged", """{"pulls":[{"number":1,"mergedAt":1,"checks":"red"}]}"""), roadmapDone, todoClean, decisions, noPulls)))
        assertEquals(listOf(Criterion.AUTHOR), failing(project(green, roadmapDone, section("todo", """{"open":2,"forAuthor":1,"sections":[]}"""), decisions, noPulls)))
        val old = now.epochMillis - Rules.PR_WAITING_LIMIT.inWholeMilliseconds - hour
        assertEquals(listOf(Criterion.PULLS), failing(project(green, roadmapDone, todoClean, decisions, section("pulls", """{"pulls":[{"number":5,"draft":false,"createdAt":$old,"updatedAt":$old}]}"""))))
        assertEquals(
            emptyList(),
            failing(project(green, roadmapDone, todoClean, decisions, section("pulls", """{"pulls":[{"number":5,"draft":false,"createdAt":$old,"updatedAt":${now.epochMillis}}]}"""))),
            "черновик, ставший готовым час назад, ждёт час, а не с открытия (правила 3)",
        )
        assertEquals(
            emptyList(),
            failing(project(green, roadmapDone, todoClean, decisions, section("pulls", """{"pulls":[{"number":5,"draft":true,"createdAt":$old,"updatedAt":$old}]}"""))),
            "черновик автора не ждёт",
        )
        assertEquals(listOf(Criterion.STAGE), failing(project(green, section("roadmap", """{"stages":[{"title":"Этап 0","flag":"active","stepsDone":1,"stepsTotal":2}]}"""), todoClean, decisions, noPulls)))
    }

    @Test
    fun stageWithoutStepsIsUnknownAndInapplicableCriteriaAreOut() {
        val table = section("roadmap", """{"stages":[{"title":"Этап 2","flag":"active","stepsDone":0,"stepsTotal":0}]}""")
        val gate = Readiness.gate(project(green, table, todoClean, decisions, noPulls), now)
        assertIs<Measure.Unknown>(gate.results[Criterion.STAGE], "ROADMAP таблицей — без шагов: неизвестно, а не «нет»")
        assertTrue(gate.blocking.isEmpty())
        val subsystem = ProjectState("S", "core", mapOf(roadmapDone, todoClean).toSortedMap(), emptyList(), skipped = setOf("pulls", "merged", "branches", "decisions"))
        val g = Readiness.gate(subsystem, now)
        assertEquals(listOf(Criterion.DECISIONS, Criterion.CHECKS, Criterion.PULLS), g.notApplicable)
        assertEquals(Measure.observed(true), g.ready, "неприменимое в итог не входит: подсистема может быть готова")
    }

    @Test
    fun unknownIsNotNo() {
        val gate = Readiness.gate(project(), now)
        assertIs<Measure.Unknown>(gate.ready, "без данных — неизвестно")
        assertTrue(gate.blocking.isEmpty())
        assertEquals(0, gate.passed)
        assertIs<Measure.Unknown>(gate.stage)
    }

    @Test
    fun pullMetricsFromTheSameResponses() {
        val p = project(
            section(
                "pulls",
                """{"pulls":[{"number":1,"draft":true,"updatedAt":${now.epochMillis}},{"number":2,"draft":false,"createdAt":${now.epochMillis - 50 * hour},"updatedAt":${now.epochMillis}}]}""",
            ),
            section(
                "merged",
                """{"pulls":[{"number":3,"mergedAt":${now.epochMillis},"createdAt":${now.epochMillis - hour}},{"number":4,"mergedAt":${now.epochMillis},"createdAt":${now.epochMillis - 3 * hour}},{"number":5,"mergedAt":${now.epochMillis}}]}""",
            ),
        )
        assertEquals(Measure.observed(1L), p.draftPulls)
        assertEquals(Measure.observed(1L to 0L), p.waitingAuthor(now), "ждёт с последнего обновления (правила 3)")
        assertEquals(Measure.observed(60L to 180L), p.leadTime, "медиана и P90 по ближайшему рангу; без времени открытия — не в счёт")
    }

    @Test
    fun corpusStateCarriesNewPointsAndSyncHealth() {
        val root = File(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem")).toPath()
        val reg = (RegistryFormat.parse(Files.readString(root.resolve("registry.toml"))) as Outcome.Success).value
        val result = Sync.run(Sync.plan(reg), DirectoryReader(root), ManualClock(now))
        val state = EcosystemState.derive(reg, result.observations, result.failures, null, now)
        val alpha = state.project("Alpha")!!
        assertEquals(Measure.observed(24 * 60L to 24 * 60L), alpha.leadTime, "влитый #2 — сутки от открытия")
        assertEquals(Measure.observed(false), alpha.lastChecksGreen)
        val points = state.points().filter { it.subject == "Alpha" }.associate { it.metric to it.value }
        assertEquals(2L, points["repo.stage"], "этап 1 из двух — второй по порядку")
        assertEquals(0L, points["repo.gate.ready"], "красные проверки держат шлюз")
        assertEquals(1L, points["repo.pr.open.drafts"])
        assertEquals(100L, state.syncHealth.orNull(), "404 подсистемы — ответ, а не неудача")
        assertEquals(setOf("pulls", "merged", "branches", "history"), state.project("DPmeta")!!.skipped, "подсистема: PR, ветки и история (D-66) — у DPmetaApp")
        assertTrue(state.points().none { it.subject == "DPmeta" && it.metric == "repo.gate.passed" } || Readiness.gate(state.project("DPmeta")!!, now).anyKnown)
        val back = (EcosystemState.fromJson(state.toJson()) as Outcome.Success).value
        assertEquals(state.syncHealth, back.syncHealth, "доля переживает запись состояния")
        assertEquals(state.project("DPmeta")!!.skipped, back.project("DPmeta")!!.skipped, "неприменимое переживает запись состояния")
        assertTrue(state.points().all { it.rulesVersion == Rules.RULES_VERSION })
    }
}
