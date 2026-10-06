package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.SlotEnvironment
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.HeartbeatFreshness
import com.dpsmpx.dpai.core.state.SessionLifecycle
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TransitionMode

/**
 * Решения о сессиях (`docs/SESSION_MODEL.md`; ТЗ, §8, §15, §29). Как и у
 * аккаунтов: операция получает состояние и время и возвращает события;
 * состояние меняет только [apply]. Детерминировано (D-22).
 */
class Sessions(val policy: SessionPolicy = SessionPolicy()) {
    /** Итог создания: сессия и события (пусто — сессия с этим ключом уже была). */
    data class Created(val session: SessionId, val events: List<SessionEvent>)

    /**
     * Создать сессию на арендованном слоте. Повтор с тем же [key] — та же
     * сессия без новых событий (ТЗ, §5.4). Продолжение [continues] —
     * только конечной сессии и только одно живое (`docs/SESSION_MODEL.md`,
     * §2): второй запрос продолжения возвращает уже начатое.
     */
    fun create(
        state: SessionsState,
        accounts: AccountsState,
        id: SessionId,
        key: String,
        task: TaskId,
        slot: AccountId,
        target: ExecutionTargetId,
        provider: ProviderSessionId,
        now: Timestamp,
        continues: SessionId? = null,
    ): Outcome<Created> {
        if (key.isBlank() || key.length > 200) return Outcome.failure(DiagCode.VALUE_INVALID, "ключ идемпотентности — 1…200 знаков")
        state.byKey(key)?.let { return Outcome.success(Created(it.id, emptyList()), listOf(repeated(it.id))) }
        if (id in state.sessions) return Outcome.failure(DiagCode.VALUE_INVALID, "сессия с этим id уже есть под другим ключом", "session $id")
        if (continues != null) {
            val prev = state.sessions[continues] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет продолжаемой сессии", "session $continues")
            state.continuationOf(continues)?.let { return Outcome.success(Created(it.id, emptyList()), listOf(repeated(it.id))) }
            if (!prev.terminal && prev.state != SessionState.PAUSED) {
                return Outcome.failure(DiagCode.INVALID_TRANSITION, "продолжают конечную или приостановленную сессию, а эта — ${prev.state}", "session $continues")
            }
        }
        val s = accounts.slots[slot] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого слота", "slot $slot")
        if (s.sessions[id] != task) {
            return Outcome.failure(DiagCode.SESSION_BINDING_MISMATCH, "слот не арендован этой сессией", "session $id")
        }
        if (s.targetId != target) return Outcome.failure(DiagCode.SESSION_BINDING_MISMATCH, "слот на другом target", "session $id")
        val created = SessionEvent.Created(id, key, task, slot, s.subscriptionId, target, s.configDir, provider, continues, now)
        return Outcome.success(Created(id, listOf(created, changed(id, SessionState.CREATED, SessionState.QUEUED, null, now))))
    }

    /** Окружение хода для слота: что задать и что удалить в окружении агента target. */
    data class SlotBinding(val setEnv: Map<String, String>, val unsetEnv: List<String>)

