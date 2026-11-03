package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.formats.Checks
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Sync
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Итог проверок PR (F-15) и лента из разницы состояний (F-05). */
class ChecksAndFeedTest {
    private val root = File(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem")).toPath()
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())

    private fun pr(checks: String) = "## Сделано\n\nx\n\n## Проверки\n\n$checks\n"

    @Test
    fun checksVerdicts() {
        val g = Checks.Verdict.GREEN
        val r = Checks.Verdict.RED
        val u = Checks.Verdict.UNKNOWN
        for ((body, want) in listOf(
            pr("- `full --strict` — 22 из 22.") to g,
            pr("- ✓ всё зелёное") to g,
            pr("verify (static): **PASS** на коммите `abc`") to g,
            pr("- `full --strict` — 21 из 22, с находками 1") to r,
            pr("- ✗ build.gradle") to r,
            pr("verify (full): **FAIL**") to r,
            pr("- static — 14 из 14; full не запускался") to u,
            pr("- проверю позже") to u,
            "Без шаблона, без раздела" to u,
            "" to u,
        )) {
            assertEquals(want, Checks.of(body).verdict, body)
        }
        assertEquals("`full --strict` — 22 из 22.", Checks.of(pr("- `full --strict` — 22 из 22.")).line)
        assertEquals(u, Checks.of(null).verdict)
    }

    private fun state(): EcosystemState {
        val reg = (RegistryFormat.parse(Files.readString(root.resolve("registry.toml"))) as Outcome.Success).value
        val r = Sync.run(Sync.plan(reg), DirectoryReader(root), ManualClock(now))
        return EcosystemState.derive(reg, r.observations, r.failures, null, now)
    }

    @Test
    fun pullsCarryChecksAndMergedKeepsOnlyMerged() {
        val alpha = state().project("Alpha")!!
        val open = ((alpha.sections["pulls"]!!.payload as JsonObject)["pulls"] as JsonArray).items.single() as JsonObject
        assertEquals("green", (open["checks"] as JsonString).value)
        val merged = ((alpha.sections["merged"]!!.payload as JsonObject)["pulls"] as JsonArray).items
        assertEquals(1, merged.size, "закрытый без влития — не влитой")
        assertEquals("red", ((merged.single() as JsonObject)["checks"] as JsonString).value)
    }

    @Test
    fun feedComesFromStateDifference() {
        val next = state()
        assertEquals(emptyList(), Feed.diff(null, next), "первый срез — без событий")
        // Прежний срез: решения без D-02, открыт PR #2 (теперь влит), другой SHA main, ROADMAP на шаг меньше.
        val alpha = next.project("Alpha")!!
        fun edit(kind: String, f: (JsonObject) -> JsonObject) = alpha.sections.getValue(kind).let { it.copy(payload = f(it.payload as JsonObject)) }
        fun JsonObject.with(k: String, v: com.dpsmpx.dpai.core.serialization.JsonValue) = JsonObject(LinkedHashMap(fields).apply { put(k, v) })
        val before = alpha.copy(
            sections = alpha.sections + mapOf(
                "decisions" to edit("decisions") { o -> o.with("entries", JsonArray((o["entries"] as JsonArray).items.dropLast(1))) },
                "pulls" to edit("pulls") { o -> o.with("pulls", JsonArray(listOf(JsonObject.of("number" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(2), "title" to JsonString("Этап 0"))))) },
                "branches" to edit("branches") { o ->
                    o.with("branches", JsonArray((o["branches"] as JsonArray).items.map { b -> (b as JsonObject).let { if ((it["name"] as JsonString).value == Rules.MAIN_BRANCH) it.with("sha", JsonString("0".repeat(40))) else it } }))
                },
                "roadmap" to edit("roadmap") { o -> o.with("stepsDone", com.dpsmpx.dpai.core.serialization.JsonNumber.of(1)) },
            ),
        )
        val previous = next.copy(projects = next.projects.map { if (it.name == "Alpha") before else it })
        val events = Feed.diff(previous, next)
        val kinds = events.map { it.kind }.toSet()
        assertEquals(setOf("main", "pr.opened", "pr.merged", "decision", "roadmap"), kinds, events.joinToString("\n"))
        assertTrue(events.any { it.text.startsWith("PR #2 влит") })
        assertTrue(events.any { it.text.startsWith("новое решение D-02") })
        assertEquals(events, Feed.merge(emptyList(), events + events).let { Feed.merge(it, emptyList()) }, "повтор не дублируется")
    }
}
