package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.LimitLevel
import com.dpsmpx.dpai.accounts.LimitWindow
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionLifecycle
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.execution.claude.ClaudeEvent
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.RunnerUpdate
import com.dpsmpx.dpai.execution.claude.TurnStatus

/**
 * Факт для аккаунтов, замеченный в сессии (D-30): оркестратор передаёт
 * его в `Accounts.observe…` слота сессии.
 */
sealed interface AccountFact {
    val session: SessionId

    data class Limit(override val session: SessionId, val window: LimitWindow, val level: LimitLevel, val resetAt: Timestamp?) : AccountFact

    data class AuthFailure(override val session: SessionId) : AccountFact

    data class Success(override val session: SessionId) : AccountFact

    data class Error(override val session: SessionId, val code: DiagCode) : AccountFact

    /** Сессия в конечном состоянии — аренду слота вернуть. */
    data class Release(override val session: SessionId) : AccountFact
}

/**
 * Сессия поверх [ClaudeRunner] (`docs/SESSION_MODEL.md`; ТЗ, §8, §12).
 * Каждый шаг возвращает события сессии и факты для аккаунтов; состояние
 * хранит и применяет вызывающий (D-12). Ход начинается только после
 * сверки слота ([Sessions.bindingFor]).
 *
 * Итог хода → состояние сессии:
 *
 * | Итог | Состояние |
 * |---|---|
 * | `COMPLETED` | `COMPLETED` — исполнитель закончил ход (D-23: не «задача выполнена») |
 * | `INCOMPLETE`, предел подписки | `PAUSED` — продолжение `resume` |
 * | ошибка входа | `BLOCKED` — нужен вход пользователя |
 * | прочие ошибки | `FAILED` |
 */
class SessionDriver(private val sessions: Sessions, private val runner: ClaudeRunner, private val clock: Clock) {
    data class Step(val events: List<SessionEvent>, val facts: List<AccountFact> = emptyList())

    /** Первый ход: сверка слота, `QUEUED → STARTING`, запуск. Продолжение прежней — `--resume` её сессии провайдера. */
    fun launch(state: SessionsState, accounts: AccountsState, id: SessionId, request: ClaudeRequest): Outcome<Step> {
        val r = state.sessions[id] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session $id")
        val binding = when (val b = sessions.bindingFor(state, accounts, id)) {
            is Outcome.Failure -> return b
            is Outcome.Success -> b.value
        }
        val now = clock.now()
        val starting = when (val t = sessions.transition(state, id, SessionState.STARTING, now)) {
            is Outcome.Failure -> return t
            is Outcome.Success -> t.value
        }
        // id сессии провайдера — тот, что в журнале (D-08): по нему сверка после перезапуска узнаёт поток.
        val resume = r.continues?.let { prev -> state.sessions[prev]?.provider == r.provider } ?: false
        val req = request.copy(setEnv = request.setEnv + binding.setEnv, unsetEnv = (request.unsetEnv + binding.unsetEnv).distinct())
        val started = runner.start(id, req, r.provider, resume)
        return when (started) {
            is Outcome.Success -> Outcome.success(Step(starting), started.diagnostics)
            is Outcome.Failure -> {
                val s1 = Sessions.applyAll(state, starting)
                val code = started.errors.first().code
                Outcome.success(Step(starting + sessions.transition(s1, id, SessionState.FAILED, now, code).valueOrNull().orEmpty(), listOf(AccountFact.Release(id))), started.diagnostics.map { Diagnostic.warning(it.code, it.message, it.subject) })
            }
        }
    }

    /** Следующий ход приостановленной или ждущей сессии: сверка слота и `--resume`. */
    fun resume(state: SessionsState, accounts: AccountsState, id: SessionId, request: ClaudeRequest): Outcome<Step> {
        val binding = when (val b = sessions.bindingFor(state, accounts, id)) {
            is Outcome.Failure -> return b
            is Outcome.Success -> b.value
        }
        val req = request.copy(setEnv = request.setEnv + binding.setEnv, unsetEnv = (request.unsetEnv + binding.unsetEnv).distinct())
        return runner.resume(id, req).let { r -> if (r is Outcome.Failure) r else Outcome.success(Step(emptyList()), r.diagnostics) }
    }

