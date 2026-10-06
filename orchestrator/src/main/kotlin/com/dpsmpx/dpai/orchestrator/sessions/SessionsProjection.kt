package com.dpsmpx.dpai.orchestrator.sessions

import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.sessions.SessionCodec
import com.dpsmpx.dpai.sessions.SessionEvent
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Сессии как проекция журнала (D-12, D-24): события — конвертами
 * `dpai.event` в потоке сессии, состояние — из журнала и снимка
 * `dpai.sessions-state`. История сессии — её поток событий.
 */
object SessionsProjection : Projection<SessionsState> {
    override val name: String = "sessions"

    override fun initial(): SessionsState = SessionsState.EMPTY

    override fun apply(state: SessionsState, event: EventEnvelope): SessionsState {
        val decoded = SessionCodec.decode(event.type, event.occurredAt, event.payload) ?: return state
        // Журнал сверен хешами (D-24): негодное событие сессии — несовместимость версий, а не порча.
        val e = checkNotNull(decoded.valueOrNull()) { "событие сессии №${event.sequence} не читается: ${decoded.diagnostics}" }
        return Sessions.apply(state, e)
    }

    override fun encode(state: SessionsState): JsonObject = SessionCodec.encodeState(state)

    override fun decode(json: JsonObject): Outcome<SessionsState> = SessionCodec.decodeState(json)
}

/** Конверты для событий сессий: тип, поток и полезная нагрузка — из [SessionCodec]. */
fun EventRecorder.sessionEnvelopes(events: List<SessionEvent>): List<EventEnvelope> =
    events.map { envelope(SessionCodec.type(it), SessionCodec.stream(it), it.at, SessionCodec.payload(it)) }
