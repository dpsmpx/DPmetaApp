package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.WorkspaceTools
import com.dpsmpx.dpai.execution.ssh.SshProcessHost
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform

/**
 * Рабочее дерево задачи на другом target, чем ход Claude (D-50;
 * `docs/EXECUTION_MODEL.md`, §7): Claude Code работает на target хода
 * (телефон, вход его слота), а файлы и команды — на target проекта (ПК)
 * через MCP-сервер агента `dpai agent mcp`, который Claude Code запускает
 * по SSH сам. Встроенные инструменты хода отключены (`--tools ""`), серверы
 * MCP — только этот (`--strict-mcp-config`): ход не трогает файлы target
 * хода. Git, проверка и слияние — как прежде, через агента target проекта.
 *
 * [endpoint] — как target хода видит target проекта: адрес, пользователь,
 * ключ SSH и known_hosts — пути на target хода (на телефоне — внутри proot,
 * где идёт Claude). Ключ — свой ключ target хода, а не ключ приложения:
 * тот не покидает Keystore (D-35).
 */
data class RemoteWorkspace(
    val endpoint: SshEndpoint,
    val platform: TargetPlatform,
    /** Команда агента `dpai` на target проекта. */
    val agent: String,
    val sshProgram: String = "ssh",
) {
    /** `--mcp-config` для дерева [root] на target проекта. */
    fun mcpConfig(root: String): Outcome<JsonObject> {
        val argv = when (val c = SshProcessHost.command(endpoint, platform, listOf(agent, "agent", "mcp", "--root", root), sshProgram)) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        val config = JsonObject.of(
            "mcpServers" to JsonObject.of(
                WorkspaceTools.SERVER to JsonObject.of(
                    "type" to JsonString("stdio"),
                    "command" to JsonString(argv.first()),
                    "args" to JsonArray(argv.drop(1).map(::JsonString)),
                ),
            ),
        )
        // Обёртка proot на телефоне передаёт аргументы в одинарных кавычках: кавычка внутри их сломала бы (D-05).
        if ('\'' in CanonicalJson.write(config)) return Outcome.failure(DiagCode.UNSUPPORTED, "кавычка ' в пути или команде не передаётся в ход на телефоне", "remote")
        return Outcome.success(config)
    }

    /**
     * Запрос хода для дерева [root] на target проекта: MCP-сервер дерева,
     * без встроенных инструментов, с подсказкой модели. Рабочий каталог
     * процесса — каталог агента target хода (`workDir = null`): один и тот
     * же для всех ходов, иначе `--resume` не найдёт сессию.
     */
    fun apply(request: ClaudeRequest, root: String): Outcome<ClaudeRequest> = when (val c = mcpConfig(root)) {
        is Outcome.Failure -> c
        is Outcome.Success -> Outcome.success(
            request.copy(
                workDir = null,
                tools = emptyList(),
                mcpConfig = c.value,
                strictMcpConfig = true,
                appendSystemPrompt = listOfNotNull(request.appendSystemPrompt, note(root)).joinToString("\n\n"),
            ),
        )
    }

    private fun note(root: String): String {
        val where = if (platform == TargetPlatform.WINDOWS) "на компьютере с Windows" else "на другом компьютере"
        val p = "mcp__${WorkspaceTools.SERVER}__"
        return "Рабочее дерево задачи — $where: $root. Встроенных инструментов нет; файлы и команды — только через " +
            "${p}read, ${p}write, ${p}edit, ${p}glob, ${p}grep и ${p}run (сборка, тесты, git). Пути — относительно корня дерева."
    }
}
