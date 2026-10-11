package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.diagnostics.Category
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.Retryability
import com.dpsmpx.dpai.core.diagnostics.Severity
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunEventSource
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.agent.RunStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Что произошло в сессии с прошлого опроса ([ClaudeRunner.stream]). */
sealed interface RunnerUpdate {
    data class TurnStarted(val turn: Int) : RunnerUpdate

    /** Событие провайдера; [sequence] — номер события агента, из которого оно разобрано. */
    data class Provider(val event: ClaudeEvent, val sequence: Long) : RunnerUpdate

    /** Строка stderr процесса Claude — для журнала, не для состояния. */
    data class Stderr(val text: String) : RunnerUpdate

    data class Heartbeat(val sequence: Long) : RunnerUpdate

    data class TurnFinished(val turn: Int, val result: ClaudeTurn) : RunnerUpdate

    /**
     * Ход потерян на target: супервизор умер, не записав выхода (`LOST`,
     * ревью A-7, A-43). Что с процессом — решает сверка, а не догадка (D-13).
     */
    data class RunLost(val turn: Int) : RunnerUpdate
}

/** Состояние сессии раннера без изменений (`inspect`). */
data class RunnerState(val session: SessionId, val provider: ProviderSessionId, val turn: Int, val run: RunStatus?, val lastTurn: ClaudeTurn?)

/**
 * Единый интерфейс сессии Claude (ТЗ, §12; `docs/EXECUTION_MODEL.md`, §3).
 * Ход — один процесс `claude -p` под агентом target; продолжение —
 * следующий ход с `--resume` того же `providerSessionId` (D-32).
 */
interface ClaudeRunner {
    /**
     * Первый ход сессии оркестратора. [provider] — id сессии провайдера,
     * который оркестратор уже записал в журнал (`null` — раннер назначит
     * сам); [resume] — эта сессия провайдера уже есть и продолжается
     * (`--resume`): так продолжение — новая сессия оркестратора над той же
     * сессией Claude (`docs/SESSION_MODEL.md`, §2).
     */
    fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId? = null, resume: Boolean = false): Outcome<RunnerState>

    /** Следующее сообщение в сессию — новый ход; пока идёт прежний — `RUNNER_TURN_ACTIVE`. */
    fun send(session: SessionId, request: ClaudeRequest): Outcome<RunnerState>

    /** Новое с прошлого опроса: события провайдера, начала и итоги ходов. */
    fun stream(session: SessionId): Outcome<List<RunnerUpdate>>

    /** Приостановить: остановить текущий ход; сессия провайдера сохраняется для `resume`. */
    fun pause(session: SessionId): Outcome<RunnerState>

    /** Отменить текущий ход. */
    fun cancel(session: SessionId): Outcome<RunnerState>

    /** Продолжить приостановленную или прерванную сессию — ход с `--resume`. */
    fun resume(session: SessionId, request: ClaudeRequest): Outcome<RunnerState>

    fun inspect(session: SessionId): Outcome<RunnerState>

    /** Полная остановка с уборкой процессов; сессия раннером больше не ведётся. */
    fun terminate(session: SessionId): Outcome<RunnerState>

    /** Номер последнего принятого события агента — его оркестратор пишет в журнал, чтобы продолжить после перезапуска. */
    fun position(session: SessionId): Long?

    /**
     * Подхватить сессию после перезапуска оркестратора (`docs/RECOVERY.md`,
     * §1): события агента после [afterSequence]; [turnRunning] — ход ещё
     * идёт, его итог будет собран из оставшихся событий.
     */
    fun adopt(session: SessionId, provider: ProviderSessionId, afterSequence: Long, turnRunning: Boolean): Outcome<RunnerState>
}

/**
 * Claude Code CLI на target через агента (D-26, D-31, D-32). Окружение
 * слота приходит в каждом запросе хода ([ClaudeRequest.setEnv]): у каждой
 * сессии свой слот.
 */
