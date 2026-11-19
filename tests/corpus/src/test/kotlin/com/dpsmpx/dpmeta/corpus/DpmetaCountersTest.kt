package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.metrics.SourceSection
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.model.ProjectCatalog
import com.dpsmpx.dpmeta.sync.Sync
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** D-173/D-176: имя репозитория не включает процесс автора; прежняя история чисел остаётся. */
class DpmetaCountersTest {
    @TempDir lateinit var dir: Path

    private fun project(name: String) = SyncProject(name, RepoRef.parse("example/$name").valueOrNull()!!)

    private fun userLayout(value: JsonObject): JsonObject = JsonObject(value.fields + ("projects" to JsonArray(
        (value["projects"] as JsonArray).items.map { item -> JsonObject((item as JsonObject).fields + ("place" to JsonString("user"))) },
    )))

    @Test
    fun projectNameNeverSchedulesPrivateProcessDocuments() {
        val plan = Sync.plan(ProjectCatalog(listOf(project("DPmeta"), project("Other"))))
        val byName = plan.groupBy { it.project.name }.mapValues { (_, tasks) -> tasks.map { it.kind.code to it.paths } }
        assertEquals(byName["Other"], byName["DPmeta"], "имя не добавляет источник или путь")
        assertTrue(plan.none { it.kind.code == "dpmeta" })
        assertTrue(plan.flatMap { it.paths }.none { it in setOf("docs/CONTRACTS.md", "docs/RISKS.md", "docs/SYNC_QUEUE.md", "docs/reports") })
    }

    @Test
    fun stateV1LoadsAndNewSyncKeepsHistoricalNumbersAndTheirRules() {
        val at = Timestamp(1_000)
        val clock = ManualClock(at)
        val vfs = NioVfs(dir)
        val store = Store(vfs, clock, RandomSource.secure())
        val oldPoints = listOf(
            MetricPoint("eco.contracts", "eco", 7, at, 5),
            MetricPoint("eco.risks_open", "eco", 3, at, 5),
        )
        assertIs<Outcome.Success<*>>(store.appendHistory(oldPoints))
        val legacySection = SourceSection("dpmeta", "dpmeta.counters.v1", at, JsonObject.of("contracts" to JsonNumber.of(7)))
        val old = EcosystemState(at, 5, listOf(ProjectState("DPmeta", mapOf("dpmeta" to legacySection), emptyList())))
        // Корпус прежней записи v1: числа, схема секции и версия правил не переосмысляются при чтении.
        val v1 = userLayout(JsonObject(old.toJson().fields + ("version" to JsonNumber.of(1))))
        val restored = assertIs<Outcome.Success<EcosystemState>>(EcosystemState.fromJson(v1)).value
        assertEquals(old, restored)
        val fresh = EcosystemState.derive(ProjectCatalog(listOf(project("DPmeta"))), emptyList(), emptyList(), restored, Timestamp(2_000))
        assertEquals(Rules.RULES_VERSION, fresh.rulesVersion)
        assertTrue("dpmeta" !in fresh.project("DPmeta")!!.sections)
        assertTrue(fresh.points().none { it.metric in oldPoints.map(MetricPoint::metric) }, "снятые метрики не записываются снова")
        assertEquals(JsonNumber.of(5), fresh.toJson()["version"])
        assertIs<Outcome.Success<*>>(store.writeState(fresh))
        assertIs<Outcome.Success<*>>(store.appendHistory(fresh.points()))
        val reopened = Store(vfs, clock, RandomSource.secure())
        assertEquals(fresh, assertIs<Outcome.Success<EcosystemState?>>(reopened.readState()).value)
        assertEquals(oldPoints, assertIs<Outcome.Success<List<MetricPoint>>>(reopened.history()).value.take(oldPoints.size))
        assertEquals(listOf(oldPoints.first()), assertIs<Outcome.Success<List<MetricPoint>>>(reopened.history("eco.contracts")).value)
    }

    @Test
    fun unknownStateVersionIsRejected() {
        val doc = EcosystemState(Timestamp(0), Rules.RULES_VERSION, emptyList()).toJson()
        assertIs<Outcome.Failure>(EcosystemState.fromJson(JsonObject(doc.fields + ("version" to JsonNumber.of(6)))))
    }
}
