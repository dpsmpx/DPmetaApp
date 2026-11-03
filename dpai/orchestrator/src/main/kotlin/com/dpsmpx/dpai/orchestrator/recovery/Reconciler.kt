package com.dpsmpx.dpai.orchestrator.recovery

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.claude.ClaudeTurnAssembler
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.sessions.SessionEvent
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import kotlin.time.Duration.Companion.seconds

/**
 * Исход сверки (ТЗ, §55). `UNKNOWN` — target не ответил: сессия остаётся
 * `RECOVERY_PENDING`. `RESOLVED_NOT_STARTED` — запуск хода не был
 * подтверждён до сбоя, и target его не знает: хода не было (A-42).
 */
enum class Resolution { RESOLVED_RUNNING, RESOLVED_FAILED, RESOLVED_COMPLETED, RESOLVED_NOT_STARTED, UNKNOWN }

/**
 * Восстановление после сбоя (ТЗ, §54, §55; `docs/RECOVERY.md`, §1, §2):
 * рабочие сессии → `RECOVERY_PENDING`; затем проба агента target и
 * сверка. Представление оркестратора и target не выбираются наугад:
 * расхождение — `STATE_CONFLICT` с исходом.
 *
 * | Агент | Исход | Сессия |
 * |---|---|---|
 * | ход идёт | `RESOLVED_RUNNING` | `RUNNING` (раннер подхватывает с записанной позиции) |
 * | ход кончился, итог в журнале агента | `RESOLVED_COMPLETED` или `RESOLVED_FAILED` | по итогу хода |
 * | супервизор потерян | `RESOLVED_FAILED` после остановки процесса | `FAILED` |
 * | запуска нет | `RESOLVED_FAILED` (процесса нет — подтверждено) | `FAILED` |
 * | запуска нет, а запуск и не был подтверждён | `RESOLVED_NOT_STARTED` | `CANCELLED` — попытка не считается |
 * | target не отвечает | `UNKNOWN` | остаётся `RECOVERY_PENDING` |
 *
 * Сессия `QUEUED` (создана, а запуск хода не записан, A-42) тоже
 * сверяется: запуск мог дойти до агента до сбоя. Она проходит `STARTING` с
 * причиной [DiagCode.RECOVERY_START_UNCONFIRMED] — по ней сверка и после
 * следующего перезапуска знает, что хода могло не быть.
 */
class Reconciler(private val sessions: Sessions, private val clock: Clock, private val pageSize: Int = 10_000) {
    data class Result(val session: SessionId, val resolution: Resolution, val events: List<SessionEvent>, val diagnostics: List<Diagnostic>)

    private val working = setOf(SessionState.STARTING, SessionState.RUNNING, SessionState.WAITING, SessionState.NEAR_LIMIT, SessionState.VERIFYING)

    /** Шаг 1 (ТЗ, §54): сессии, которые по журналу работали или ждали запуска, — в `RECOVERY_PENDING`. */
    fun markPending(state: SessionsState): List<SessionEvent> {
        var s = state
        val out = mutableListOf<SessionEvent>()
        fun move(id: SessionId, to: SessionState, reason: DiagCode) {
            val es = sessions.transition(s, id, to, clock.now(), reason).valueOrNull().orEmpty()
            out += es
            s = Sessions.applyAll(s, es)
        }
        for (r in state.sessions.values.filter { it.state in working || it.state == SessionState.QUEUED }.sortedBy { it.id.value }) {
            if (r.state == SessionState.QUEUED) {
                move(r.id, SessionState.STARTING, DiagCode.RECOVERY_START_UNCONFIRMED)
                move(r.id, SessionState.RECOVERY_PENDING, DiagCode.RECOVERY_START_UNCONFIRMED)
            } else {
                move(r.id, SessionState.RECOVERY_PENDING, DiagCode.RECOVERY_RESTARTED)
            }
        }
        return out
    }