class ClaudeCodeCliRunner(
    private val agent: AgentClient,
    private val newProviderSession: () -> ProviderSessionId,
    private val claudeCommand: List<String> = listOf("claude"),
    private val stopGrace: Duration = 5.seconds,
    private val eventSource: RunEventSource = agent,
) : ClaudeRunner {
    private class Live(val session: SessionId, val provider: ProviderSessionId, val channel: RunChannel, var providerStarted: Boolean) {
        var turn = 0
        var assembler: ClaudeTurnAssembler? = null
        var lastTurn: ClaudeTurn? = null
        var run: RunStatus? = null
        var terminated = false
        /** Потеря хода уже передана: [RunnerUpdate.RunLost] — один раз на ход. */
        var lostReported = false
    }

    private val live = HashMap<SessionId, Live>()

    @Synchronized
    override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
        if (session in live) return Outcome.failure(DiagCode.VALUE_INVALID, "сессия уже начата: продолжение — send или resume", "session $session")
        if (resume && provider == null) return Outcome.failure(DiagCode.VALUE_INVALID, "возобновить можно только известную сессию провайдера", "session $session")
        val l = Live(session, provider ?: newProviderSession(), RunChannel(eventSource, session), providerStarted = resume)
        live[session] = l
        return turn(l, request)
    }

    @Synchronized
    override fun send(session: SessionId, request: ClaudeRequest): Outcome<RunnerState> = withLive(session) { turn(it, request) }

    @Synchronized
    override fun resume(session: SessionId, request: ClaudeRequest): Outcome<RunnerState> = withLive(session) { turn(it, request) }

    /** Ход: первый для сессии провайдера — `--session-id`, следующие — `--resume`. */
    private fun turn(l: Live, request: ClaudeRequest): Outcome<RunnerState> {
        if (l.terminated) return Outcome.failure(DiagCode.INVALID_TRANSITION, "сессия остановлена полностью", "session ${l.session}")
        if (l.assembler != null || l.run?.finished == false) return Outcome.failure(DiagCode.RUNNER_TURN_ACTIVE, "ход ${l.turn} ещё идёт", "session ${l.session}")
        val spec = RunSpec(
            command = ClaudeCodeCli.argv(claudeCommand, request, l.provider, resume = l.providerStarted),
            workDir = request.workDir,
            setEnv = request.setEnv,
            unsetEnv = request.unsetEnv,
            timeout = request.timeout,
            stdin = request.prompt,
        )
        return when (val r = agent.start(l.session, spec)) {
            is Outcome.Failure -> if (l.run == null) unconfirmedStart(l, r) else r
            is Outcome.Success -> started(l, r.value, r.diagnostics)
        }
    }

    private fun started(l: Live, run: RunStatus, diagnostics: List<Diagnostic>): Outcome<RunnerState> {
        l.providerStarted = true
        l.run = run
        l.lostReported = false
        return Outcome.success(state(l), diagnostics)
    }

    /**
     * Первый `start` запуска не удался (ревью A-12, A-55): запуск мог дойти до
     * агента, а ответ — потеряться (обрыв SSH, тайм-аут, ответа нет). FAILED и
     * повтор при идущем на target ходе — второй Claude в том же дереве. Поэтому
     * `status`: агент знает запуск — ход идёт, дальше как после ответа; не
     * знает — хода не было, отказ как есть. Агент не отвечает: сетевой отказ
     * запуска — подключения не было (сон target, A-50), отказ как есть; иначе
     * исход неизвестен — [DiagCode.RECOVERY_START_UNCONFIRMED], раннер сессию
     * забывает, и её решит сверка ([adopt], A-42). Явный отказ агента
     * (`NON_RETRYABLE`, кроме нечитаемого ответа) — без `status`.
     */
    private fun unconfirmedStart(l: Live, failed: Outcome.Failure): Outcome<RunnerState> {
        if (failed.errors.any { it.retryability == Retryability.NON_RETRYABLE && it.code != DiagCode.AGENT_REPLY_INVALID }) return failed
        return when (val s = agent.status(l.session)) {
            is Outcome.Success -> started(l, s.value, failed.diagnostics.map { Diagnostic.warning(it.code, it.message, it.subject) })
            is Outcome.Failure -> when {
                s.errors.any { it.code == DiagCode.ENTITY_NOT_FOUND } -> failed
                failed.errors.all { it.category == Category.NETWORK } -> failed
                else -> {
                    live.remove(l.session)
                    val note = "ответ на запуск потерян, а target не отвечает: ход мог начаться"
                    Outcome.Failure(listOf(Diagnostic.of(DiagCode.RECOVERY_START_UNCONFIRMED, note, "session ${l.session}", Severity.ERROR, failed.errors + s.errors)))
                }
            }
        }
    }

    @Synchronized
    override fun stream(session: SessionId): Outcome<List<RunnerUpdate>> = withLive(session) { l ->
        when (val batch = l.channel.poll()) {
            is Outcome.Failure -> batch
            is Outcome.Success -> {
                l.run = batch.value.status ?: l.run
                val updates = mutableListOf<RunnerUpdate>()
                for (e in batch.value.events) updates += translate(l, e)
                if (l.run?.state == RunState.LOST && !l.lostReported) {
                    l.lostReported = true
                    updates += RunnerUpdate.RunLost(l.turn)
                }
                Outcome.success(updates, batch.diagnostics)
            }
        }
    }

    private fun translate(l: Live, e: EventEnvelope): List<RunnerUpdate> = when (e.type) {
        EventType.TARGET_PROCESS_STARTED -> {
            l.turn = (e.payload["turn"] as? JsonNumber)?.toLongExact()?.toInt() ?: (l.turn + 1)
            l.assembler = ClaudeTurnAssembler(l.provider)
            listOf(RunnerUpdate.TurnStarted(l.turn))
        }
        EventType.TARGET_PROCESS_OUTPUT -> {
            val text = (e.payload["text"] as? JsonString)?.value.orEmpty()
            if ((e.payload["stream"] as? JsonString)?.value == "stderr") {
                listOf(RunnerUpdate.Stderr(text))
            } else {
                val truncated = (e.payload["truncated"] as? JsonBool)?.value ?: false
                listOfNotNull(l.assembler?.feed(text, truncated)?.let { RunnerUpdate.Provider(it, e.sequence) })
            }
        }
        EventType.TARGET_PROCESS_HEARTBEAT -> listOf(RunnerUpdate.Heartbeat(e.sequence))
        EventType.TARGET_PROCESS_EXITED -> {
            val code = (e.payload["exitCode"] as? JsonNumber)?.toLongExact()?.toInt()
            val reason = (e.payload["reason"] as? JsonString)?.value?.let { r -> ExitReason.entries.firstOrNull { it.name == r } }
            val turn = (l.assembler ?: ClaudeTurnAssembler(l.provider)).finish(code, reason)
            l.assembler = null
            l.lastTurn = turn
            listOf(RunnerUpdate.TurnFinished(l.turn, turn))
        }
        else -> emptyList()
    }

    @Synchronized
    override fun pause(session: SessionId): Outcome<RunnerState> = withLive(session) { stopTurn(it) }

    @Synchronized
    override fun cancel(session: SessionId): Outcome<RunnerState> = withLive(session) { stopTurn(it) }

    @Synchronized
    override fun terminate(session: SessionId): Outcome<RunnerState> = withLive(session) { l ->
        val r = stopTurn(l)
        l.terminated = true
        r
    }

    private fun stopTurn(l: Live): Outcome<RunnerState> {
        if (l.run == null || l.run?.state == RunState.EXITED) return Outcome.success(state(l))
        return when (val r = agent.stop(l.session, stopGrace)) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                l.run = r.value.run ?: l.run
                val survivors = r.value.stop?.survivors.orEmpty()
                if (survivors.isNotEmpty()) {
                    Outcome.failure(Diagnostic.error(DiagCode.PROCESS_ORPHANS_LEFT, "после остановки живы ${survivors.joinToString()}", "session ${l.session}"))
                } else {
                    Outcome.success(state(l))
                }
            }
        }
    }

    @Synchronized
    override fun position(session: SessionId): Long? = live[session]?.channel?.lastAccepted

    @Synchronized
    override fun adopt(session: SessionId, provider: ProviderSessionId, afterSequence: Long, turnRunning: Boolean): Outcome<RunnerState> {
        if (session in live) return Outcome.failure(DiagCode.VALUE_INVALID, "сессия уже ведётся раннером", "session $session")
        val l = Live(session, provider, RunChannel(eventSource, session, startAfter = afterSequence), providerStarted = true)
        if (turnRunning) replayTurn(l, afterSequence)
        live[session] = l
        return Outcome.success(state(l))
    }

    /**
     * Подхват идущего хода (ревью A-14): сборка хода — с его начала, а не с
     * записанной позиции: события до неё (инструменты D-41, расход) иначе в
     * итог хода не попадали. Обновлений наружу нет — они уже были до сбоя.
     * Агент не ответил — сборка с позиции, как раньше.
     */
    private fun replayTurn(l: Live, upTo: Long) {
        l.assembler = ClaudeTurnAssembler(l.provider)
        var after = 0L
        while (after < upTo) {
            val page = agent.events(l.session, after, REPLAY_PAGE).valueOrNull() ?: return
            for (e in page.events.takeWhile { it.sequence <= upTo }) {
                if (e.type == EventType.TARGET_PROCESS_STARTED || e.type == EventType.TARGET_PROCESS_OUTPUT) translate(l, e)
            }
            if (!page.more || page.events.isEmpty()) return
            after = page.events.last().sequence
        }
    }

    @Synchronized
    override fun inspect(session: SessionId): Outcome<RunnerState> = withLive(session) { l ->
        when (val s = agent.status(session)) {
            is Outcome.Failure -> Outcome.success(state(l), s.diagnostics.map { Diagnostic.warning(it.code, it.message, it.subject) })
            is Outcome.Success -> Outcome.success(state(l).copy(run = s.value))
        }
    }

    private fun state(l: Live) = RunnerState(l.session, l.provider, l.turn, l.run, l.lastTurn)

    private inline fun <T> withLive(session: SessionId, f: (Live) -> Outcome<T>): Outcome<T> {
        val l = live[session] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "сессия не ведётся раннером", "session $session")
        return f(l)
    }

    private companion object {
        const val REPLAY_PAGE = 1000
    }
}
