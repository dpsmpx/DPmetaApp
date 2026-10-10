package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.WorkspaceTools
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Дерево на ПК, ход на телефоне (D-50): команда MCP-сервера и запрос хода. */
class RemoteWorkspaceTest {
    private val pc = RemoteWorkspace(
        SshEndpoint.of("100.64.1.2", "dev", 22, "/root/.ssh/dpai_workspace", null).valueOrNull()!!,
        TargetPlatform.WINDOWS,
        "C:/Users/dev/dpai-agent.cmd",
    )

    @Test
    fun mcpServerIsAgentOverSshWithStrictHostKey() {
        val server = ((pc.mcpConfig("C:/dpai/trees/tsk_1").valueOrNull()!!["mcpServers"] as JsonObject)[WorkspaceTools.SERVER] as JsonObject)
        assertEquals(JsonString("stdio"), server["type"])
        assertEquals(JsonString("ssh"), server["command"])
        val args = (server["args"] as JsonArray).items.map { (it as JsonString).value }
        assertTrue("StrictHostKeyChecking=yes" in args && "BatchMode=yes" in args, "$args")
        assertEquals(listOf("-i", "/root/.ssh/dpai_workspace"), args.subList(args.indexOf("-i"), args.indexOf("-i") + 2))
        assertEquals(listOf("-l", "dev", "--", "100.64.1.2"), args.subList(args.size - 5, args.size - 1))
        assertEquals("C:/Users/dev/dpai-agent.cmd agent mcp --root C:/dpai/trees/tsk_1", args.last())
    }

    @Test
    fun requestHasNoBuiltinToolsOnlyThisServerAndAStableWorkDir() {
        val base = ClaudeRequest("сделай", workDir = "C:/dpai/trees/tsk_1", appendSystemPrompt = "правила")
        val r = pc.apply(base, "C:/dpai/trees/tsk_1").valueOrNull()!!
        assertEquals(emptyList(), r.tools, "встроенные инструменты выключены")
        assertTrue(r.strictMcpConfig)
        assertNull(r.workDir, "каталог процесса — каталог агента: один для всех ходов, --resume находит сессию")
        assertTrue(r.appendSystemPrompt!!.startsWith("правила") && "C:/dpai/trees/tsk_1" in r.appendSystemPrompt!!)
        assertEquals(base.prompt, r.prompt)
    }

    @Test
    fun unsupportedArgumentsAreRefusedNotMangled() {
        // Одинарная кавычка ломает обёртку proot; `%` — cmd.exe на Windows (D-05).
        assertEquals(DiagCode.UNSUPPORTED, (pc.mcpConfig("C:/it's") as Outcome.Failure).errors.single().code)
        assertIs<Outcome.Failure>(pc.mcpConfig("C:/100%"))
    }
}
