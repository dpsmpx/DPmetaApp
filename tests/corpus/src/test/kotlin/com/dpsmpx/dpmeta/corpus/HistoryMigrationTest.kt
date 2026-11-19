package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.git.CommitHistory
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.metrics.SourceSection
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.HistorySchema
import com.dpsmpx.dpmeta.model.Observation
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpmeta.model.ProjectCatalog
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.sync.Sync
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** D-176: прежний личный список не становится новой выборкой всех авторов. */
class HistoryMigrationTest {
    @TempDir lateinit var dir: Path
    private val now = Timestamp(Instant.parse("2026-11-08T09:00:00Z").toEpochMilli())
    private val payload = assertIs<Outcome.Success<*>>(JsonParser().parse("""
        {"complete":true,"days":[["2026-11-08",4,2,2,3,1,0]],"decisions":[],"ahead":[{"name":"work","commits":1,"last":${now.epochMillis}}]}
    """.trimIndent())).value as JsonObject
    private val entry = SyncProject("P", RepoRef.parse("example/P").valueOrNull()!!)
    private val registry = ProjectCatalog(listOf(entry))

    private fun project(schema: String, body: JsonObject = payload) =
        ProjectState("P", mapOf("history" to SourceSection("history", schema, now, body)), emptyList())

    private fun userLayout(value: JsonObject): JsonObject = JsonObject(value.fields + ("projects" to JsonArray(
        (value["projects"] as JsonArray).items.map { item -> JsonObject((item as JsonObject).fields + ("place" to JsonString("user"))) },
    )))

    @Test
    fun legacyStateVersionsKeepNumbersButNeedFreshHistoryForNewPoints() {
        for (version in 1..2) {
            val vfs = NioVfs(dir.resolve("v$version"))
            val store = Store(vfs, ManualClock(now), RandomSource.secure())
            val oldRules = if (version == 1) 5 else 6
            val original = EcosystemState(now, oldRules, listOf(project(HistorySchema.LEGACY)))
            val document = userLayout(JsonObject(original.toJson().fields + ("version" to JsonNumber.of(version))))
            assertIs<Outcome.Success<*>>(DocumentStore(vfs, "dpmeta/state").write(Store.STATE_KEY, document))
            val oldPoints = listOf(MetricPoint("repo.commits.ai_share", "P", 50, now, oldRules), MetricPoint("repo.branches.unmerged", "P", 1, now, oldRules))
            assertIs<Outcome.Success<*>>(store.appendHistory(oldPoints))
            val restored = assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value!!
            assertEquals(original, restored)
            val row = restored.project("P")!!.historyDays!!.single()
            assertEquals(2L, row.human)
            assertEquals(null, row.other, "прежняя категория не перекрашивается в прочих")
            val offline = EcosystemState.derive(registry, emptyList(), emptyList(), restored, now)
            assertEquals(9, offline.rulesVersion)
            assertEquals(payload, offline.project("P")!!.sections.getValue("history").payload)
            assertTrue(offline.points().none { it.metric.startsWith("repo.commits.") || it.metric.startsWith("repo.branches.unmerged") })
            assertIs<Measure.Unknown>(offline.project("P")!!.aiShare(now))
            assertIs<Measure.Unknown>(offline.project("P")!!.branchesAhead)
            val fresh = CommitHistory("2026-11-08", listOf(CommitHistory.Day("2026-11-08", 6, 2, 4, 4, 2, 0)), emptyList(), listOf(CommitHistory.Ahead("work", 2, now)), complete = true)
            val observation = assertIs<Outcome.Success<Observation>>(Observation.of(ObservationSource.GITHUB, "P", "history", now, Sync.HISTORY_SCHEMA, Sync.historyJson(fresh), Hashing.sha256("fresh"))).value
            assertEquals("clone.history.v3", observation.schema)
            assertEquals(com.dpsmpx.dpai.core.serialization.JsonString("clone.history.v3"), (observation.payload as JsonObject)["schema"])
            val current = EcosystemState.derive(registry, listOf(observation), emptyList(), offline, now)
            assertEquals(Measure.observed(33L), current.project("P")!!.aiShare(now))
            assertEquals(4L, current.project("P")!!.historyDays!!.single().other)
            assertEquals(null, current.project("P")!!.historyDays!!.single().human)
            assertIs<Outcome.Success<*>>(store.writeState(current))
            assertEquals(JsonNumber.of(5), current.toJson()["version"])
            assertIs<Outcome.Success<*>>(store.appendHistory(current.points()))
            val history = assertIs<Outcome.Success<List<MetricPoint>>>(store.history()).value
            assertEquals(oldPoints, history.take(oldPoints.size))
            assertEquals(listOf(oldRules, 9), history.filter { it.metric == "repo.commits.ai_share" }.map { it.rulesVersion })
            assertEquals(current, assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value)
            assertEquals(9, Rules.RULES_VERSION)
        }
    }

    @Test
    fun unknownOrIncompleteHistoryDoesNotInventZeroOrAFullShare() {
        val unknown = project("clone.history.v3")
        assertEquals(null, unknown.historyDays)
        val partial = project(HistorySchema.CURRENT, JsonObject(payload.fields + ("complete" to com.dpsmpx.dpai.core.serialization.JsonBool(false))))
        for (p in listOf(unknown, partial)) {
            assertIs<Measure.Unknown>(p.aiShare(now))
            assertIs<Measure.Unknown>(p.branchesAhead)
        }
    }
}