    /**
     * Сверка перед каждым ходом (ТЗ, §66, §109): слот по-прежнему арендован
     * этой сессией, на том же target, с тем же каталогом настроек и в той
     * же подписке. Окружение хода — из слота этой сессии: его
     * `CLAUDE_CONFIG_DIR` и удаление переменных, перекрывающих вход
     * ([SlotEnvironment], D-29), — учётные данные другого слота в ход не
     * попадут.
     */
    fun bindingFor(state: SessionsState, accounts: AccountsState, id: SessionId): Outcome<SlotBinding> {
        val r = state.sessions[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session $id")
        val slot = accounts.slots[r.slot] ?: return Outcome.failure(DiagCode.SESSION_BINDING_MISMATCH, "слот сессии исчез", "session $id")
        val problems = buildList {
            if (id !in slot.sessions) add("слот ${slot.id} не арендован этой сессией")
            if (slot.targetId != r.target) add("слот на target ${slot.targetId}, сессия — на ${r.target}")
            if (slot.configDir != r.configDir) add("каталог настроек слота сменился")
            if (slot.subscriptionId != r.subscription) add("слот другой подписки")
        }
        if (problems.isNotEmpty()) return Outcome.failure(DiagCode.SESSION_BINDING_MISMATCH, problems.joinToString("; "), "session $id")
        return Outcome.success(SlotBinding(mapOf(SlotEnvironment.CONFIG_DIR to slot.configDir), SlotEnvironment.OVERRIDING))
    }

    /** Переход по автомату `SessionLifecycle`; повтор в текущее состояние — без событий. */
    fun transition(
        state: SessionsState,
        id: SessionId,
        to: SessionState,
        now: Timestamp,
        reason: DiagCode? = null,
        mode: TransitionMode = TransitionMode.ORDINARY,
    ): Outcome<List<SessionEvent>> {
        val r = state.sessions[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session $id")
        return when (val t = SessionLifecycle.table.transition(r.state, to, mode, "session $id")) {
            is Outcome.Failure -> t
            is Outcome.Success -> Outcome.success(if (t.value.changed) listOf(changed(id, r.state, to, reason, now)) else emptyList())
        }
    }

    /**
     * Heartbeat живой сессии; у конечной — ничего (D-12: поздний heartbeat
     * не воскрешает). [agentSequence] — позиция в потоке агента: новая
     * позиция пишется всегда, даже в ту же миллисекунду.
     */
    fun heartbeat(state: SessionsState, id: SessionId, now: Timestamp, agentSequence: Long? = null): List<SessionEvent> {
        val r = state.sessions[id] ?: return emptyList()
        if (r.terminal) return emptyList()
        val advanced = agentSequence != null && agentSequence > r.agentSequence
        if (!advanced && r.lastHeartbeat != null && r.lastHeartbeat >= now) return emptyList()
        return listOf(SessionEvent.Heartbeat(id, now, agentSequence?.takeIf { advanced }))
    }

    /**
     * Свежесть знания о сессии (ТЗ, §15). Пропуск heartbeat даёт `AGING`,
     * `STALE`, `UNKNOWN` — но не `DEAD`: «упала» — только когда проба target
     * подтвердила, что процесса нет ([processConfirmedAbsent], D-13).
     */
    fun freshness(record: SessionRecord, now: Timestamp, processConfirmedAbsent: Boolean = false): HeartbeatFreshness =
        HeartbeatFreshness.classify(record.lastHeartbeat ?: record.createdAt.takeIf { record.state == SessionState.QUEUED }, now, policy.heartbeat, processConfirmedAbsent)

    /**
     * Что делать с работающей сессией по свежести: только `DEAD` (проба
     * подтвердила отсутствие процесса) ведёт к сверке `RECOVERY_PENDING`;
     * `STALE` и `UNKNOWN` — показать как устаревшую, состояние не трогать.
     */
    fun onProbe(state: SessionsState, id: SessionId, now: Timestamp, processConfirmedAbsent: Boolean): Outcome<List<SessionEvent>> {
        val r = state.sessions[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session $id")
        if (r.terminal || freshness(r, now, processConfirmedAbsent) != HeartbeatFreshness.DEAD) return Outcome.success(emptyList())
        return transition(state, id, SessionState.RECOVERY_PENDING, now, DiagCode.AGENT_RUN_LOST)
    }

    private fun changed(id: SessionId, from: SessionState, to: SessionState, reason: DiagCode?, now: Timestamp) =
        SessionEvent.StateChanged(id, from, to, reason, now)

    private fun repeated(id: SessionId) = Diagnostic.info(DiagCode.SESSION_ALREADY_EXISTS, "повтор запроса: сессия уже создана", "session $id")

    companion object {
        /** Свёртка события в состояние; недопустимый переход в журнале — несовместимость, а не тихая правка. */
        fun apply(state: SessionsState, e: SessionEvent): SessionsState {
            val sessions = state.sessions.toMutableMap()
            when (e) {
                is SessionEvent.Created -> sessions[e.session] = SessionRecord(
                    e.session, e.key, e.task, e.slot, e.subscription, e.target, e.configDir, e.provider, e.continues,
                    createdAt = e.at, updatedAt = e.at,
                )
                is SessionEvent.StateChanged -> sessions.computeIfPresent(e.session) { _, r ->
                    check(SessionLifecycle.table.isAllowed(r.state, e.to, TransitionMode.EXPLICIT)) { "сессия ${e.session}: ${r.state} → ${e.to}" }
                    r.copy(state = e.to, updatedAt = e.at, lastReason = e.reason ?: r.lastReason)
                }
                is SessionEvent.Heartbeat -> sessions.computeIfPresent(e.session) { _, r ->
                    r.copy(lastHeartbeat = e.at, updatedAt = e.at, agentSequence = maxOf(r.agentSequence, e.agentSequence ?: 0))
                }
                is SessionEvent.TurnStarted -> sessions.computeIfPresent(e.session) { _, r -> r.copy(turn = e.turn, lastHeartbeat = e.at, updatedAt = e.at) }
                is SessionEvent.TurnFinished -> sessions.computeIfPresent(e.session) { _, r -> r.copy(lastHeartbeat = e.at, updatedAt = e.at) }
                is SessionEvent.Output, is SessionEvent.ToolStarted -> sessions.computeIfPresent(e.session) { _, r -> r.copy(lastHeartbeat = e.at, updatedAt = e.at) }
            }
            return SessionsState(sessions)
        }

        fun applyAll(state: SessionsState, events: List<SessionEvent>): SessionsState = events.fold(state, ::apply)
    }
}
