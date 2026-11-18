package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.metrics.SourceSection
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.model.UserProject
import com.dpsmpx.dpmeta.sync.Sync
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** D-176: процесс автора не задаёт готовность и вопросы проектов пользователя. */
class RetiredProcessTest {
    @TempDir lateinit var dir: Path
    private val now = Timestamp(1_800_000_000_000)
    private val retired = setOf("decisions", "todo", "roadmap")
    private val oldMetrics = setOf("repo.stage", "repo.gate.passed", "repo.gate.ready", "repo.decisions.count", "repo.decisions.new", "repo.todo.items", "repo.roadmap.progress", "eco.questions_open")
    private val registry = ProjectList(listOf(UserProject(RepoRef.parse("example/P").valueOrNull()!!, checklists = listOf("plan.md")))).toRegistry()

    private fun section(kind: String, text: String) = kind to SourceSection(kind, "process.test.v1", now,
        assertIs<Outcome.Success<*>>(JsonParser().parse(text)).value as JsonObject)

    private fun legacy() = EcosystemState(now, 7, listOf(ProjectState("P", "user", mapOf(
        section("decisions", """{"count":3}"""),
        section("todo", """{"open":2,"forAuthor":1,"sections":[]}"""),
        section("roadmap", """{"stepsDone":1,"stepsTotal":2,"stages":[{"title":"Этап","flag":"active","stepsDone":1,"stepsTotal":2}]}"""),
        section("readme", """{"blocks":[]}"""),
        section("pulls", """{"pulls":[]}"""),
    ), emptyList())))

    @Test
    fun userProjectPlanDoesNotProbeAuthorProcessFiles() {
        val plan = Sync.plan(registry)
        assertTrue(plan.none { it.kind.code in retired }, "TODO и ROADMAP не назначаются без выбора пользователя")
        assertEquals(listOf("plan.md"), plan.single { it.kind.code == "checklists" }.paths)
        assertEquals(listOf("README.md"), plan.single { it.kind.code == "readme" }.paths)
    }

    @Test
    fun oldSectionsCannotProduceNewReadinessOrQuestionValues() {
        assertTrue(legacy().points().none { it.metric in oldMetrics }, "прежний payload не становится новой точкой снятого правила")
        val current = EcosystemState.derive(registry, emptyList(), emptyList(), legacy(), now)
        assertTrue(current.project("P")!!.sections.keys.none { it in retired })
        assertTrue("readme" in current.project("P")!!.sections)
        assertTrue(current.points().none { it.metric in oldMetrics })
        assertTrue("questionsOpen" !in current.toJson().fields)
        assertEquals(8, current.rulesVersion)
    }

    @Test
    fun legacyStatesAndNumericHistorySurviveWithoutReclassification() {
        for (version in 1..3) {
            val storeVfs = NioVfs(dir.resolve("v$version"))
            val store = Store(storeVfs, ManualClock(now), RandomSource.secure())
            val old = legacy()
            val document = JsonObject(old.toJson().fields + ("version" to JsonNumber.of(version)))
            assertIs<Outcome.Success<*>>(DocumentStore(storeVfs, "dpmeta/state").write(Store.STATE_KEY, document))
            val points = oldMetrics.sorted().map { MetricPoint(it, if (it.startsWith("eco.")) "eco" else "P", 2, now, 7) }
            assertIs<Outcome.Success<*>>(store.appendHistory(points))
            val restored = assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value!!
            assertEquals(old, restored)
            val current = EcosystemState.derive(registry, emptyList(), emptyList(), restored, now)
            assertIs<Outcome.Success<*>>(store.writeState(current))
            assertIs<Outcome.Success<*>>(store.appendHistory(current.points()))
            val history = assertIs<Outcome.Success<List<MetricPoint>>>(store.history()).value
            assertEquals(points, history.filter { it.metric in oldMetrics })
            assertEquals(JsonNumber.of(4), current.toJson()["version"])
            assertEquals(8, Rules.RULES_VERSION)
            assertEquals(current, assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value)
        }
    }
}