    /** Шаг 2: проба агента и сверка одной сессии в `RECOVERY_PENDING`. */
    fun reconcile(state: SessionsState, id: SessionId, agent: AgentClient, executor: com.dpsmpx.dpai.core.ids.Executor = com.dpsmpx.dpai.core.ids.Executor.CLAUDE): Result {
        val r = state.sessions[id] ?: return Result(id, Resolution.UNKNOWN, emptyList(), listOf(Diagnostic.error(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session $id")))
        if (r.state != SessionState.RECOVERY_PENDING) return Result(id, Resolution.UNKNOWN, emptyList(), emptyList())
        val now = clock.now()
        fun resolve(resolution: Resolution, to: SessionState, reason: DiagCode?, note: String): Result {
            val events = sessions.transition(state, id, to, now, reason).valueOrNull().orEmpty()
            return Result(id, resolution, events, listOf(Diagnostic.info(DiagCode.STATE_CONFLICT, "${resolution.name}: $note", "session $id")))
        }
        val status = when (val s = agent.status(id)) {
            is Outcome.Success -> s.value
            is Outcome.Failure ->
                return if (s.errors.any { it.code == DiagCode.ENTITY_NOT_FOUND } && r.lastReason == DiagCode.RECOVERY_START_UNCONFIRMED) {
                    resolve(Resolution.RESOLVED_NOT_STARTED, SessionState.CANCELLED, DiagCode.RECOVERY_START_UNCONFIRMED, "запуск не дошёл до target: хода не было")
                } else if (s.errors.any { it.code == DiagCode.ENTITY_NOT_FOUND }) {
                    resolve(Resolution.RESOLVED_FAILED, SessionState.FAILED, DiagCode.AGENT_RUN_LOST, "target не знает запуска: процесса нет")
                } else {
                    Result(id, Resolution.UNKNOWN, emptyList(), listOf(Diagnostic.of(DiagCode.STATE_CONFLICT, "UNKNOWN: target не ответил", "session $id", com.dpsmpx.dpai.core.diagnostics.Severity.WARNING, s.errors)))
                }
        }
        return when (status.state) {
            RunState.STARTING, RunState.RUNNING -> resolve(Resolution.RESOLVED_RUNNING, SessionState.RUNNING, null, "ход ${status.turn} идёт")
            RunState.LOST -> {
                // Супервизор умер, процесс мог остаться — остановить дерево, иначе сирота (ТЗ, §119).
                val stopped = agent.stop(id, 5.seconds)
                val survivors = stopped.valueOrNull()?.stop?.survivors.orEmpty()
                val res = resolve(Resolution.RESOLVED_FAILED, SessionState.FAILED, DiagCode.AGENT_RUN_LOST, "супервизор потерян, процесс остановлен")
                if (stopped is Outcome.Failure || survivors.isNotEmpty()) {
                    res.copy(diagnostics = res.diagnostics + Diagnostic.error(DiagCode.PROCESS_ORPHANS_LEFT, "остановить процесс не удалось: ${survivors.joinToString()}", "session $id"))
                } else {
                    res
                }
            }
            RunState.EXITED -> {
                val turn = lastTurn(id, r.provider, agent, executor)
                when (turn) {
                    TurnStatus.COMPLETED -> resolve(Resolution.RESOLVED_COMPLETED, SessionState.COMPLETED, null, "ход завершился, пока приложения не было")
                    TurnStatus.INCOMPLETE -> resolve(Resolution.RESOLVED_FAILED, SessionState.PAUSED, DiagCode.CLAUDE_STREAM_INCOMPLETE, "ход прерван, пока приложения не было")
                    else -> resolve(Resolution.RESOLVED_FAILED, SessionState.FAILED, status.reason?.let { reasonCode(it) } ?: DiagCode.SESSION_FAILED, "ход завершился неудачей")
                }
            }
        }
    }

    /** Итог последнего хода — из событий агента: начало хода, вывод, выход. */
    private fun lastTurn(id: SessionId, provider: com.dpsmpx.dpai.core.ids.ProviderSessionId, agent: AgentClient, executor: com.dpsmpx.dpai.core.ids.Executor): TurnStatus? {
        // Все страницы (ревью A-64): одна страница с начала — первые [pageSize] событий и не больше
        // объёма строки ответа агента; итог последнего хода длинной сессии оставался за ней.
        // В памяти — только хвост с последнего начала хода.
        val events = mutableListOf<com.dpsmpx.dpai.core.events.EventEnvelope>()
        var after = 0L
        while (true) {
            val page = agent.events(id, after, pageSize).valueOrNull() ?: return null
            for (e in page.events) {
                if (e.type == EventType.TARGET_PROCESS_STARTED) events.clear()
                events += e
            }
            if (!page.more || page.events.isEmpty()) break
            after = page.events.last().sequence
        }
        val startIdx = events.indexOfLast { it.type == EventType.TARGET_PROCESS_STARTED }
        if (startIdx < 0) return null
        val asm = ClaudeTurnAssembler(provider, executor)
        var exitCode: Int? = null
        var reason: ExitReason? = null
        for (e in events.drop(startIdx + 1)) {
            when (e.type) {
                EventType.TARGET_PROCESS_OUTPUT -> if ((e.payload["stream"] as? JsonString)?.value == "stdout") {
                    asm.feedAll((e.payload["text"] as? JsonString)?.value.orEmpty(), (e.payload["truncated"] as? JsonBool)?.value ?: false)
                } else {
                    // Отказ входа Gemini — только в stderr (D-100).
                    asm.stderr((e.payload["text"] as? JsonString)?.value.orEmpty())
                }
                EventType.TARGET_PROCESS_EXITED -> {
                    exitCode = (e.payload["exitCode"] as? JsonNumber)?.toLongExact()?.toInt()
                    reason = (e.payload["reason"] as? JsonString)?.value?.let { n -> ExitReason.entries.firstOrNull { it.name == n } }
                }
                else -> Unit
            }
        }
        return asm.finish(exitCode, reason).status
    }

    private fun reasonCode(r: ExitReason): DiagCode = when (r) {
        ExitReason.TIMEOUT -> DiagCode.PROCESS_TIMEOUT
        ExitReason.START_FAILED -> DiagCode.PROCESS_START_FAILED
        ExitReason.LOST -> DiagCode.AGENT_RUN_LOST
        else -> DiagCode.SESSION_FAILED
    }
}
