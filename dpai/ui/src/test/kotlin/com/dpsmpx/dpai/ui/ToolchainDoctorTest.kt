package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class ToolchainDoctorTest {
    @TempDir lateinit var dir: Path

    @Test fun installedJavaThatCannotStartIsNotReady() {
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
                onOutput(OutputLine(OutputStream.STDERR, "Failed to mark memory page as executable - check if grsecurity/PaX is enabled"))
                return Outcome.success(object : RunningProcess {
                    override val pid: Long? = null
                    override val isAlive = false
                    override fun awaitExit(timeout: Duration) = 1
                    override fun stop(grace: Duration) = StopReport(1, false, emptyList())
                })
            }
        }
        val report = ToolchainDoctor.run(host, ToolchainDoctor.select("java", emptyList()).valueOrNull()!!, dir.toString()) { false }.valueOrNull()!!
        assertEquals(JsonBool(false), report["allSelectedToolsWork"])
        val item = (report["probes"] as JsonArray).items.single() as JsonObject
        assertEquals(JsonString("JVM_EXECUTABLE_MEMORY"), item["finding"])
        assertTrue((item["explanation"] as JsonString).value.contains("не доказательство"))
        assertEquals(JsonBool(false), report["projectBuildVerified"])
    }

    @Test fun selinuxExecmemDenialNamesAndroidRestrictionAndRemoteBuildTarget() {
        val diagnostic = """{"pageSize":4096,"rwxErrno":13,"rxErrno":0,"selinuxContext":"u:r:untrusted_app:s0:c84"}"""
        val finding = ToolchainDoctor.explain(diagnostic)!!
        assertEquals("ANDROID_SELINUX_EXECMEM", finding.first)
        assertTrue(finding.second.contains("proot"))
        assertTrue(finding.second.contains("SSH"))
        assertEquals(null, ToolchainDoctor.explain("""{"rwxErrno":13,"selinuxContext":null}"""))
    }

    @Test fun actualPythonAndCompilerRunWithoutChangingTheProject() {
        for (tool in listOf("python3", "c++")) {
            val report = ToolchainDoctor.run(LocalProcessHost(), ToolchainDoctor.select(tool, emptyList()).valueOrNull()!!, dir.toString()) { false }.valueOrNull()!!
            assertEquals(JsonBool(true), report["allSelectedToolsWork"], report.toString())
        }
        assertTrue(dir.toFile().listFiles().orEmpty().isEmpty())
    }

    @Test fun absentExecutableIsUnknownExitRatherThanZero() {
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit) = Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.PROCESS_START_FAILED, "нет программы")
        }
        val report = ToolchainDoctor.run(host, ToolchainDoctor.select("java", emptyList()).valueOrNull()!!, dir.toString()) { false }.valueOrNull()!!
        assertEquals(JsonNull, ((report["probes"] as JsonArray).items.single() as JsonObject)["exitCode"])
        assertEquals(JsonBool(false), report["allSelectedToolsWork"])
    }

    @Test fun suppliedShellCannotBecomeAReadOnlyProbe() {
        val arbitrary = ToolchainDoctor.Probe("java", listOf("sh", "-c", "echo changed"))
        assertTrue(ToolchainDoctor.run(LocalProcessHost(), listOf(arbitrary), dir.toString()) { false } is Outcome.Failure)
    }
}
