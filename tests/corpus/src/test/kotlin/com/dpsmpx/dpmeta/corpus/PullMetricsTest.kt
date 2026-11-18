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

/** Метрики PR не зависят от снятого шлюза готовности (D-176). */
class PullMetricsTest {
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())
    private val hour = 3_600_000L

    private fun section(kind: String, json: String) = kind to SourceSection(kind, "test", now, (JsonParser().parse(json) as Outcome.Success).value as JsonObject)

    private fun project(vararg sections: Pair<String, SourceSection>, errors: List<SourceError> = emptyList()) =
        ProjectState("P", "core", sections.toMap().toSortedMap(), errors)

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
        assertEquals(1L, points["repo.pr.open.drafts"])
        assertEquals(100L, state.syncHealth.orNull(), "404 подсистемы — ответ, а не неудача")
        assertEquals(setOf("pulls", "merged", "branches", "history"), state.project("DPmeta")!!.skipped, "подсистема: PR, ветки и история (D-66) — у DPmetaApp")
        val back = (EcosystemState.fromJson(state.toJson()) as Outcome.Success).value
        assertEquals(state.syncHealth, back.syncHealth, "доля переживает запись состояния")
        assertEquals(state.project("DPmeta")!!.skipped, back.project("DPmeta")!!.skipped, "неприменимое переживает запись состояния")
        assertTrue(state.points().all { it.rulesVersion == Rules.RULES_VERSION })
    }
}
