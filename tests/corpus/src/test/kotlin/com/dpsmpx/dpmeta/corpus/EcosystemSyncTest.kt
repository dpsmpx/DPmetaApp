package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.Registry
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.SourceKind
import com.dpsmpx.dpmeta.sync.Sync
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Синхронизация с корпуса (ТЗ, §6.4; выход этапа 2): синтетическая
 * экосистема `ecosystem/` → наблюдения → состояние и точки истории; хранение
 * на `dpai/storage`; детерминизм (N-01).
 */
class EcosystemSyncTest {
    private val root = File(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem")).toPath()
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())

    private fun registry(): Registry = (RegistryFormat.parse(Files.readString(root.resolve("registry.toml"))) as Outcome.Success).value

    private fun sync(): Pair<EcosystemState, com.dpsmpx.dpmeta.sync.SyncResult> {
        val reg = registry()
        val result = Sync.run(Sync.plan(reg), DirectoryReader(root), ManualClock(now))
        return EcosystemState.derive(reg, result.observations, result.failures, null, now) to result
    }

    @Test
    fun corpusSyncBuildsStateOfEveryShownProject() {
        val (state, result) = sync()
        assertEquals(listOf("Alpha", "Beta", "DPmeta"), state.projects.map { it.name }, "архив не показывается, порядок — реестра")
        val alpha = state.project("Alpha")!!
        assertEquals(Measure.observed(2L), alpha.decisions)
        assertEquals(Measure.observed(3L), alpha.todoOpen)
        assertEquals(Measure.observed(2L), alpha.forAuthor)
        assertEquals(Measure.observed(50L), alpha.roadmapPercent)
        assertEquals(Measure.observed(1L), alpha.openPulls)
        assertEquals(Measure.observed(true), alpha.working(now), "ветка за 24 часа — работает")
        val beta = state.project("Beta")!!
        assertEquals(Measure.observed(2L), beta.decisions, "шаблон Р\\d+ из реестра")
        assertEquals(Measure.observed(50L), beta.roadmapPercent, "ROADMAP по второму пути-кандидату")
        assertIs<Measure.Unknown>(beta.working(now), "без веток и PR — неизвестно, а не «свободен»")
        val meta = state.project("DPmeta")!!
        assertEquals(Measure.observed(1L), meta.decisions, "подсистема читается из каталога DPmetaApp")
        assertEquals(setOf("readme", "roadmap"), meta.errors.map { it.kind }.toSet())
        assertTrue(meta.errors.all { it.code == DiagCode.ENTITY_NOT_FOUND.name })
        assertEquals(Measure.observed(1L), state.activeProjects)
        assertEquals(Measure.observed(3L), state.questionsOpen)
        assertEquals(0, result.deferred)
        val points = state.points()
        assertTrue(points.none { it.subject == "Beta" && it.metric == "repo.activity.state" }, "неизвестное — без точки")
        assertTrue(points.any { it.metric == "eco.questions_open" && it.value == 3L })
    }

    @Test
    fun stateDoesNotDependOnObservationOrder() {
        val reg = registry()
        val result = Sync.run(Sync.plan(reg), DirectoryReader(root), ManualClock(now))
        val reference = CanonicalJson.write(EcosystemState.derive(reg, result.observations, result.failures, null, now).toJson())
        repeat(5) { seed ->
            val shuffled = result.observations.shuffled(Random(seed))
            val s = EcosystemState.derive(reg, shuffled, result.failures.shuffled(Random(seed)), null, now)
            assertEquals(reference, CanonicalJson.write(s.toJson()), "порядок $seed")
        }
    }

    @Test
    fun failedSourceKeepsPreviousSection() {
        val reg = registry()
        val (first, _) = sync()
        val later = Timestamp(now.epochMillis + 3_600_000)
        val failure = Triple("Alpha", "todo", com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.NETWORK_UNREACHABLE, "нет сети", "Alpha"))
        val second = EcosystemState.derive(reg, emptyList(), listOf(failure), first, later)
        val alpha = second.project("Alpha")!!
        assertEquals(Measure.observed(3L), alpha.todoOpen, "прежнее не перекрыто пустым")
        assertEquals(listOf("todo"), alpha.errors.map { it.kind })
    }

    @Test
    fun budgetDefersTheRestWithAMark() {
        val reg = registry()
        val reader = object : com.dpsmpx.dpmeta.sync.SourceReader by DirectoryReader(root) {
            var calls = 0

            override fun file(project: com.dpsmpx.dpmeta.model.ProjectEntry, path: String) =
                if (++calls > 2) Outcome.failure(DiagCode.RUN_BUDGET_EXHAUSTED, "бюджет", project.name) else DirectoryReader(root).file(project, path)
        }
        val tasks = Sync.plan(reg)
        assertEquals(SourceKind.PULLS, tasks.first().kind, "сначала PR и ветки (ТЗ, §6.4)")
        val r = Sync.run(tasks, reader, ManualClock(now))
        assertTrue(r.deferred > 0)
        assertTrue(r.failures.count { it.third.code == DiagCode.RUN_BUDGET_EXHAUSTED } == r.deferred + 1)
    }

    @Test
    fun storeKeepsStateAndAppendsHistory() {
        val dir = Files.createTempDirectory("dpmeta-store")
        try {
            val store = Store(NioVfs(dir), ManualClock(now), RandomSource.seeded(7))
            assertNull((store.readState() as Outcome.Success).value)
            val (state, _) = sync()
            assertIs<Outcome.Success<*>>(store.writeState(state))
            val back = (store.readState() as Outcome.Success).value!!
            assertEquals(Measure.observed(2L), back.project("Alpha")!!.decisions)
            val n = (store.appendHistory(state.points()) as Outcome.Success).value
            store.appendHistory(state.points())
            val history = (store.history() as Outcome.Success).value
            assertEquals(2 * n, history.size, "история только добавляется")
            assertEquals(2, (store.history("eco.questions_open") as Outcome.Success).value.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
