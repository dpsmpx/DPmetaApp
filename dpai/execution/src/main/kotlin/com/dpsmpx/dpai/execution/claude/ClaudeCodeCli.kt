package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import java.math.BigDecimal
import kotlin.time.Duration

/**
 * Запрос хода Claude (ТЗ, §12). Prompt уходит на стандартный вход, а не
 * аргументом (D-14; длина командной строки ограничена). [permissionMode],
 * инструменты, бюджет, схема — документированные флаги
 * (`docs/research/CLAUDE_CODE_CLI.md`, §2, §8). [setEnv] и [unsetEnv] —
 * правка окружения агента для слота сессии: `CLAUDE_CONFIG_DIR` слота и
 * удаление переменных, перекрывающих вход (D-27, D-29).
 */
data class ClaudeRequest(
    val prompt: String,
    val model: String? = null,
    /** Усилие хода (`--effort`: low … max) и запасная модель — маршрут D-40 (`docs/EFFICIENCY.md`, §7). */
    val effort: String? = null,
    val fallbackModel: String? = null,
    /**
     * Каталог, окружение и `git status` — из системного prompt в первое
     * сообщение (`--exclude-dynamic-system-prompt-sections`): сессии разных
     * рабочих деревьев делят кеш prompt (D-40, п. 6).
     */
    val shareSystemPromptCache: Boolean = false,
    val permissionMode: String? = null,
    val tools: List<String>? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val maxBudgetUsd: BigDecimal? = null,
    val jsonSchema: JsonObject? = null,
    val addDirs: List<String> = emptyList(),
    val appendSystemPrompt: String? = null,
    /**
     * Серверы MCP хода (`--mcp-config`, строка JSON) и только они
     * (`--strict-mcp-config`): рабочее дерево на другом target (D-50).
     */
    val mcpConfig: JsonObject? = null,
    val strictMcpConfig: Boolean = false,
    val workDir: String? = null,
    val timeout: Duration? = null,
    val setEnv: Map<String, String> = emptyMap(),
    val unsetEnv: List<String> = emptyList(),
    /** Исполнитель хода (D-100): Claude Code, Codex CLI или Gemini CLI — по слоту сессии. */
    val executor: Executor = Executor.CLAUDE,
    /**
     * Ход только для чтения (ревью, D-73): у Claude — набор инструментов
     * [tools], у Codex — песочница `read-only`, у Gemini — режим `plan`.
     */
    val readOnly: Boolean = false,
) {
    init {
        require(prompt.isNotBlank()) { "пустой prompt" }
        require(maxBudgetUsd == null || maxBudgetUsd.signum() > 0) { "бюджет — положительный" }
    }
}

/**
 * Команда Claude Code для хода (D-04, D-32): только документированные флаги
 * ([DOCUMENTED_FLAGS] сверяется с исследованием тестом
 * `MockRunnerTest`). Первый ход — `--session-id`: id сессии провайдера
 * назначает оркестратор и пишет в журнал до запуска; следующие —
 * `--resume` того же id (`docs/research/CLAUDE_CODE_CLI.md`, §3).
 */
object ClaudeCodeCli {
    /**
     * Режим разрешений хода оркестратора (D-38): в `-p` спросить человека
     * некому, и без режима правки и команды отклоняются — ход «завершается»
     * без изменений. Какие действия безопасны, решает Claude Code (`auto`).
     */
    const val TURN_PERMISSION_MODE: String = "auto"

    /** Инструмент субагентов Claude Code (`docs/research/CLAUDE_CODE_CLI.md`, §11): запрет — `--disallowedTools` (D-40). */
    const val SUBAGENT_TOOL: String = "Agent"

    val DOCUMENTED_FLAGS: Set<String> = setOf(
        "-p", "--verbose", "--output-format", "--session-id", "--resume", "--model", "--permission-mode",
        "--tools", "--allowedTools", "--disallowedTools", "--max-budget-usd", "--json-schema", "--add-dir",
        "--append-system-prompt", "--effort", "--fallback-model", "--exclude-dynamic-system-prompt-sections",
        "--mcp-config", "--strict-mcp-config",
    )

    fun argv(claude: List<String>, request: ClaudeRequest, session: ProviderSessionId, resume: Boolean): List<String> = buildList {
        addAll(claude)
        add("-p")
        // stream-json при -p требует --verbose (проверено запуском, §8).
        add("--verbose")
        add("--output-format")
        add("stream-json")
        add(if (resume) "--resume" else "--session-id")
        add(session.value)
        request.model?.let { add("--model"); add(it) }
        request.fallbackModel?.let { add("--fallback-model"); add(it) }
        request.effort?.let { add("--effort"); add(it) }
        if (request.shareSystemPromptCache) add("--exclude-dynamic-system-prompt-sections")
        request.permissionMode?.let { add("--permission-mode"); add(it) }
        request.tools?.let { add("--tools"); add(it.joinToString(",")) }
        if (request.allowedTools.isNotEmpty()) { add("--allowedTools"); addAll(request.allowedTools) }
        if (request.disallowedTools.isNotEmpty()) { add("--disallowedTools"); addAll(request.disallowedTools) }
        request.maxBudgetUsd?.let { add("--max-budget-usd"); add(it.toPlainString()) }
        request.jsonSchema?.let { add("--json-schema"); add(CanonicalJson.write(it)) }
        for (d in request.addDirs) { add("--add-dir"); add(d) }
        request.appendSystemPrompt?.let { add("--append-system-prompt"); add(it) }
        request.mcpConfig?.let { add("--mcp-config"); add(CanonicalJson.write(it)) }
        if (request.strictMcpConfig) add("--strict-mcp-config")
    }

    /** Флаги в команде (без значений) — для сверки с [DOCUMENTED_FLAGS]. */
    fun flagsOf(argv: List<String>): Set<String> = argv.filter { it.startsWith("-") }.toSet()
}
