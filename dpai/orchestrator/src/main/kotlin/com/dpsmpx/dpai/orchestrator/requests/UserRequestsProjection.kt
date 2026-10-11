package com.dpsmpx.dpai.orchestrator.requests

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.notifications.RequestEvent
import com.dpsmpx.dpai.notifications.UserRequestCodec
import com.dpsmpx.dpai.notifications.UserRequests
import com.dpsmpx.dpai.notifications.UserRequestsState
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.snapshot.Projection

/**
 * Запросы человеку в журнале (D-44): события `USER_REQUEST_*` в потоке
 * запроса и снимок `dpai.user-requests-state` v1. После перезапуска
 * ждущий запрос снова ждёт с прежним сроком и хешем — кнопка уже
 * показанного уведомления действует; отвеченный помнит ответ, и повтор
 * нажатия второго действия не делает.
 */
object UserRequestsProjection : Projection<UserRequestsState> {
    const val FORMAT = "dpai.user-requests-state"
    const val VERSION = 1
    private const val PREFIX = "USER_REQUEST_"

    override val name: String = "user-requests"

    override fun initial(): UserRequestsState = UserRequestsState.EMPTY

    override fun apply(state: UserRequestsState, event: EventEnvelope): UserRequestsState {
        val decoded = decode(event.type, event.occurredAt, event.payload) ?: return state
        val e = checkNotNull(decoded.valueOrNull()) { "событие запроса №${event.sequence} не читается: ${decoded.diagnostics}" }
        return UserRequests.apply(state, e)
    }

    fun decode(type: EventType, at: Timestamp, payload: JsonObject): Outcome<RequestEvent>? {
        if (!type.name.startsWith(PREFIX)) return null
        return UserRequestCodec.decode(type.name.removePrefix(PREFIX), at, payload)
    }

    fun type(e: RequestEvent): EventType = when (e) {
        is RequestEvent.Created -> EventType.USER_REQUEST_CREATED
        is RequestEvent.Delivered -> EventType.USER_REQUEST_DELIVERED
        is RequestEvent.Answered -> EventType.USER_REQUEST_ANSWERED
        is RequestEvent.Expired -> EventType.USER_REQUEST_EXPIRED
        is RequestEvent.Cancelled -> EventType.USER_REQUEST_CANCELLED
        is RequestEvent.Failed -> EventType.USER_REQUEST_FAILED
    }

    override fun encode(state: UserRequestsState): JsonObject = JsonObject(
        linkedMapOf(*FormatHeader.write(FORMAT, VERSION), "requests" to UserRequestCodec.stateJson(state)),
    )

    override fun decode(json: JsonObject): Outcome<UserRequestsState> {
        val d = DiagnosticCollector()
        val f = JsonFields(json, "$", d)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("requests")?.items.orEmpty().mapIndexed { i, v ->
            (v as? JsonObject)?.let { o -> JsonFields(o, "$.requests[$i]", d).let { r -> UserRequestCodec.readRequest(r, d).also { r.finish() } } }
        }
        f.finish()
        if (items.any { it == null }) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.FIELD_MISSING, "запрос в снимке неполон", "$.requests")
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(UserRequestsState(items.filterNotNull().associateBy { it.id }))
    }
}

/** Конверты событий запросов: поток — сам запрос. */
fun EventRecorder.requestEnvelopes(events: List<RequestEvent>): List<EventEnvelope> =
    events.map { envelope(UserRequestsProjection.type(it), StreamRef.of(it.request), it.at, UserRequestCodec.payload(it)) }