    /** Новое с прошлого опроса — в события сессии и факты аккаунтов. */
    fun poll(state: SessionsState, id: SessionId): Outcome<Step> {
        val updates = when (val u = runner.stream(id)) {
            is Outcome.Failure -> return u
            is Outcome.Success -> u
        }
        var current = state
        val events = mutableListOf<SessionEvent>()
        val facts = mutableListOf<AccountFact>()
        fun emit(es: List<SessionEvent>) {
            events += es
            current = Sessions.applyAll(current, es)
        }
        fun moveTo(to: SessionState, reason: DiagCode?) {
            val from = current.sessions.getValue(id).state
            val target = when {
                SessionLifecycle.table.isAllowed(from, to) -> to
                to != SessionState.FAILED && SessionLifecycle.table.isAllowed(from, SessionState.PAUSED) -> SessionState.PAUSED
                else -> SessionState.FAILED
            }
            emit(sessions.transition(current, id, target, clock.now(), reason).valueOrNull().orEmpty())
        }
        for (u in updates.value) {
            val now = clock.now()
            when (u) {
                is RunnerUpdate.TurnStarted -> {
                    emit(listOf(SessionEvent.TurnStarted(id, u.turn, now)))
                    moveTo(SessionState.RUNNING, null)
                }
                is RunnerUpdate.Heartbeat -> emit(sessions.heartbeat(current, id, now))
                is RunnerUpdate.Stderr -> Unit
                is RunnerUpdate.Provider -> when (val e = u.event) {
                    is ClaudeEvent.Assistant -> {
                        val text = e.texts.joinToString("\n").take(sessions.policy.maxOutputChars)
                        if (text.isNotBlank()) emit(listOf(SessionEvent.Output(id, text, now)))
                        e.toolUses.forEach { emit(listOf(SessionEvent.ToolStarted(id, it.name, now))) }
                    }
                    is ClaudeEvent.RateLimit -> {
                        val limits = limitFacts(id, e)
                        facts += limits
                        val near = limits.any { it.level != LimitLevel.OK }
                        if (near && current.sessions.getValue(id).state == SessionState.RUNNING) moveTo(SessionState.NEAR_LIMIT, DiagCode.CLAUDE_RATE_LIMITED)
                    }
                    else -> emit(sessions.heartbeat(current, id, now))
                }
                is RunnerUpdate.TurnFinished -> {
                    val t = u.result
                    emit(listOf(SessionEvent.TurnFinished(id, u.turn, t.status.name, t.diagnostics.filter { it.isError }.map { it.code }.distinct(), now)))
                    val (to, reason, fact) = outcomeOf(id, t)
                    fact?.let { facts += it }
                    moveTo(to, reason)
                }
            }
        }
        // Позиция в потоке агента — в журнал: после перезапуска сессия продолжается с неё.
        runner.position(id)?.let { pos -> emit(sessions.heartbeat(current, id, clock.now(), pos)) }
        if (current.sessions.getValue(id).terminal && state.sessions[id]?.terminal == false) facts += AccountFact.Release(id)
        return Outcome.success(Step(events, facts), updates.diagnostics)
    }

    /** Приостановить: остановить ход; сессия провайдера остаётся для `resume`. */
    fun pause(state: SessionsState, id: SessionId): Outcome<Step> =
        runner.pause(id).let { r ->
            if (r is Outcome.Failure) r else sessions.transition(state, id, SessionState.PAUSED, clock.now()).let { t -> if (t is Outcome.Failure) t else Outcome.success(Step(t.valueOrNull()!!)) }
        }

    /** Отменить: остановка с уборкой процессов, `CANCELLED`, аренда возвращается. */
    fun cancel(state: SessionsState, id: SessionId): Outcome<Step> =
        runner.terminate(id).let { r ->
            if (r is Outcome.Failure) {
                r
            } else {
                sessions.transition(state, id, SessionState.CANCELLED, clock.now()).let { t ->
                    if (t is Outcome.Failure) t else Outcome.success(Step(t.valueOrNull()!!, listOf(AccountFact.Release(id))))
                }
            }
        }

    private fun outcomeOf(id: SessionId, t: ClaudeTurn): Triple<SessionState, DiagCode?, AccountFact?> {
        val codes = t.diagnostics.filter { it.isError }.map { it.code }
        return when {
            t.status == TurnStatus.COMPLETED -> Triple(SessionState.COMPLETED, null, AccountFact.Success(id))
            DiagCode.CLAUDE_AUTH_REQUIRED in codes -> Triple(SessionState.BLOCKED, DiagCode.CLAUDE_AUTH_REQUIRED, AccountFact.AuthFailure(id))
            DiagCode.CLAUDE_RATE_LIMITED in codes -> Triple(SessionState.PAUSED, DiagCode.CLAUDE_RATE_LIMITED, null)
            t.status == TurnStatus.INCOMPLETE -> Triple(SessionState.PAUSED, codes.firstOrNull(), null)
            else -> Triple(SessionState.FAILED, codes.firstOrNull() ?: DiagCode.SESSION_FAILED, codes.firstOrNull()?.let { AccountFact.Error(id, it) })
        }
    }

    /** Окна `rate_limit_event` → факты пределов подписки (`docs/research/CLAUDE_CODE_CLI.md`, §8). */
    private fun limitFacts(id: SessionId, e: ClaudeEvent.RateLimit): List<AccountFact.Limit> {
        val names = mapOf("five_hour" to LimitWindow.SESSION, "seven_day" to LimitWindow.WEEKLY)
        val out = mutableListOf<AccountFact.Limit>()
        for ((name, window) in names) {
            val w = e.windows[name]
            val here = e.type == null || e.type == name
            val rejectedHere = e.rejected && here
            val level = when {
                rejectedHere -> LimitLevel.REACHED
                e.warning && here -> LimitLevel.NEAR
                (w?.utilization?.orNull() ?: 0.0) >= sessions.policy.nearLimitUtilization -> LimitLevel.NEAR
                else -> null
            } ?: continue
            out += AccountFact.Limit(id, window, level, w?.resetsAt ?: e.resetsAt.takeIf { rejectedHere })
        }
        return out
    }
}
