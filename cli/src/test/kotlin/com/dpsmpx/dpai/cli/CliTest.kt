package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class CliTest {
    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()
    private val cli = Cli(out = { out += it }, err = { err += it }, version = "1.2.3")

    @Test
    fun versionPrintsVersionAndSucceeds() {
        assertEquals(Cli.OK, cli.run(listOf("version")))
        assertEquals(listOf("dpai 1.2.3"), out)
    }

    @Test
    fun helpSucceeds() {
        assertEquals(Cli.OK, cli.run(listOf("help")))
        assertTrue(out.single().contains("dpai version"))
    }

    @Test
    fun noCommandIsUsageError() {
        assertEquals(Cli.USAGE_ERROR, cli.run(emptyList()))
        assertTrue(out.isEmpty())
        assertTrue(err.isNotEmpty())
    }

    @Test
    fun unknownCommandIsUsageError() {
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("launch-everything")))
        assertTrue(err.first().contains("launch-everything"))
    }

    /**
     * Ответ агента — UTF-8 при любой локали target (ревью 8 октября 2026):
     * при POSIX System.out превращал кириллицу диагностики в `?`.
     */
    @Test
    fun agentRepliesInUtf8UnderPosixLocale(@TempDir home: Path) {
        val java = File(File(System.getProperty("java.home"), "bin"), if (File.separatorChar == '\\') "java.exe" else "java").path
        val env = System.getenv() - setOf("LANG", "LC_ALL", "LC_CTYPE", "JAVA_TOOL_OPTIONS") + ("LC_ALL" to "POSIX")
        val cmd = listOf(
            java, "-cp", System.getProperty("java.class.path"), "com.dpsmpx.dpai.cli.MainKt",
            "agent", "status", "--run", "ses_01J00000000000000000000000", "--home", home.toString(),
        )
        val done = LocalProcessHost().run(ProcessSpec(cmd, environment = env), 60.seconds).valueOrNull()!!
        val text = (done.stdout + done.stderr).joinToString("\n")
        assertTrue(text.any { it in '\u0400'..'\u04FF' }, "в ответе нет кириллицы: $text")
        assertFalse('?' in text, "кириллица стала `?`: $text")
    }
}
