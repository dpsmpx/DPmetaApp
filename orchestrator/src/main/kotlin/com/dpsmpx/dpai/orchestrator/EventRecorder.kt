package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.storage.journal.JournalRecord

/**
 * Конверты событий с номерами без пропусков в каждом потоке (D-12):
 * следующий номер потока известен по журналу ([resumeFrom]).
 */
class EventRecorder(private val ids: IdGenerator, private val clock: Clock) {
    private val next = HashMap<StreamRef, Long>()

    fun resumeFrom(records: List<JournalRecord>) {
        for (r in records) next[r.event.stream] = maxOf(next[r.event.stream] ?: 1, r.event.sequence + 1)
    }

    @Synchronized
    fun envelope(
        type: EventType,
        stream: StreamRef,
        occurredAt: Timestamp,
        payload: JsonObject,
        correlation: CorrelationId? = null,
    ): EventEnvelope {
        val seq = next[stream] ?: 1
        next[stream] = seq + 1
        return EventEnvelope(ids.next(IdKinds.EVENT), type, stream, seq, occurredAt, clock.now(), correlation, null, payload)
    }
}
