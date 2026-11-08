package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.Place
import com.dpsmpx.dpmeta.model.ProjectEntry
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.model.Registry
import com.dpsmpx.dpmeta.sync.Got
import com.dpsmpx.dpmeta.sync.SourceKind
import com.dpsmpx.dpmeta.sync.SourceReader
import com.dpsmpx.dpmeta.sync.Sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Счётчики документов DPmeta (`eco.*`, ТЗ, §8): только у проекта DPmeta;
 * файла нет — его поля нет (неизвестно, а не ноль); срезы — по каталогу,
 * если источник его показывает.
 */
class DpmetaCountersTest {
    private fun project(name: String, slug: String, dir: String? = null) =
        ProjectEntry(name, RepoRef.parse(slug).valueOrNull()!!, null, Place.SUPPORT, emptyList(), emptyList(), null, emptyList(), dir)

    private val dpmeta = project("DPmeta", "dpsmpx/DPmeta", "dpmeta")
    private val other = project("Alpha", "dpsmpx/Alpha")

    private class Files(val files: Map<String, String>, val reports: List<String>?) : SourceReader {
        override val source = ObservationSource.GITHUB
        override fun hasActivity(project: ProjectEntry) = false
        override fun pulls(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = error("не нужно")
        override fun closed(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = error("не нужно")
        override fun branches(project: ProjectEntry): Outcome<Got<List<Branch>>> = error("не нужно")
        override fun commit(project: ProjectEntry, sha: String): Outcome<CommitInfo> = error("не нужно")
        override fun file(project: ProjectEntry, path: String): Outcome<Got<String>> =
            files[path]?.let { Outcome.success(Got(it, Hashing.sha256(it))) } ?: Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "нет", path)
        override fun names(project: ProjectEntry, dir: String): Outcome<List<String>>? = reports?.let { Outcome.success(it) }
    }

    private fun run(reader: SourceReader): JsonObject? {
        val tasks = Sync.plan(Registry(1, listOf(dpmeta, other))).filter { it.kind == SourceKind.DPMETA }
        assertEquals(listOf("DPmeta"), tasks.map { it.project.name }, "только у DPmeta")
        val r = Sync.run(tasks, reader, ManualClock(Timestamp(0)))
        return r.observations.singleOrNull()?.payload as? JsonObject
    }

    private fun JsonObject.n(k: String) = (this[k] as? JsonNumber)?.toLongExact()

    @Test
    fun countsAllFourAndUnknownStaysUnknown() {
        val full = run(Files(mapOf(
            "docs/CONTRACTS.md" to "## C-01 — а\n\n## C-02 — б\n",
            "docs/RISKS.md" to "## RISK-01 — а\n- **Статус:** открыт.\n\n## RISK-02 — б\n- **Статус:** закрыт.\n",
            "docs/SYNC_QUEUE.md" to "### SQ-01 — а\n",
        ), listOf("2026-11-01-xref.md", "2026-11-01-activity.md", "README.txt")))!!
        assertEquals(listOf(2L, 2L, 1L, 1L, 2L), listOf("contracts", "risks", "risksOpen", "syncQueue", "reports").map { full.n(it) })
        val partial = run(Files(mapOf("docs/RISKS.md" to "## RISK-01 — а\n"), null))!!
        assertEquals(null, partial.n("contracts"), "файла нет — поля нет")
        assertEquals(null, partial.n("reports"), "каталог не показан — срезов неизвестно")
        assertEquals(1L, partial.n("risksOpen"), "без статуса — открыт")
        assertEquals(null, run(Files(emptyMap(), listOf("x.md"))), "нет ни одного документа — наблюдения нет")
    }

    @Test
    fun ecoPointsComeFromTheDpmetaSection() {
        val reader = Files(mapOf("docs/CONTRACTS.md" to "## C-01 — а\n", "docs/SYNC_QUEUE.md" to "### SQ-01 — а\n### SQ-02 — б\n"), emptyList())
        val tasks = Sync.plan(Registry(1, listOf(dpmeta, other))).filter { it.kind == SourceKind.DPMETA }
        val obs = Sync.run(tasks, reader, ManualClock(Timestamp(0))).observations
        val state = EcosystemState.derive(Registry(1, listOf(dpmeta, other)), obs, emptyList(), null, Timestamp(0))
        val points = state.points().filter { it.subject == "eco" }.associate { it.metric to it.value }
        assertEquals(1L, points["eco.contracts"])
        assertEquals(2L, points["eco.sync_queue"])
        assertEquals(0L, points["eco.reports"], "каталог пуст — это ответ, 0")
        assertTrue("eco.risks_open" !in points, "RISKS.md нет — точки нет")
        assertIs<Measure.Unknown>(state.dpmetaCount("risksOpen"))
    }
}
