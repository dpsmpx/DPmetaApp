package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonString
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
import com.dpsmpx.dpai.core.git.RepoRef
import com.dpsmpx.dpmeta.model.UserProject
import com.dpsmpx.dpmeta.sync.Sync
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.model.Observation
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.SyncProject
import com.dpsmpx.dpmeta.model.SourceFailure
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.SourceReader
import com.dpsmpx.dpmeta.sync.Got
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
    private val registry = ProjectList(listOf(UserProject(RepoRef.parse("example/P").valueOrNull()!!, checklists = listOf("TODO.md", "ROADMAP/ROADMAP.md")))).forSync()

    private fun section(kind: String, text: String) = kind to SourceSection(kind, "process.test.v1", now,
        assertIs<Outcome.Success<*>>(JsonParser().parse(text)).value as JsonObject)

    private fun legacy() = EcosystemState(now, 7, listOf(ProjectState("P", mapOf(
        section("decisions", """{"count":3}"""),
        section("todo", """{"open":2,"forAuthor":1,"sections":[]}"""),
        section("roadmap", """{"stepsDone":1,"stepsTotal":2,"stages":[{"title":"Этап","flag":"active","stepsDone":1,"stepsTotal":2}]}"""),
        section("readme", """{"blocks":[]}"""),
        section("pulls", """{"pulls":[]}"""),
    ), emptyList())))

    private fun userLayout(value: JsonObject): JsonObject = JsonObject(value.fields + ("projects" to JsonArray(
        (value["projects"] as JsonArray).items.map { item -> JsonObject((item as JsonObject).fields + ("place" to JsonString("user"))) },
    )))

    @Test
    fun userProjectPlanDoesNotProbeAuthorProcessFiles() {
        val plan = Sync.plan(registry)
        assertTrue(plan.none { it.kind.code in retired }, "TODO и ROADMAP не назначаются без выбора пользователя")
        assertEquals(listOf("TODO.md", "ROADMAP/ROADMAP.md"), plan.single { it.kind.code == "checklists" }.paths)
        assertEquals(listOf("README.md"), plan.single { it.kind.code == "readme" }.paths)
        val body = "## За автором\n- [ ] Пункт\n"
        val reader = object : SourceReader by DirectoryReader(dir) {
            override fun file(project: SyncProject, path: String) = Outcome.success(Got(body, Hashing.sha256(body)))
        }
        val result = Sync.run(plan.filter { it.kind.code == "checklists" }, reader, ManualClock(now))
        assertTrue(result.failures.isEmpty())
        val state = EcosystemState.derive(registry, result.observations, result.failures, null, now)
        assertEquals(listOf(1L, 1L), state.project("P")!!.checklists!!.map { it.total })
        assertTrue(state.points().none { it.metric in oldMetrics })
    }

    @Test
    fun oldSectionsCannotProduceNewReadinessOrQuestionValues() {
        assertTrue(legacy().points().none { it.metric in oldMetrics }, "прежний payload не становится новой точкой снятого правила")
        val observation = Observation.of(ObservationSource.DIRECTORY, "P", "roadmap", now,
            "process.test.v1", legacy().project("P")!!.sections.getValue("roadmap").payload, Hashing.sha256("old")).valueOrNull()!!
        val failure = SourceFailure("P", "todo", Diagnostic.error(DiagCode.NETWORK_UNREACHABLE, "нет сети"), now)
        val current = EcosystemState.derive(registry, listOf(observation), listOf(failure), legacy(), now)
        assertTrue(current.project("P")!!.errors.isEmpty())
        assertIs<Measure.Unknown>(current.syncHealth, "снятые источники не портят долю активных источников")
        assertTrue(current.project("P")!!.sections.keys.none { it in retired })
        assertTrue("readme" in current.project("P")!!.sections)
        assertTrue(current.points().none { it.metric in oldMetrics })
        assertTrue("questionsOpen" !in current.toJson().fields)
        assertEquals(Rules.RULES_VERSION, current.rulesVersion)
    }

    @Test
    fun legacyStatesAndNumericHistorySurviveWithoutReclassification() {
        for (version in 1..3) {
            val storeVfs = NioVfs(dir.resolve("v$version"))
            val store = Store(storeVfs, ManualClock(now), RandomSource.secure())
            val old = legacy()
            val document = userLayout(JsonObject(old.toJson().fields + ("version" to JsonNumber.of(version)) + ("questionsOpen" to JsonNumber.of(1))))
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
            assertEquals(JsonNumber.of(6), current.toJson()["version"])
            assertEquals(10, Rules.RULES_VERSION)
            assertEquals(current, assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value)
        }
    }

    @Test
    fun legacyFeedIsReadableButRetiredEventsAreNotProducedAgain() {
        val before = legacy()
        val project = before.project("P")!!
        val after = before.copy(projects = listOf(project.copy(sections = project.sections + mapOf(
            section("decisions", """{"count":4,"entries":[{"id":"D-4","title":"Новое"}]}"""),
            section("roadmap", """{"stepsDone":2,"stepsTotal":2}"""),
        ))))
        assertTrue(Feed.diff(before, after).isEmpty())
        val saved = listOf(FeedEvent(now, "P", "decision", "Прежнее решение"), FeedEvent(now, "P", "roadmap", "Прежний шаг"))
        val store = Store(NioVfs(dir), ManualClock(now), RandomSource.secure())
        assertIs<Outcome.Success<*>>(store.appendFeed(saved))
        assertEquals(saved, assertIs<Outcome.Success<List<FeedEvent>>>(store.readFeed()).value)
        assertTrue(saved.all { Feed.event(it) == null })
    }
}
