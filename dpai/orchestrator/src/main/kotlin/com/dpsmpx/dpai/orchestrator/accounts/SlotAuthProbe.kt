package com.dpsmpx.dpai.orchestrator.accounts

import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.accounts.SlotState
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.ExitReason
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
 * (`OutputCapture.AUTH_STATUS`, D-69): в журнале запуска — только
 * `dpai.auth-status`.
 *
 * Без ожидания: [start] запускает пробу, [poll] возвращает итог, когда
 * процесс вышел, и `null`, пока идёт (D-07: время — у вызывающего).
 */
class SlotAuthProbe(private val agent: AgentClient) {
    /** Исполнитель каждой пробы: по нему — разбор итога. */
    private val kinds = HashMap<SessionId, Executor>()

    fun start(run: SessionId, slot: SlotState): Outcome<SessionId> = start(run, slot.configDir, slot.executor)

    /**
     * Проба каталога, у которого слота ещё нет: по итогу выбирается подписка
     * слота. Команда — по исполнителю (D-100): `claude auth status --json`,
     * `codex login status` или проба `settings.json` и файла входа Gemini
     * (`docs/research/GEMINI_CLI.md`, §2; сам файл входа не читается).
     */
    fun start(run: SessionId, configDir: String, executor: Executor = Executor.CLAUDE): Outcome<SessionId> {
        val env = mapOf(SlotEnvironment.configVar(executor) to configDir)
        val unset = SlotEnvironment.overriding(executor)
        val spec = when (executor) {
            Executor.CLAUDE -> RunSpec(listOf("claude", "auth", "status", "--json"), setEnv = env, unsetEnv = unset, timeout = 60.seconds, capture = OutputCapture.AUTH_STATUS)
            Executor.CODEX -> RunSpec(listOf("codex", "login", "status"), setEnv = env, unsetEnv = unset, timeout = 60.seconds)
            Executor.GEMINI -> RunSpec(listOf("sh", "-c", GEMINI_PROBE), setEnv = env, unsetEnv = unset, timeout = 60.seconds)
        }
        return when (val s = agent.start(run, spec)) {
            is Outcome.Failure -> s
            is Outcome.Success -> Outcome.success(run).also { synchronized(kinds) { kinds[run] = executor } }
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
        val executor = synchronized(kinds) { kinds.remove(run) } ?: Executor.CLAUDE
        if (executor != Executor.CLAUDE) {
            if (status.reason == ExitReason.START_FAILED) {
                return Outcome.failure(DiagCode.PROCESS_START_FAILED, "проба входа: ${executor.title} на target не запустился — установлен ли он и есть ли в PATH", "run $run")
            }
            // «Not logged in» Codex пишет и в stderr: строки входа ищутся во всём выводе.
            val all = events.filter { it.type == EventType.TARGET_PROCESS_OUTPUT }.joinToString("\n") { (it.payload["text"] as? JsonString)?.value.orEmpty() }
            return if (executor == Executor.CODEX) AuthStatus.fromCodexStatus(all) else AuthStatus.fromGeminiProbe(all)
        }
        // Код 1 — «не вошёл», но JSON тот же (`docs/research/CLAUDE_CODE_CLI.md`, §4); агент свёл его в `dpai.auth-status`.
        return when (val json = JsonParser().parse(stdout)) {
            // Без сводки — сказать почему, а не гадать (D-05): старый агент спецификацию v2 отверг бы ещё
            // при запуске, а сюда доходят «claude не запустился» и тайм-аут (ревью 17 октября 2026).
            is Outcome.Failure -> Outcome.failure(
                when (status.reason) {
                    ExitReason.START_FAILED -> Diagnostic.error(DiagCode.PROCESS_START_FAILED, "проба входа: claude на target не запустился — установлен ли он и есть ли в PATH", "run $run")
                    ExitReason.TIMEOUT -> Diagnostic.error(DiagCode.PROCESS_TIMEOUT, "проба входа: claude auth status не ответил вовремя", "run $run")
                    else -> Diagnostic.error(DiagCode.CLAUDE_AUTH_STATUS_INVALID, "проба входа: агент не вернул dpai.auth-status (${status.reason?.name ?: "без причины"}, код ${status.exitCode})", "run $run")
                },
            )
            is Outcome.Success -> AuthStatus.fromAgentJson(json.value)
        }
    }

    companion object {
        /**
         * Проба входа Gemini (`docs/research/GEMINI_CLI.md`, §2): способ входа
         * из `settings.json` и есть ли файл входа — без чтения файла входа.
         */
        const val GEMINI_PROBE: String =
            "d=\"\$GEMINI_CLI_HOME/.gemini\"; " +
                "t=\$(sed -n 's/.*\"selectedType\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p' \"\$d/settings.json\" 2>/dev/null | head -n 1); " +
                "echo \"selectedType=\$t\"; " +
                "if [ -s \"\$d/oauth_creds.json\" ]; then echo creds=yes; else echo creds=no; fi"
    }
}
