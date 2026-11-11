package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ExecutorCli
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.execution.tools.SharedToolsHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SharedToolsTest {
    private val config = SharedTools("/tools/a space/DP-Verify", "/tools/controller.py")
    private val provider = ProviderSessionId.newUuid(RandomSource.seeded(37))

    private class Recorder : ProcessHost {
        var received: ProcessSpec? = null
        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            received = spec
            return Outcome.failure(DiagCode.UNSUPPORTED, "образец процесса")
        }
    }

    private fun request(executor: Executor, readOnly: Boolean = false): ProcessSpec {
        val request = ClaudeRequest("исходное поручение", executor = executor, readOnly = readOnly)
        return ProcessSpec(ExecutorCli.argv(listOf(executor.name.lowercase()), request, provider, false),
            "/worktree", mapOf("SLOT" to "own", SharedTools.EXECUTOR_ENV to executor.name), request.prompt)
    }

    @Test
    fun providersReceiveSameFolderAndOriginalSlotPrompt() {
        for (executor in Executor.entries) {
            val source = request(executor)
            val prepared = config.prepare(source, executor)
            assertEquals(listOf("python3", config.controller, "--repo", config.repository, "session", "--"), prepared.command.take(6))
            assertEquals("/worktree", prepared.workDir)
            assertEquals(source.environment, prepared.environment)
            assertTrue(prepared.stdin!!.endsWith("\n\n" + source.stdin))
            assertTrue(prepared.stdin.contains(config.repository))
        }
    }

    @Test
    fun additionalDirectoriesRespectProviderSyntaxAndReadOnly() {
        val claude = config.prepare(request(Executor.CLAUDE), Executor.CLAUDE).command
        assertEquals(listOf("--add-dir", config.repository), claude.takeLast(2))
        val gemini = config.prepare(request(Executor.GEMINI, true), Executor.GEMINI).command
        assertEquals(listOf("--include-directories", config.repository), gemini.takeLast(2))
        assertTrue(gemini.contains("plan"))
        val codex = config.prepare(request(Executor.CODEX, true), Executor.CODEX).command
        assertEquals("-", codex.last())
        assertTrue(codex.contains("sandbox_mode=\"read-only\""))
        assertEquals(config.repository, codex[codex.indexOf("--add-dir") + 1])
        assertFalse(codex.any { it.contains("danger-full-access") })
    }

    @Test
    fun pathsAreLiteralArgumentsIncludingShellCharacters() {
        val value = SharedTools("/tools/quote\" dollar\$ tick`", "/tools/controller with spaces.py")
        val prepared = value.prepare(request(Executor.CODEX), Executor.CODEX)
        assertEquals(value.repository, prepared.command[3])
        assertFalse(prepared.command.contains("sh"))
        assertEquals(value.repository, prepared.command[prepared.command.indexOf("--add-dir") + 1])
    }

    @Test
    fun codexResumePreservesExistingWritableRootsAndGetsExecOptionBeforeSubcommand() {
        val request = ClaudeRequest("дальше", executor = Executor.CODEX)
        val command = ExecutorCli.argv(listOf("codex"), request, provider, true).toMutableList()
        command.addAll(command.lastIndex, listOf("-c", "sandbox_workspace_write.writable_roots=[\"/existing\"]"))
        val prepared = config.prepare(ProcessSpec(command, stdin = request.prompt), Executor.CODEX).command
        assertTrue(prepared.indexOf("--add-dir") < prepared.indexOf("resume"))
        assertTrue(prepared.contains("sandbox_workspace_write.writable_roots=[\"/existing\"]"))
    }

    @Test
    fun configurationRoundTripsAndRejectsRelativeOrUnknownFields() {
        assertEquals(config, SharedTools.parse(config.toJson()).valueOrNull())
        assertIs<Outcome.Failure>(SharedTools.parse(JsonObject(config.toJson().fields + ("repository" to JsonString("relative")))))
        assertIs<Outcome.Failure>(SharedTools.parse(JsonObject(config.toJson().fields + ("token" to JsonString("not-a-credential")))))
    }

    @Test
    fun disabledHostPreservesSpecAndUnmarkedCommandsDoNotReadConfig() {
        val recorder = Recorder()
        val spec = request(Executor.CLAUDE)
        SharedToolsHost(recorder) { Outcome.success(null) }.start(spec)
        assertSame(spec, recorder.received)
        val probe = spec.copy(environment = mapOf("SLOT" to "own"))
        SharedToolsHost(recorder) { error("проба входа не читает настройку") }.start(probe)
        assertSame(probe, recorder.received)
    }

    @Test
    fun corruptConfigurationAndUnsupportedWindowsFailBeforeStartingProcess() {
        val recorder = Recorder()
        val host = SharedToolsHost(recorder) { Outcome.failure(DiagCode.STORAGE_CORRUPTED, "образец") }
        val failure = assertIs<Outcome.Failure>(host.start(request(Executor.CLAUDE)))
        assertEquals(DiagCode.STORAGE_CORRUPTED, failure.diagnostics.first().code)
        assertEquals(null, recorder.received)
        val windows = SharedToolsHost(recorder, posix = false) { Outcome.success(config) }
        assertEquals(DiagCode.UNSUPPORTED, assertIs<Outcome.Failure>(windows.start(request(Executor.CLAUDE))).diagnostics.first().code)
        assertEquals(null, recorder.received)
    }

    @Test
    fun configuredHostWrapsEachProvider() {
        for (executor in Executor.entries) {
            val recorder = Recorder()
            SharedToolsHost(recorder) { Outcome.success(config) }.start(request(executor))
            assertEquals(config.controller, recorder.received!!.command[1])
        }
    }
}
