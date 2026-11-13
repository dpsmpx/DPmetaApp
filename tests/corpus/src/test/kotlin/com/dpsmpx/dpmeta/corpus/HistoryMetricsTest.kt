package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.metrics.SourceSection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Метрики из истории клона (D-66, ТЗ, §8): доля ИИ за окно, ветки впереди
 * основной, новые решения за неделю; нет истории или коммитов — неизвестно,
 * а не ноль.
 */
class HistoryMetricsTest {
    // 8 ноября 2026, 12:00 по +03:00.
    private val now = Timestamp(Instant.parse("2026-11-08T09:00:00Z").toEpochMilli())

    private fun project(json: String?) = ProjectState(
        "P", "core",
        json?.let { mapOf("history" to SourceSection("history", "clone.history.v1", now, (JsonParser().parse(it) as Outcome.Success).value as JsonObject)) }.orEmpty().toSortedMap(),
        emptyList(),
    )

    private val history = """
        {"complete":true,"first":"2026-09-01",
         "days":[["2026-09-01",10,10,0,10,0,0],["2026-10-10",4,1,3,4,0,1],["2026-11-01",6,3,3,5,1,2],["2026-11-08",2,2,0,1,1,0]],
         "decisions":[["2026-09-01",10],["2026-10-30",14],["2026-11-05",17]],
         "ahead":[{"name":"work","commits":2,"last":${now.epochMillis}},{"name":"idea","commits":1,"last":${now.epochMillis}}]}
    """.trimIndent()

    @Test
    fun aiShareCountsOnlyTheWindow() {
        // Окно 30 дней — с 10 октября по 8 ноября: 1 + 3 + 2 = 6 ИИ из 12; 1 сентября — за окном.
        assertEquals(30, Rules.AI_SHARE_DAYS)
        assertEquals(Measure.observed(50L), project(history).aiShare(now))
    }

    @Test
    fun branchesAndDecisions() {
        val p = project(history)
        assertEquals(Measure.observed(2L to 3L), p.branchesAhead)
        // За 7 дней (с 1 ноября): было 14 на конец 1-го, стало 17.
        assertEquals(Measure.observed(3L), p.decisionsNew(now))
    }

    @Test
    fun unknownIsNotZero() {
        val none = project(null)
        assertIs<Measure.Unknown>(none.aiShare(now), "нет истории — неизвестно")
        assertIs<Measure.Unknown>(none.branchesAhead)
        assertIs<Measure.Unknown>(none.decisionsNew(now))
        val quiet = project("""{"complete":true,"days":[["2026-01-01",5,5,0,5,0,0]],"decisions":[],"ahead":[]}""")
        assertIs<Measure.Unknown>(quiet.aiShare(now), "коммитов за окно нет — не 0 %")
        assertIs<Measure.Unknown>(quiet.decisionsNew(now), "журнала нет — неизвестно")
        assertEquals(Measure.observed(0L to 0L), quiet.branchesAhead, "веток впереди нет — это ответ, 0")
    }

    @Test
    fun journalStartedInsideTheWindowIsAllNew() {
        val p = project("""{"complete":true,"days":[],"decisions":[["2026-11-06",3]],"ahead":[]}""")
        assertEquals(Measure.observed(3L), p.decisionsNew(now))
    }

    @Test
    fun pointsCarryTheNewMetrics() {
        val state = EcosystemState(now, Rules.RULES_VERSION, listOf(project(history)))
        val names = state.points().map { it.metric }.toSet()
        for (m in listOf("repo.commits.ai_share", "repo.branches.unmerged", "repo.branches.unmerged.commits", "repo.decisions.new")) {
            assertTrue(m in names, m)
        }
        assertTrue(state.points().all { it.rulesVersion == 5 }, "правила 5")
    }
}
