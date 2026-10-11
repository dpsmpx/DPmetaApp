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

    /** Загрузка окна подписки 0…1 и время сброса (D-42, D-45) — из `rate_limit_event`. */
    data class Usage(override val session: SessionId, val window: LimitWindow, val utilization: Double, val resetAt: Timestamp?) : AccountFact

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
 * | запуск не подтверждён: ответ потерян, target молчит (A-12, A-55) | `RECOVERY_PENDING` — решит сверка |
 * | прочие ошибки | `FAILED` |
 */
class SessionDriver(private val sessions: Sessions, private val runner: ClaudeRunner, private val clock: Clock) {
    /**
     * [unsupportedFlags] — флаги, которых не знает Claude Code target («unknown option» в stderr, D-40);
     * [turns] — завершённые ходы опроса целиком: файлы и итог хода нужны оркестратору для handoff (D-41).
     */
    data class Step(
        val events: List<SessionEvent>,
        val facts: List<AccountFact> = emptyList(),
        val unsupportedFlags: Set<String> = emptySet(),
        val turns: List<ClaudeTurn> = emptyList(),
        /** Что модель написала и о чём рассуждала — по порядку, без инструментов (лента чата D-59). */
        val chat: List<ChatPiece> = emptyList(),
        /** Фоновые команды хода (D-66): что запущено и может пережить ход. */
        val background: List<String> = emptyList(),
    )

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
                val warnings = started.diagnostics.map { Diagnostic.warning(it.code, it.message, it.subject) }
                if (code == DiagCode.RECOVERY_START_UNCONFIRMED) {
                    // Ход мог начаться (A-12, A-55): не FAILED и не повтор поверх него — сверка (A-42, A-44)
                    // подхватит идущий ход или отменит сессию без попытки; слот аккаунта пока за ней.
                    return Outcome.success(Step(starting + sessions.transition(s1, id, SessionState.RECOVERY_PENDING, now, code).valueOrNull().orEmpty()), warnings)
                }
                Outcome.success(Step(starting + sessions.transition(s1, id, SessionState.FAILED, now, code).valueOrNull().orEmpty(), listOf(AccountFact.Release(id))), warnings)
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
        val unsupported = linkedSetOf<String>()
        val turns = mutableListOf<ClaudeTurn>()
        val chat = mutableListOf<ChatPiece>()
        val background = mutableListOf<String>()
        for (u in updates.value) {
            val now = clock.now()
            when (u) {
                is RunnerUpdate.TurnStarted -> {
                    emit(listOf(SessionEvent.TurnStarted(id, u.turn, now)))
                    moveTo(SessionState.RUNNING, null)
                }
                is RunnerUpdate.Heartbeat -> emit(sessions.heartbeat(current, id, now))
                // Неизвестный флаг (старый Claude Code на target): CLI выходит до вызова API — модель не тратится.
                is RunnerUpdate.Stderr -> UNKNOWN_OPTION.findAll(u.text).forEach { unsupported += it.groupValues[1] }
                is RunnerUpdate.Provider -> when (val e = u.event) {
                    is ClaudeEvent.Assistant -> {
                        // Лента чата (D-59): рассуждения и текст самого хода — по порядку; субагенты и инструменты — нет.
                        if (!e.subagent) {
                            e.thinking.forEach { chat += ChatPiece(ChatPiece.Kind.THINKING, it, now) }
                            e.texts.filter { it.isNotBlank() }.forEach { chat += ChatPiece(ChatPiece.Kind.TEXT, it, now) }
                        }
                        e.toolUses.mapNotNullTo(background) { com.dpsmpx.dpai.execution.claude.BackgroundCommand.of(it) }
                        val text = e.texts.joinToString("\n").take(sessions.policy.maxOutputChars)
                        if (text.isNotBlank()) emit(listOf(SessionEvent.Output(id, text, now)))
                        e.toolUses.forEach { emit(listOf(SessionEvent.ToolStarted(id, it.name, now))) }
                        // Живой вид (D-42): последний текст и текущий инструмент с целью — в памяти, не в журнале.
                        if (!e.subagent) {
                            val prev = live[id]
                            val use = e.toolUses.lastOrNull()
                            live[id] = LiveTurn(
                                text = text.takeIf { it.isNotBlank() }?.let { com.dpsmpx.dpai.core.diagnostics.Redactor.STANDARD.redact(it.takeLast(LiveTurn.MAX_TEXT)) } ?: prev?.text,
                                tool = use?.name ?: prev?.tool,
                                target = if (use != null) com.dpsmpx.dpai.execution.claude.ToolTarget.of(use) else prev?.target,
                                at = now,
                            )
                        }
                    }
                    is ClaudeEvent.RateLimit -> {
                        val limits = limitFacts(id, e)
                        facts += limits
                        for ((name, window) in mapOf("five_hour" to LimitWindow.SESSION, "seven_day" to LimitWindow.WEEKLY)) {
                            val w = e.windows[name] ?: continue
                            w.utilization.orNull()?.let { u -> facts += AccountFact.Usage(id, window, u, w.resetsAt) }
                        }
                        val near = limits.any { it.level != LimitLevel.OK }
                        if (near && current.sessions.getValue(id).state == SessionState.RUNNING) moveTo(SessionState.NEAR_LIMIT, DiagCode.CLAUDE_RATE_LIMITED)
                    }
                    else -> emit(sessions.heartbeat(current, id, now))
                }
                // Супервизор на target умер, не записав выхода: что с ходом — решит сверка (A-43).
                is RunnerUpdate.RunLost -> moveTo(SessionState.RECOVERY_PENDING, DiagCode.AGENT_RUN_LOST)
                is RunnerUpdate.TurnFinished -> {
                    val t = u.result
                    turns += t
                    emit(
                        listOf(
                            SessionEvent.TurnUsage(id, u.turn, t.usage.recorded(), now),
                            SessionEvent.TurnTools(id, u.turn, t.tools, now),
                            SessionEvent.TurnFinished(id, u.turn, t.status.name, t.diagnostics.filter { it.isError }.map { it.code }.distinct(), now),
                        ),
                    )
                    val (to, reason, fact) = outcomeOf(id, t)
                    if (unsupported.isNotEmpty() && t.status != TurnStatus.COMPLETED) {
                        // Отказ из-за флага — не вина аккаунта: без факта ошибки слота, повтор — без флага.
                        moveTo(SessionState.FAILED, DiagCode.CLAUDE_FLAG_UNSUPPORTED)
                    } else {
                        fact?.let { facts += it }
                        moveTo(to, reason)
                    }
                }
            }
        }
        // Позиция в потоке агента — в журнал: после перезапуска сессия продолжается с неё.
        runner.position(id)?.let { pos -> emit(sessions.heartbeat(current, id, clock.now(), pos)) }
        if (current.sessions.getValue(id).terminal && state.sessions[id]?.terminal == false) facts += AccountFact.Release(id)
        return Outcome.success(Step(events, facts, unsupported, turns, chat, background), updates.diagnostics)
    }

    /** Что ход делает сейчас (D-42): только в памяти процесса — после перезапуска пусто, пока не придёт новое. */
    private val live = java.util.concurrent.ConcurrentHashMap<SessionId, LiveTurn>()

    fun live(id: SessionId): LiveTurn? = live[id]

    /** Сессия завершена — её живой вид больше не нужен (память не растёт с числом сессий, ТЗ, §116). */
    fun forget(id: SessionId) {
        live.remove(id)
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
            // Ход упёрся в предел — это и есть факт предела, даже без `rate_limit_event` с отказом (A-49):
            // иначе слот свободен, и задача тут же перезапускается в тот же предел.
            DiagCode.CLAUDE_RATE_LIMITED in codes -> Triple(SessionState.PAUSED, DiagCode.CLAUDE_RATE_LIMITED, reachedLimit(id, t))
            t.status == TurnStatus.INCOMPLETE -> Triple(SessionState.PAUSED, codes.firstOrNull(), null)
            else -> Triple(SessionState.FAILED, codes.firstOrNull() ?: DiagCode.SESSION_FAILED, codes.firstOrNull()?.let { AccountFact.Error(id, it) })
        }
    }

    /** Предел, в который упёрся ход: окно — из последнего `rate_limit_event`, неизвестно — окно пяти часов; сброс — если известен. */
    private fun reachedLimit(id: SessionId, t: ClaudeTurn): AccountFact.Limit {
        val window = if (t.rateLimit?.type == "seven_day") LimitWindow.WEEKLY else LimitWindow.SESSION
        val name = if (window == LimitWindow.WEEKLY) "seven_day" else "five_hour"
        return AccountFact.Limit(id, window, LimitLevel.REACHED, t.rateLimit?.windows?.get(name)?.resetsAt ?: t.rateLimit?.resetsAt)
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

    private companion object {
        /** Отказ commander.js в Claude Code: «error: unknown option '--flag'». */
        val UNKNOWN_OPTION = Regex("unknown option '(--[A-Za-z0-9-]+)'")
    }
}
