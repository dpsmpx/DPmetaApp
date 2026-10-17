package com.dpsmpx.dpai.orchestrator.accounts

import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.accounts.SlotState
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.OutputCapture
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import kotlin.time.Duration.Companion.seconds

/**
 * Вход слота на его target (`docs/ACCOUNT_MODEL.md`; D-27, D-29):
 * `claude auth status --json` под агентом с окружением слота — каталог
 * конфигурации слота, без переменных, перекрывающих вход. Итог —
 * [AuthStatus], который оркестратор применяет к слоту
 * (`Accounts.observeAuthStatus`). Токенов вывод не содержит; email и
 * организацию агент сводит в отпечаток на target и на диск их не пишет
 * (`OutputCapture.AUTH_STATUS`, D-55): в журнале запуска — только
 * `dpai.auth-status`.
 *
 * Без ожидания: [start] запускает пробу, [poll] возвращает итог, когда
 * процесс вышел, и `null`, пока идёт (D-07: время — у вызывающего).
 */
class SlotAuthProbe(private val agent: AgentClient) {
    fun start(run: SessionId, slot: SlotState): Outcome<SessionId> = start(run, slot.configDir)

    /** Проба каталога, у которого слота ещё нет: по итогу выбирается подписка слота. */
    fun start(run: SessionId, configDir: String): Outcome<SessionId> {
        val spec = RunSpec(
            listOf("claude", "auth", "status", "--json"),
            setEnv = mapOf(SlotEnvironment.CONFIG_DIR to configDir),
            unsetEnv = SlotEnvironment.OVERRIDING,
            timeout = 60.seconds,
            capture = OutputCapture.AUTH_STATUS,
        )
        return when (val s = agent.start(run, spec)) {
            is Outcome.Failure -> s
            is Outcome.Success -> Outcome.success(run)
        }
    }

    fun poll(run: SessionId): Outcome<AuthStatus?> {
        val status = when (val s = agent.status(run)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        if (!status.finished) return Outcome.success(null)
        if (status.state == RunState.LOST) return Outcome.failure(DiagCode.AGENT_RUN_LOST, "проба входа потеряна", "run $run")
        val events = when (val e = agent.events(run, 0, 1000)) {
            is Outcome.Failure -> return e
            is Outcome.Success -> e.value.events
        }
        val stdout = events.filter { it.type == EventType.TARGET_PROCESS_OUTPUT && (it.payload["stream"] as? JsonString)?.value == "stdout" }
            .joinToString("\n") { (it.payload["text"] as? JsonString)?.value.orEmpty() }
        // Код 1 — «не вошёл», но JSON тот же (`docs/research/CLAUDE_CODE_CLI.md`, §4); агент свёл его в `dpai.auth-status`.
        return when (val json = JsonParser().parse(stdout)) {
            is Outcome.Failure -> Outcome.failure(DiagCode.CLAUDE_AUTH_STATUS_INVALID, "проба входа: агент не вернул dpai.auth-status (код ${status.exitCode}; агент на target устарел?)", "run $run")
            is Outcome.Success -> AuthStatus.fromAgentJson(json.value)
        }
    }
}
