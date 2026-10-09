package com.dpsmpx.dpai.orchestrator.accounts

import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.claude.UsageReport
import kotlin.time.Duration.Companion.seconds

/**
 * Загрузка окон подписки без хода (D-48): локальная команда `/usage`
 * Claude Code под агентом с окружением слота — к модели запроса нет,
 * лимит не тратится. Как [SlotAuthProbe]: [start] запускает, [poll]
 * возвращает окна, когда процесс вышел, и `null`, пока идёт.
 */
class UsageProbe(private val agent: AgentClient) {
    fun start(run: SessionId, configDir: String): Outcome<SessionId> {
        val spec = RunSpec(
            listOf("claude", "-p", "--verbose", "--output-format", "stream-json"),
            // Каталог слота — рабочий: без сканирования проекта и его настроек.
            workDir = configDir,
            setEnv = mapOf(SlotEnvironment.CONFIG_DIR to configDir),
            unsetEnv = SlotEnvironment.OVERRIDING,
            timeout = 60.seconds,
            stdin = "/usage\n",
        )
        return when (val s = agent.start(run, spec)) {
            is Outcome.Failure -> s
            is Outcome.Success -> Outcome.success(run)
        }
    }

    fun poll(run: SessionId): Outcome<List<UsageReport.Window>?> {
        val status = when (val s = agent.status(run)) {
            is Outcome.Failure -> return s
            is Outcome.Success -> s.value
        }
        if (!status.finished) return Outcome.success(null)
        if (status.state == RunState.LOST) return Outcome.failure(DiagCode.AGENT_RUN_LOST, "проба /usage потеряна", "run $run")
        val events = when (val e = agent.events(run, 0, 1000)) {
            is Outcome.Failure -> return e
            is Outcome.Success -> e.value.events
        }
        val stdout = events.filter { it.type == EventType.TARGET_PROCESS_OUTPUT && (it.payload["stream"] as? JsonString)?.value == "stdout" }
            .map { (it.payload["text"] as? JsonString)?.value.orEmpty() }
        return UsageReport.parse(stdout)
    }
}
