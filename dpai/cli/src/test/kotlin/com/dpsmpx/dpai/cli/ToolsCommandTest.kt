package com.dpsmpx.dpai.cli

import java.nio.file.Path
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import com.dpsmpx.dpai.execution.tools.SharedToolsStatus
import com.dpsmpx.dpai.orchestrator.agent.SharedToolsStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ToolsCommandTest {
    @TempDir lateinit var home: Path

    private class Host(var response: String, var code: Int = 0) : ProcessHost {
        val received = mutableListOf<ProcessSpec>()
        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            received += spec
            onOutput(OutputLine(OutputStream.STDOUT, response))
            return Outcome.success(object : RunningProcess {
                override val pid: Long? = null
                override val isAlive = false
                override fun awaitExit(timeout: kotlin.time.Duration) = code
                override fun stop(grace: kotlin.time.Duration) = StopReport(code, false, emptyList())
            })
        }
    }

    @Test
    fun managedControllerPassesAccountOnlyInStdinAndPublishesVerifiedSettings() {
        val output = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val account = """{"token":"synthetic-account-value"}"""
        val host = Host("""{"status":"installed","revision":"${"a".repeat(40)}"}""")
        val cli = ToolsCommand({ output += it }, { errors += it }, input = ByteArrayInputStream(account.toByteArray()), host = host)
        val repo = home.resolve("separate folder").toString()
        val data = home.resolve("agent")
        val options = listOf("--home", data.toString(), "--repo", repo)
        assertEquals(0, cli.run(listOf("init", "--github-stdin") + options), errors.toString())
        val spec = host.received.single()
        assertEquals(account, spec.stdin)
        assertFalse(spec.command.any { "synthetic" in it })
        assertNull(spec.environment)
        val store = SharedToolsStore(NioVfs(data))
        val tools = (store.read() as Outcome.Success).value!!
        assertEquals(repo, tools.repository)
        val controller = java.io.File(tools.controller)
        val bytes = controller.readBytes()
        assertTrue(bytes.isNotEmpty())
        assertFalse(java.nio.file.Files.walk(data).use { paths -> paths.filter { java.nio.file.Files.isRegularFile(it) }.anyMatch { "synthetic-account" in java.nio.file.Files.readString(it) } })
        assertEquals(0, cli.run(listOf("status", "--json", "--home", data.toString())))
        val status = assertIs<Outcome.Success<*>>(SharedToolsStatus.parse((JsonParser().parse(output.last()) as Outcome.Success).value)).value as SharedToolsStatus
        assertEquals(tools, status.configured)
        controller.writeText("changed locally")
        assertEquals(1, cli.run(listOf("sync") + options))
        assertEquals("changed locally", controller.readText())
        assertEquals(1, host.received.size, "не запускать изменённый контроллер")
    }

    @Test
    fun refusalDoesNotConnectAndUntrustedOutputIsNotEchoed() {
        val output = mutableListOf<String>()
        val host = Host("private diagnostic", 1)
        val command = ToolsCommand({ output += it }, {}, host = host)
        val options = listOf("--home", home.resolve("agent").toString(), "--repo", home.resolve("tools").toString())
        assertEquals(1, command.run(listOf("init") + options))
        assertNull((SharedToolsStore(NioVfs(home.resolve("agent"))).read() as Outcome.Success).value)
        assertTrue(output.none { "private diagnostic" in it })
        host.code = 0
        host.response = "{}"
        assertEquals(1, command.run(listOf("init") + options))
        assertNull((SharedToolsStore(NioVfs(home.resolve("agent"))).read() as Outcome.Success).value)
    }

    @Test
    fun busyOperationPreventsLateConfigurationOverwrite() {
        val data = home.resolve("agent")
        val files = NioVfs(data)
        files.mkdirs("")
        val command = ToolsCommand({}, {})
        val lease = files.tryLease("shared-tools-operation.lock")!!
        lease.use {
            assertEquals(75, command.run(listOf("disable", "--home", data.toString())))
            assertEquals(75, command.run(listOf("configure", "--home", data.toString(), "--repo", "/tools", "--controller", "/controller.py")))
            assertEquals(0, command.run(listOf("status", "--json", "--home", data.toString())), "состояние читается во время операции")
        }
        assertEquals(0, command.run(listOf("disable", "--home", data.toString())))
    }

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

    @Test
    fun windowsTargetPersistsConfigurationAndCanDisableIt() {
        val out = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val command = ToolsCommand({ out += it }, { errors += it }, windows = true)
        val base = listOf("--home", home.toString())
        assertEquals(0, command.run(listOf("configure") + base + listOf("--repo", "C:/tools/DP Verify", "--controller", "C:/tools/runtime.py")))
        assertEquals(0, command.run(listOf("status") + base))
        assertTrue(out.contains("Папка: C:/tools/DP Verify"))
        assertTrue(out.contains("Python: python"))
        assertEquals(0, command.run(listOf("disable") + base))
        assertEquals(0, command.run(listOf("status") + base))
        assertTrue(out.last().contains("выключены"))
        assertTrue(errors.isEmpty())
    }
}
