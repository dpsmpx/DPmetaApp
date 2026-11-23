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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

class ToolchainDoctorTest {
    @TempDir lateinit var dir: Path

    @Test fun onlyExplicitGradleOrJvmCommandsRequireJavaBeforeLaunch() {
        fun profile(kind: ManualProfileKind, vararg argv: String) = ManualProfile(kind, listOf(ManualStep("шаг", argv.toList(), 60)))
        assertTrue(ToolchainDoctor.needsJvm(profile(ManualProfileKind.BUILD, "./gradlew", "assembleRelease")))
        assertTrue(ToolchainDoctor.needsJvm(profile(ManualProfileKind.BUILD, "sh", "gradlew", "assemble")))
        assertTrue(ToolchainDoctor.needsJvm(profile(ManualProfileKind.CHECK, "/usr/bin/java", "-version")))
        assertTrue(ToolchainDoctor.needsJvm(profile(ManualProfileKind.BUILD, "env", "JAVA_HOME=/opt/jdk", "gradle", "build")))
        assertEquals(false, ToolchainDoctor.needsJvm(profile(ManualProfileKind.BUILD, "cmake", "--build", "build")))
        assertEquals(false, ToolchainDoctor.needsJvm(profile(ManualProfileKind.DEPENDENCIES, "./gradlew", "assemble")))
    }

    @Test fun buildPreflightRejectsSelinuxDenialWithoutStartingGradle() {
        val calls = arrayListOf<String>()
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
                calls += spec.command.first()
                val java = spec.command.first() == "java"
                onOutput(OutputLine(if (java) OutputStream.STDERR else OutputStream.STDOUT,
                    if (java) "Failed to mark memory page as executable" else
                        """{"rwxErrno":13,"selinuxContext":"u:r:untrusted_app:s0:c84"}"""))
                return Outcome.success(object : RunningProcess {
                    override val pid: Long? = null
                    override val isAlive = false
                    override fun awaitExit(timeout: Duration) = 1
                    override fun stop(grace: Duration) = StopReport(1, false, emptyList())
                })
            }
        }
        val blocked = assertIs<Outcome.Failure>(ToolchainDoctor.buildJvmPreflight(host) { false })
        assertEquals(com.dpsmpx.dpai.core.diagnostics.DiagCode.EXECUTION_HOST_UNAVAILABLE, blocked.diagnostics.first().code)
        assertTrue(blocked.diagnostics.first().message.contains("SELinux untrusted_app"))
        assertTrue(blocked.diagnostics.first().message.contains("SSH-target"))
        assertEquals(listOf("java", "python3"), calls)
    }

    @Test fun successfulJavaProbeAllowsBuild() {
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> = Outcome.success(object : RunningProcess {
                override val pid: Long? = null
                override val isAlive = false
                override fun awaitExit(timeout: Duration) = 0
                override fun stop(grace: Duration) = StopReport(0, false, emptyList())
            })
        }
        assertIs<Outcome.Success<Unit>>(ToolchainDoctor.buildJvmPreflight(host) { false })
    }

    @Test fun preflightDistinguishesInstalledApkFromGitSourcesAndReportsConflictingMemoryProbe() {
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
                val java = spec.command.first() == "java"
                onOutput(OutputLine(if (java) OutputStream.STDERR else OutputStream.STDOUT,
                    if (java) "Failed to mark memory page as executable" else
                        """{"pageSize":4096,"rwxErrno":0,"rxErrno":0,"selinuxContext":"u:r:untrusted_app:s0:c84"}"""))
                return Outcome.success(object : RunningProcess {
                    override val pid: Long? = null
                    override val isAlive = false
                    override fun awaitExit(timeout: Duration) = if (java) 1 else 0
                    override fun stop(grace: Duration) = StopReport(1, false, emptyList())
                })
            }
        }
        val oldApk = assertIs<Outcome.Failure>(ToolchainDoctor.buildJvmPreflight(host, 36) { false }).diagnostics.first().message
        assertTrue(oldApk.contains("targetSdk=36"))
        assertTrue(oldApk.contains("обновление Git-исходников не меняет APK"))
        assertTrue(oldApk.contains("\"rwxErrno\":0"))
        val compatibleApk = assertIs<Outcome.Failure>(ToolchainDoctor.buildJvmPreflight(host, 28) { false }).diagnostics.first().message
        assertTrue(compatibleApk.contains("targetSdk=28"))
        assertTrue(compatibleApk.contains("не снимает запреты"))
        assertTrue(compatibleApk.contains("проба memory не подтвердила"))
    }

    @Test fun sshBuildDoctorUsesOnlyFixedReadOnlyCommandsWithoutRemoteWorkDir() {
        val seen = arrayListOf<List<String>>()
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
                assertEquals(null, spec.workDir)
                seen += spec.command
                return Outcome.success(object : RunningProcess {
                    override val pid: Long? = null
                    override val isAlive = false
                    override fun awaitExit(timeout: Duration) = 0
                    override fun stop(grace: Duration) = StopReport(0, false, emptyList())
                })
            }
        }
        val report = ToolchainDoctor.run(host, ToolchainDoctor.sshBuildProbes(), null) { false }.valueOrNull()!!
        assertEquals(JsonBool(true), report["allSelectedToolsWork"])
        assertEquals(setOf("java", "javac", "git", "aapt2", "sh"), seen.map { it.first() }.toSet())
        assertEquals(5, seen.size)
    }

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
