package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.ProjectCatalog
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

    private fun registry(): ProjectCatalog = corpusProjects(root)

    private fun sync(): Pair<EcosystemState, com.dpsmpx.dpmeta.sync.SyncResult> {
        val reg = registry()
        val result = Sync.run(Sync.plan(reg), DirectoryReader(root), ManualClock(now))
        return EcosystemState.derive(reg, result.observations, result.failures, null, now) to result
    }

    @Test
    fun corpusSyncBuildsStateOfEveryShownProject() {
        val (state, result) = sync()
        assertEquals(listOf("Alpha", "Beta", "Pult", "DPmeta"), state.projects.map { it.name }, "архив не показывается, порядок — реестра")
        // Строка управления (D-46): клон, в котором живут подсистемы, — и сама проект с файлами.
        val pult = state.project("Pult")!!
        assertEquals(emptyList(), pult.errors.map { it.kind })
        val alpha = state.project("Alpha")!!
        assertEquals(Measure.observed(1L), alpha.openPulls)
        assertEquals(Measure.observed(true), alpha.working(now), "ветка за 24 часа — работает")
        val beta = state.project("Beta")!!
        assertIs<Measure.Unknown>(beta.working(now), "без веток и PR — неизвестно, а не «свободен»")
        val meta = state.project("DPmeta")!!
        assertEquals(setOf("readme"), meta.errors.map { it.kind }.toSet())
        assertTrue("dpmeta" !in meta.sections, "имя проекта не включает особый источник")
        assertTrue(meta.errors.all { it.code == DiagCode.ENTITY_NOT_FOUND.name })
        assertEquals(Measure.observed(1L), state.activeProjects)
        assertEquals(0, result.deferred)
        val points = state.points()
        assertTrue(points.none { it.subject == "Beta" && it.metric == "repo.activity.state" }, "неизвестное — без точки")
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
        val failure = com.dpsmpx.dpmeta.model.SourceFailure("Alpha", "readme", com.dpsmpx.dpai.core.diagnostics.Diagnostic.error(DiagCode.NETWORK_UNREACHABLE, "нет сети", "Alpha"), later)
        val second = EcosystemState.derive(reg, emptyList(), listOf(failure), first, later)
        val alpha = second.project("Alpha")!!
        assertEquals(first.project("Alpha")!!.sections["readme"], alpha.sections["readme"], "прежний README не перекрыт пустым")
        assertEquals(listOf("readme"), alpha.errors.map { it.kind })
    }

    @Test
    fun budgetDefersTheRestWithAMark() {
        val reg = registry()
        val reader = object : com.dpsmpx.dpmeta.sync.SourceReader by DirectoryReader(root) {
            var calls = 0

            override fun file(project: com.dpsmpx.dpmeta.model.SyncProject, path: String) =
                if (++calls > 2) Outcome.failure(DiagCode.RUN_BUDGET_EXHAUSTED, "бюджет", project.name) else DirectoryReader(root).file(project, path)
        }
        val tasks = Sync.plan(reg)
        assertEquals(SourceKind.PULLS, tasks.first().kind, "сначала PR и ветки (ТЗ, §6.4)")
        val r = Sync.run(tasks, reader, ManualClock(now))
        assertTrue(r.deferred > 0)
        assertTrue(r.failures.count { it.diagnostic.code == DiagCode.RUN_BUDGET_EXHAUSTED } == r.deferred + 1)
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
            assertEquals(state, back)
            val n = (store.appendHistory(state.points()) as Outcome.Success).value
            store.appendHistory(state.points())
            val history = (store.history() as Outcome.Success).value
            assertEquals(2 * n, history.size, "история только добавляется")
            assertEquals(2, (store.history("app.sync.health") as Outcome.Success).value.size)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
