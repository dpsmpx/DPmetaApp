package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ProviderSessionId

/**
 * Команда хода по исполнителю (D-100): только документированные флаги
 * (`docs/research/CLAUDE_CODE_CLI.md`, `CODEX_CLI.md`, `GEMINI_CLI.md`);
 * задание — стандартным вводом.
 */
object ExecutorCli {
    /** Команды исполнителей по умолчанию: имя на `PATH` target. */
    val DEFAULT_COMMANDS: Map<Executor, List<String>> = mapOf(
        Executor.CLAUDE to listOf("claude"),
        Executor.CODEX to listOf("codex"),
        Executor.GEMINI to listOf("gemini"),
    )

    fun argv(command: List<String>, request: ClaudeRequest, session: ProviderSessionId, resume: Boolean): List<String> = when (request.executor) {
        Executor.CLAUDE -> ClaudeCodeCli.argv(command, request, session, resume)
        Executor.CODEX -> CodexCli.argv(command, request, resume)
        Executor.GEMINI -> GeminiCli.argv(command, request, session, resume)
    }
}

/**
 * `codex exec --json` (`docs/research/CODEX_CLI.md`): id сессии Codex
 * назначает сам, поэтому продолжение — `resume --last` в рабочем дереве
 * задачи (§5); ход ревью — `--ephemeral`, он в этот список не попадает.
 * Песочница — `workspace-write`, у ревью — `read-only`.
 */
object CodexCli {
    val DOCUMENTED_FLAGS: Set<String> = setOf("exec", "resume", "--last", "--json", "--skip-git-repo-check", "-c", "-m", "--ephemeral", "-")

    /** Усилие хода (`--effort` Claude) — в `model_reasoning_effort` Codex; `max` у Codex — `xhigh`. */
    private val EFFORT = mapOf("minimal" to "minimal", "low" to "low", "medium" to "medium", "high" to "high", "xhigh" to "xhigh", "max" to "xhigh")

    fun argv(codex: List<String>, request: ClaudeRequest, resume: Boolean): List<String> = buildList {
        addAll(codex)
        add("exec")
        if (resume) {
            add("resume")
            add("--last")
        }
        add("--json")
        add("--skip-git-repo-check")
        add("-c")
        add("sandbox_mode=\"${if (request.readOnly) "read-only" else "workspace-write"}\"")
        if (request.readOnly) add("--ephemeral")
        request.model?.let { add("-m"); add(it) }
        request.effort?.let(EFFORT::get)?.let { add("-c"); add("model_reasoning_effort=\"$it\"") }
        add("-")
    }
}

/**
 * `gemini -p --output-format stream-json` (`docs/research/GEMINI_CLI.md`):
 * id сессии назначает оркестратор (`--session-id`), продолжение —
 * `--resume` того же id. `--skip-trust` — рабочее дерево задачи доверено на
 * эту сессию, иначе режим одобрений сбрасывается. Режим — `auto_edit`
 * (правки без вопроса, команды оболочки — нет), у ревью — `plan`.
 */
object GeminiCli {
    val DOCUMENTED_FLAGS: Set<String> = setOf("-p", "--output-format", "--session-id", "--resume", "--skip-trust", "--approval-mode", "-m")

    /** Значение `-p`: задание — stdin, к нему Gemini добавляет `-p` через пустую строку. */
    const val PROMPT_TAIL = " "

    fun argv(gemini: List<String>, request: ClaudeRequest, session: ProviderSessionId, resume: Boolean): List<String> = buildList {
        addAll(gemini)
        add("-p")
        add(PROMPT_TAIL)
        add("--output-format")
        add("stream-json")
        add(if (resume) "--resume" else "--session-id")
        add(session.value)
        add("--skip-trust")
        add("--approval-mode")
        add(if (request.readOnly) "plan" else "auto_edit")
        request.model?.let { add("-m"); add(it) }
    }
}
