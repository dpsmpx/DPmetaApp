package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Несколько targets за одним [ClaudeRunner] (`docs/EXECUTION_MODEL.md`,
 * §3): каждый вызов уходит раннеру того target, на котором сессия
 * записана в журнале. [targetOf] читает это из состояния сессий
 * оркестратора; [runnerFor] — раннер target (у каждого свой агент).
 * Неизвестная сессия или target без раннера — `ENTITY_NOT_FOUND`, а не
 * «первый попавшийся» (D-05).
 */
class RoutingRunner(
    private val targetOf: (SessionId) -> ExecutionTargetId?,
    private val runnerFor: (ExecutionTargetId) -> ClaudeRunner?,
) : ClaudeRunner {
    private fun <T> route(session: SessionId, call: ClaudeRunner.() -> Outcome<T>): Outcome<T> {
        val target = targetOf(session) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "сессия без target", "session $session")
        val runner = runnerFor(target) ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "у target нет раннера", "target $target")
        return runner.call()
    }

    override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> =
        route(session) { start(session, request, provider, resume) }

    override fun send(session: SessionId, request: ClaudeRequest): Outcome<RunnerState> = route(session) { send(session, request) }

    override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> = route(session) { stream(session) }

    override fun pause(session: SessionId): Outcome<RunnerState> = route(session) { pause(session) }

    override fun cancel(session: SessionId): Outcome<RunnerState> = route(session) { cancel(session) }

    override fun resume(session: SessionId, request: ClaudeRequest): Outcome<RunnerState> = route(session) { resume(session, request) }

    override fun inspect(session: SessionId): Outcome<RunnerState> = route(session) { inspect(session) }

    override fun terminate(session: SessionId): Outcome<RunnerState> = route(session) { terminate(session) }

    override fun position(session: SessionId): Long? = targetOf(session)?.let(runnerFor)?.position(session)

    override fun adopt(session: SessionId, provider: ProviderSessionId, afterSequence: Long, turnRunning: Boolean): Outcome<RunnerState> =
        route(session) { adopt(session, provider, afterSequence, turnRunning) }
}
