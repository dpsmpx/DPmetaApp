package com.dpsmpx.dpmeta.cli

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `dpmeta-cli` (D-29): синхронизация с корпуса, состояние, история, дым, коды возврата. */
class CliTest {
    private val corpus = Path.of(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem"))

    private fun cli(env: Map<String, String> = emptyMap()): Pair<Cli, MutableList<String>> {
        val lines = mutableListOf<String>()
        return Cli(ManualClock(Timestamp(1_793_527_200_000)), env::get) { lines += it } to lines
    }

    @Test
    fun syncFromCorpusWritesStateAndHistory() {
        val dir = Files.createTempDirectory("dpmeta-cli")
        try {
            val (c, out) = cli()
            assertEquals(0, c.run(listOf("sync", "--corpus", corpus.toString(), "--state", dir.toString())))
            assertTrue(out.any { it.startsWith("синхронизация: проектов 4") }, out.joinToString("\n"))
            assertTrue(out.any { "Alpha [core]: работает" in it }, out.joinToString("\n"))
            out.clear()
            assertEquals(0, c.run(listOf("state", "--state", dir.toString())))
            assertTrue(out.single().contains("\"format\":\"dpmeta.state\""))
            out.clear()
            assertEquals(0, c.run(listOf("history", "--state", dir.toString(), "--metric", "eco.questions_open")))
            assertEquals(1, out.size)
            assertTrue(out.single().contains("\"value\":3"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun smokePasses() {
        val (c, out) = cli()
        assertEquals(0, c.run(listOf("smoke", "--corpus", corpus.toString())), out.joinToString("\n"))
        assertTrue(out.last().startsWith("✓ дым"))
    }

    @Test
    fun badArgumentsAndMissingTokenAreCode2() {
        val (c, out) = cli()
        assertEquals(2, c.run(listOf("sync")))
        assertEquals(2, c.run(listOf("sync", "--corpus", "a", "--live")))
        assertEquals(2, c.run(listOf("unknown")))
        out.clear()
        assertEquals(2, c.run(listOf("sync", "--live", "--state", Files.createTempDirectory("x").toString())))
        assertTrue(out.first().contains(Cli.TOKEN_ENV))
    }

    @Test
    fun missingFilesAreNotAskedAgainBeforeRecheck() {
        val dir = Files.createTempDirectory("dpmeta-cli")
        try {
            val (c, out) = cli()
            c.run(listOf("sync", "--corpus", corpus.toString(), "--state", dir.toString()))
            assertTrue(out.any { "DPmeta/roadmap: ENTITY_NOT_FOUND" in it })
            out.clear()
            assertEquals(0, c.run(listOf("sync", "--corpus", corpus.toString(), "--state", dir.toString())))
            assertTrue(out.none { "✗ DPmeta/roadmap" in it }, out.joinToString("\n"))
            assertTrue(out.any { it.contains("не спрашивалось") && it.contains("DPmeta/roadmap") }, out.joinToString("\n"))
            out.clear()
            c.run(listOf("state", "--state", dir.toString()))
            assertTrue(out.single().contains("\"kind\":\"roadmap\""), "отказ перенесён в состояние")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
