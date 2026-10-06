package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json

/** События для тестов хранилища: одна сессия, номера подряд, полезная нагрузка с номером. */
class EventFactory(seed: Long = 1) {
    val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(seed))
    private val stream = StreamRef.of(ids.next(IdKinds.SESSION))
    private var seq = 0L

    fun next(text: String = "вывод"): EventEnvelope {
        seq++
        return EventEnvelope(
            ids.next(IdKinds.EVENT), EventType.SESSION_OUTPUT, stream, seq, clock.now(), clock.now(),
            payload = JsonObject.of("n" to json(seq), "text" to json(text)),
        )
    }
}
