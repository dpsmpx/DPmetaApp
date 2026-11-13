package com.dpsmpx.dpai.orchestrator.commands

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.orchestrator.EventRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Снимок настроек контура (D-165): v2 с переходом у предела, v1 читается как «выключено». */
class ControlProjectionTest {
    private fun doc(text: String) = (JsonParser().parse(text) as Outcome.Success).value as JsonObject

    @Test
    fun versionOneReadsAsSwitchOffAndVersionTwoRoundTrips() {
        // Синтетический образец прежнего снимка (до D-165).
        val v1 = ControlProjection.decode(doc("""{"format":"dpai.control-state","version":1,"disabledTargets":[],"maxSessions":4}"""))
        assertEquals(ControlState(emptySet(), 4, autoExecutorSwitch = false), assertIs<Outcome.Success<ControlState>>(v1).value)
        val state = ControlState(emptySet(), null, autoExecutorSwitch = true)
        val encoded = ControlProjection.encode(state)
        assertEquals(2L, (encoded["version"] as com.dpsmpx.dpai.core.serialization.JsonNumber).toLongExact())
        assertEquals(state, assertIs<Outcome.Success<ControlState>>(ControlProjection.decode(encoded)).value)
        val newer = ControlProjection.decode(doc("""{"format":"dpai.control-state","version":99,"disabledTargets":[],"maxSessions":null,"autoExecutorSwitch":true}"""))
        assertTrue(newer is Outcome.Failure, "неизвестная версия не читается молча")
    }

    @Test
    fun eventTurnsTheSwitchOnAndOff() {
        val clock = ManualClock()
        val recorder = EventRecorder(IdGenerator(clock, RandomSource.seeded(7)), clock)
        val on = ControlProjection.apply(ControlState.EMPTY, recorder.executorSwitchEnvelope(true, clock.now()))
        assertTrue(on.autoExecutorSwitch)
        assertEquals(ControlState.EMPTY, ControlProjection.apply(on, recorder.executorSwitchEnvelope(false, clock.now())))
    }
}
