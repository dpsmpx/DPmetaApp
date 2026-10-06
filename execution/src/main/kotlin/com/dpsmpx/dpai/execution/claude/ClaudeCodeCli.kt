package com.dpsmpx.dpai.execution.claude

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
    val permissionMode: String? = null,
    val tools: List<String>? = null,
    val allowedTools: List<String> = emptyList(),
    val disallowedTools: List<String> = emptyList(),
    val maxBudgetUsd: BigDecimal? = null,
    val jsonSchema: JsonObject? = null,
    val addDirs: List<String> = emptyList(),
    val appendSystemPrompt: String? = null,
    val workDir: String? = null,
    val timeout: Duration? = null,
    val setEnv: Map<String, String> = emptyMap(),
    val unsetEnv: List<String> = emptyList(),
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
    val DOCUMENTED_FLAGS: Set<String> = setOf(
        "-p", "--verbose", "--output-format", "--session-id", "--resume", "--model", "--permission-mode",
        "--tools", "--allowedTools", "--disallowedTools", "--max-budget-usd", "--json-schema", "--add-dir",
        "--append-system-prompt",
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
        request.permissionMode?.let { add("--permission-mode"); add(it) }
        request.tools?.let { add("--tools"); add(it.joinToString(",")) }
        if (request.allowedTools.isNotEmpty()) { add("--allowedTools"); addAll(request.allowedTools) }
        if (request.disallowedTools.isNotEmpty()) { add("--disallowedTools"); addAll(request.disallowedTools) }
        request.maxBudgetUsd?.let { add("--max-budget-usd"); add(it.toPlainString()) }
        request.jsonSchema?.let { add("--json-schema"); add(CanonicalJson.write(it)) }
        for (d in request.addDirs) { add("--add-dir"); add(d) }
        request.appendSystemPrompt?.let { add("--append-system-prompt"); add(it) }
    }

    /** Флаги в команде (без значений) — для сверки с [DOCUMENTED_FLAGS]. */
    fun flagsOf(argv: List<String>): Set<String> = argv.filter { it.startsWith("-") }.toSet()
}
