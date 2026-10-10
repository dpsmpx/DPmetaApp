package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.SubscriptionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.HeartbeatThresholds
import com.dpsmpx.dpai.core.state.SessionLifecycle
import com.dpsmpx.dpai.core.state.SessionState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Сессия (ТЗ, §7, §29; `docs/SESSION_MODEL.md`): связка задача + слот +
 * target + сессия провайдера. [key] — ключ идемпотентности создания:
 * повтор того же запроса возвращает ту же сессию (ТЗ, §5.4). [configDir] —
 * `credentialRef` слота на момент создания: перед каждым ходом он сверяется
 * со слотом (ТЗ, §66).
 */
data class SessionRecord(
    val id: SessionId,
    val key: String,
    val task: TaskId,
    val slot: AccountId,
    val subscription: SubscriptionId,
    val target: ExecutionTargetId,
    val configDir: String,
    val provider: ProviderSessionId,
    val continues: SessionId?,
    val state: SessionState = SessionLifecycle.table.initial,
    val createdAt: Timestamp,
    val updatedAt: Timestamp,
    val lastHeartbeat: Timestamp? = null,
    val turn: Int = 0,
    val lastReason: DiagCode? = null,
    /** Номер последнего применённого события агента: с него сессия продолжается после перезапуска. */
    val agentSequence: Long = 0,
    /** Расход сессии: prompt (оценка) и ходы (со слов провайдера), D-40. */
    val usage: UsageTotals = UsageTotals.EMPTY,
) {
    val terminal: Boolean get() = state in SessionLifecycle.table.terminal
}

/** Все сессии: производное от событий (D-12). */
data class SessionsState(val sessions: Map<SessionId, SessionRecord> = emptyMap()) {
    fun byKey(key: String): SessionRecord? = sessions.values.firstOrNull { it.key == key }

    /** Живое продолжение сессии [prev], если оно есть. */
    fun continuationOf(prev: SessionId): SessionRecord? = sessions.values.firstOrNull { it.continues == prev && !it.terminal }

    companion object {
        val EMPTY = SessionsState()
    }
}

/**
 * Пороги свежести heartbeat (ТЗ, §15) и порог загрузки окна подписки, при
 * котором сессия переходит в `NEAR_LIMIT` (время handoff, ТЗ, §27).
 */
data class SessionPolicy(
    val heartbeat: HeartbeatThresholds = HeartbeatThresholds(aging = 90.seconds, stale = 5.minutes, unknown = 15.minutes),
    val nearLimitUtilization: Double = 0.9,
    /** Длина текста одного события `SESSION_OUTPUT`: длиннее — обрезается (размер журнала, ТЗ, §116). */
    val maxOutputChars: Int = 4000,
) {
    init {
        require(nearLimitUtilization > 0 && nearLimitUtilization <= 1) { "порог загрузки — (0, 1]" }
        require(maxOutputChars in 100..1_000_000) { "maxOutputChars — 100…1000000" }
    }
}

/** Факт о сессии (D-12): из них [Sessions.apply] собирает состояние. */
sealed interface SessionEvent {
    val session: SessionId
    val at: Timestamp

    data class Created(
        override val session: SessionId,
        val key: String,
        val task: TaskId,
        val slot: AccountId,
        val subscription: SubscriptionId,
        val target: ExecutionTargetId,
        val configDir: String,
        val provider: ProviderSessionId,
        val continues: SessionId?,
        override val at: Timestamp,
    ) : SessionEvent

    data class StateChanged(
        override val session: SessionId,
        val from: SessionState,
        val to: SessionState,
        val reason: DiagCode?,
        override val at: Timestamp,
    ) : SessionEvent

    data class Heartbeat(override val session: SessionId, override val at: Timestamp, val agentSequence: Long? = null) : SessionEvent

    data class TurnStarted(override val session: SessionId, val turn: Int, override val at: Timestamp) : SessionEvent

    /** Итог хода: статус и коды причин — без текста модели (заявление, D-23). */
    data class TurnFinished(
        override val session: SessionId,
        val turn: Int,
        val status: String,
        val codes: List<DiagCode>,
        override val at: Timestamp,
    ) : SessionEvent

    /** Текст модели — для истории сессии; обрезан по [SessionPolicy.maxOutputChars]. */
    data class Output(override val session: SessionId, val text: String, override val at: Timestamp) : SessionEvent

    data class ToolStarted(override val session: SessionId, val tool: String, override val at: Timestamp) : SessionEvent

    /** Запрос хода собран: размер (оценка), режим, маршрут модели (D-40). */
    data class PromptPrepared(override val session: SessionId, val facts: PromptFacts, override val at: Timestamp) : SessionEvent

    /** Расход хода со слов провайдера (D-40); пишется перед [TurnFinished] того же хода. */
    data class TurnUsage(
        override val session: SessionId,
        val turn: Int,
        val usage: com.dpsmpx.dpai.execution.claude.TurnUsage,
        override val at: Timestamp,
    ) : SessionEvent

    /** Инструменты хода — замер оркестратора по потоку (D-41); пишется перед [TurnFinished] того же хода. */
    data class TurnTools(
        override val session: SessionId,
        val turn: Int,
        val tools: com.dpsmpx.dpai.execution.claude.TurnTools,
        override val at: Timestamp,
    ) : SessionEvent
}

/**
 * Живой вид хода (D-42): последний текст модели, последний инструмент и
 * его цель (файл, команда, шаблон) и когда это было. Только в памяти.
 */
data class LiveTurn(val text: String?, val tool: String?, val target: String?, val at: com.dpsmpx.dpai.core.clock.Timestamp) {
    companion object {
        const val MAX_TEXT = 600
    }
}
