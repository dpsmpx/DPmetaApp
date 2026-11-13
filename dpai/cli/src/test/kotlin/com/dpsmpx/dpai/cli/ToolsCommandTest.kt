package com.dpsmpx.dpai.cli

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ToolsCommandTest {
    @TempDir lateinit var home: Path

    @Test
    fun configureStatusAndDisableShareAgentSettings() {
        val out = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val cli = Cli({ out += it }, { errors += it })
        val base = listOf("--home", home.toString())
        if (java.io.File.separatorChar == '\\') {
            assertEquals(1, cli.run(listOf("tools", "configure") + base + listOf("--repo", "/shared/DP-Verify", "--controller", "/shared/runtime.py")))
            assertEquals(0, cli.run(listOf("tools", "status") + base))
            assertTrue(out.last().contains("выключены"))
            return
        }
        assertEquals(0, cli.run(listOf("tools", "configure") + base + listOf("--repo", "/shared/DP-Verify", "--controller", "/shared/runtime.py")))
        assertEquals(0, cli.run(listOf("tools", "status") + base))
        assertTrue(out.any { it.contains("/shared/DP-Verify") })
        assertEquals(0, cli.run(listOf("tools", "disable") + base))
        assertEquals(0, cli.run(listOf("tools", "status") + base))
        assertTrue(out.last().contains("выключены"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun missingUnknownDuplicateAndRelativeOptionsAreUsageErrors() {
        val command = ToolsCommand({}, {})
        assertEquals(2, command.run(listOf("configure", "--repo", "/tools")))
        assertEquals(2, command.run(listOf("status", "--unexpected", "x")))
        assertEquals(2, command.run(listOf("status", "--home", home.toString(), "--home", home.toString())))
        assertEquals(2, command.run(listOf("configure", "--repo", "relative", "--controller", "/tools/runtime.py")))
    }
}
