package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.metrics.SourceSection
import com.dpsmpx.dpmeta.metrics.Store
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpmeta.model.RepoRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RetiredPlacesTest {
    @TempDir lateinit var dir: Path
    private val now = Timestamp(1_800_000_000_000)

    @Test
    fun legacyPlacesDoNotReturnToNewStateOrChangeNumericHistory() {
        val projects = ProjectList().add(RepoRef.parse("example/P").valueOrNull()!!).valueOrNull()!!
        for (version in 1..4) {
            val vfs = NioVfs(dir.resolve("v$version"))
            val store = Store(vfs, ManualClock(now), RandomSource.secure())
            val legacy = JsonObject.of(
                "format" to JsonString("dpmeta.state"), "version" to JsonNumber.of(version),
                "asOf" to JsonNumber.of(now.epochMillis), "rulesVersion" to JsonNumber.of(8),
                "syncHealth" to JsonNumber.of(100),
                "projects" to JsonArray(listOf(JsonObject.of(
                    "name" to JsonString("P"), "place" to JsonString("core"),
                    "sections" to JsonArray(listOf(SourceSection("readme", "readme.status.v1", now,
                        JsonObject.of("path" to JsonString("README.md"), "blocks" to JsonArray(emptyList()))).toJson())),
                    "errors" to JsonArray(emptyList()),
                    "skipped" to JsonArray(listOf(JsonString("pulls"), JsonString("history"))),
                ))),
            )
            assertIs<Outcome.Success<*>>(DocumentStore(vfs, "dpmeta/state").write(Store.STATE_KEY, legacy))
            val old = listOf(MetricPoint("repo.pr.open", "P", 3, now, 8))
            assertIs<Outcome.Success<*>>(store.appendHistory(old))
            val previous = assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value!!
            assertTrue(previous.project("P")!!.sections.isEmpty(), "старый host-кэш не выдаётся за данные своего репозитория")
            assertIs<Measure.Unknown>(previous.syncHealth)
            val current = EcosystemState.derive(projects.forSync(), emptyList(), emptyList(), previous, now)
            assertEquals(listOf("P"), current.projects.map { it.name })
            val row = (current.toJson()["projects"] as JsonArray).items.single() as JsonObject
            assertTrue("place" !in row.fields, "место автора не переносится в новый формат")
            assertTrue("skipped" !in row.fields, "репозиторий пользователя не наследует роль подсистемы")
            assertEquals(JsonNumber.of(5), current.toJson()["version"])
            assertEquals(9, current.rulesVersion)
            assertIs<Outcome.Success<*>>(store.writeState(current))
            assertEquals(old, assertIs<Outcome.Success<List<MetricPoint>>>(store.history()).value)
            assertEquals(current, assertIs<Outcome.Success<EcosystemState?>>(store.readState()).value)
        }
    }

    @Test
    fun userListCacheWithLegacyDecisionMarkerRemainsReadableWithoutRoleFields() {
        val section = SourceSection("readme", "readme.status.v1", now, JsonObject.of("blocks" to JsonArray(emptyList())))
        val value = JsonObject.of(
            "format" to JsonString("dpmeta.state"), "version" to JsonNumber.of(4),
            "asOf" to JsonNumber.of(now.epochMillis), "rulesVersion" to JsonNumber.of(8),
            "projects" to JsonArray(listOf(JsonObject.of(
                "name" to JsonString("P"), "place" to JsonString("user"),
                "skipped" to JsonArray(listOf(JsonString("decisions"))),
                "sections" to JsonArray(listOf(section.toJson())), "errors" to JsonArray(emptyList()),
            ))),
        )
        val state = assertIs<Outcome.Success<EcosystemState>>(EcosystemState.fromJson(value)).value
        assertEquals(section, state.project("P")!!.sections["readme"])
        val row = (state.toJson()["projects"] as JsonArray).items.single() as JsonObject
        assertTrue("place" !in row.fields)
        assertEquals(state, assertIs<Outcome.Success<EcosystemState>>(EcosystemState.fromJson(state.toJson())).value)
    }
}
