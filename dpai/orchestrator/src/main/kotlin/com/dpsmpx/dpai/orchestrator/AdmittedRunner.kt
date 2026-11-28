package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AiAdmission
import com.dpsmpx.dpai.accounts.AiModelRef
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCli
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.RunnerState
import com.dpsmpx.dpai.execution.claude.RunnerUpdate

/** Допуск перед реальным CLI; журнал намерения переживает смерть процесса Android. */
class AdmittedRunner(
    private val delegate: ClaudeRunner,
    private val agent: AgentClient,
    private val target: ExecutionTargetId,
    private val admission: AiAdmission = AiAdmission.global,
    private val location: String = Hashing.sha256(target.value).hex,
) : ClaudeRunner by delegate {
    @Synchronized
    override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> =
        turn(session, request) { delegate.start(session, it, provider, resume) }

    @Synchronized
    override fun send(session: SessionId, request: ClaudeRequest): Outcome<RunnerState> = turn(session, request) { delegate.send(session, it) }

    @Synchronized
    override fun resume(session: SessionId, request: ClaudeRequest): Outcome<RunnerState> = turn(session, request) { delegate.resume(session, it) }

    private fun turn(session: SessionId, request: ClaudeRequest, launch: (ClaudeRequest) -> Outcome<RunnerState>): Outcome<RunnerState> {
        if (admission.configured() && request.executor != Executor.CLAUDE) return Outcome.failure(
            DiagCode.OPERATION_NOT_ALLOWED, "для этого CLI ещё не подтверждён запрет делегирования в общем режиме ИИ",
        )
        val effective = controlled(request)
        val previous = delegate.inspect(session).valueOrNull()?.run?.turn ?: 0
        if (previous < 0 || previous == Int.MAX_VALUE) return Outcome.failure(DiagCode.VALUE_INVALID, "недопустимый номер предыдущего ИИ-хода")
        val nextTurn = previous + 1
        when (val r = admission.acquire(session.value, AiModelRef.native(effective.executor, effective.model), target, nextTurn, location)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> Unit
        }
        // Даже исключение транспорта не доказывает, что процесс не запустился.
        val result = launch(effective)
        reconcile(session)
        return result
    }

    private fun controlled(request: ClaudeRequest): ClaudeRequest {
        if (!admission.configured()) return request
        return request.copy(fallbackModel = null, disallowedTools = (request.disallowedTools + ClaudeCodeCli.SUBAGENT_TOOL).distinct())
    }

    /** Только подтверждённый выход; LOST и потерянный ответ сохраняют резерв. */
    @Synchronized
    fun reconcile(session: SessionId): Outcome<Unit> {
        val pending = admission.snapshot().pending.firstOrNull { it.owner == session.value } ?: return Outcome.success(Unit)
        if (pending.target != target || pending.location != location) return Outcome.failure(DiagCode.STATE_CONFLICT, "адрес ИИ-запуска изменён: исходный target ещё не сверен")
        return when (val r = agent.status(session)) {
            is Outcome.Failure -> if (r.errors.any { it.code == DiagCode.ENTITY_NOT_FOUND }) {
                // Исключаем запоздавший первый start. ID больше не используется, новый запуск получит новый ID.
                when (val fenced = agent.fenceStop(session)) { is Outcome.Failure -> fenced; is Outcome.Success -> admission.release(session.value) }
            } else r
            is Outcome.Success -> {
                if (r.value.state != RunState.EXITED || r.value.turn < (pending.turn ?: 1)) return Outcome.success(Unit)
                val page = when (val read = agent.events(session, (r.value.lastSequence - 16).coerceAtLeast(0), 32)) {
                    is Outcome.Failure -> return read
                    is Outcome.Success -> read.value
                }
                val exit = page.events.lastOrNull { it.type == EventType.TARGET_PROCESS_EXITED &&
                    (it.payload["turn"] as? JsonNumber)?.toLongExact() == r.value.turn.toLong() }
                    ?: return Outcome.failure(DiagCode.AGENT_STOP_PENDING, "нет подтверждённого события выхода ИИ-хода")
                if ((exit.payload["survivors"] as? JsonArray)?.items?.isNotEmpty() == true) {
                    Outcome.failure(DiagCode.PROCESS_ORPHANS_LEFT, "после ИИ-хода остались процессы; резерв сохранён")
                } else admission.release(session.value)
            }
        }
    }

    @Synchronized override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> = delegate.stream(session).also { reconcile(session) }
    @Synchronized override fun inspect(session: SessionId): Outcome<RunnerState> = delegate.inspect(session).also { reconcile(session) }
    @Synchronized override fun pause(session: SessionId): Outcome<RunnerState> = delegate.pause(session).also { reconcile(session) }
    @Synchronized override fun cancel(session: SessionId): Outcome<RunnerState> = delegate.cancel(session).also { reconcile(session) }
    @Synchronized override fun terminate(session: SessionId): Outcome<RunnerState> = delegate.terminate(session).also { reconcile(session) }

    @Synchronized override fun adopt(session: SessionId, provider: ProviderSessionId, afterSequence: Long, turnRunning: Boolean, executor: Executor): Outcome<RunnerState> {
        if (turnRunning) {
            when (val r = admission.recover(session.value, AiModelRef.native(executor, null), target, location)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> Unit
            }
        }
        return delegate.adopt(session, provider, afterSequence, turnRunning, executor).also { reconcile(session) }
    }
}
