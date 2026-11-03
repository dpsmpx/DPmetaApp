package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.ScreenText
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Sync
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Снимки экранов на JVM (этап 3): синтетическая экосистема корпуса →
 * синхронизация → состояние → модели экранов → текст, сверка с
 * `src/test/snapshots/`. Обновить снимки — `-Dsnapshots.update=true`
 * (переменная окружения `SNAPSHOTS_UPDATE=1`), затем посмотреть дифф.
 */
class ScreenSnapshotTest {
    private val corpus = Path.of(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem"))
    private val dir = File(System.getProperty("snapshots.dir") ?: error("нет snapshots.dir"))
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())

    private fun state(): EcosystemState {
        val reg = (RegistryFormat.parse(Files.readString(corpus.resolve("registry.toml"))) as Outcome.Success).value
        val r = Sync.run(Sync.plan(reg), DirectoryReader(corpus), ManualClock(now))
        return EcosystemState.derive(reg, r.observations, r.failures, null, now)
    }

    private fun snapshot(name: String, text: String) {
        val f = File(dir, "$name.txt")
        if (System.getenv("SNAPSHOTS_UPDATE") == "1") {
            f.writeText(text)
            return
        }
        assertTrue(f.isFile, "нет снимка ${f.name}: SNAPSHOTS_UPDATE=1 и посмотреть дифф")
        assertEquals(f.readText(), text, "снимок ${f.name} разошёлся с экраном")
    }

    @Test
    fun summary() = snapshot("summary", ScreenText.summary(Screens.summary(state())))

    @Test
    fun projects() = snapshot("projects", ScreenText.projects(Screens.projects(state())))

    @Test
    fun cardOfAlpha() = snapshot("card-alpha", ScreenText.card(Screens.card(state(), "Alpha")!!))

    @Test
    fun cardOfSubsystemWithErrors() = snapshot("card-dpmeta", ScreenText.card(Screens.card(state(), "DPmeta")!!))

    @Test
    fun questions() = snapshot("questions", ScreenText.questions(Screens.questions(state())))

    @Test
    fun feedWithFilters() {
        val events = listOf(
            com.dpsmpx.dpmeta.metrics.FeedEvent(now, "Alpha", "pr.merged", "PR #2 влит: Этап 0"),
            com.dpsmpx.dpmeta.metrics.FeedEvent(Timestamp(now.epochMillis - 3_600_000), "Beta", "decision", "новое решение Р2 — Порядок"),
        )
        snapshot("feed", ScreenText.feed(Screens.feed(events)) + ScreenText.feed(Screens.feed(events, project = "Beta")) + ScreenText.feed(Screens.feed(events, kind = "pr.merged")))
    }

    @Test
    fun unknownIsAWordNotZero() {
        val beta = Screens.card(state(), "Beta")!!
        assertEquals(Screens.UNKNOWN, beta.working)
        assertTrue(beta.pulls.isEmpty())
        assertNull(Screens.card(state(), "Gamma"), "архив не показывается")
    }
}
