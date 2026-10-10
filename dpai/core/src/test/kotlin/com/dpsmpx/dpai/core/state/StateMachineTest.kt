package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class StateMachineTest {
    private enum class S { A, B, C, D }

    @Test
    fun ordinaryExplicitAndInvalidTransitions() {
        val t = TransitionTable("тест", S.entries, S.A, setOf(S.C), mapOf(S.A to setOf(S.B), S.B to setOf(S.C), S.D to setOf(S.A)), mapOf(S.C to setOf(S.D)))
        assertEquals(Transition(S.A, S.B, changed = true, mode = TransitionMode.ORDINARY), t.transition(S.A, S.B).valueOrNull())
        assertEquals(DiagCode.INVALID_TRANSITION, assertIs<Outcome.Failure>(t.transition(S.A, S.C, subject = "x")).errors.single().code)
        assertEquals(DiagCode.EXPLICIT_OPERATION_REQUIRED, assertIs<Outcome.Failure>(t.transition(S.C, S.D)).errors.single().code)
        assertTrue(t.transition(S.C, S.D, TransitionMode.EXPLICIT).isSuccess)
        assertFalse(t.isAllowed(S.C, S.D))
        assertTrue(t.isAllowed(S.C, S.D, TransitionMode.EXPLICIT))
    }

    /** Повтор перехода в текущее состояние — успех без изменения (ТЗ, §101, §102). */
    @Test
    fun repeatedTransitionIsIdempotent() {
        val r = SessionLifecycle.table.transition(SessionState.RUNNING, SessionState.RUNNING)
        assertEquals(false, r.valueOrNull()?.changed)
        val terminal = SessionLifecycle.table.transition(SessionState.COMPLETED, SessionState.COMPLETED)
        assertEquals(false, terminal.valueOrNull()?.changed)
    }

    @Test
    fun tableValidatesItself() {
        assertFailsWith<IllegalArgumentException>("недостижимое") {
            TransitionTable("т", S.entries, S.A, emptySet(), mapOf(S.A to setOf(S.B)))
        }
        assertFailsWith<IllegalArgumentException>("петля") {
            TransitionTable("т", S.entries, S.A, emptySet(), mapOf(S.A to setOf(S.A, S.B, S.C, S.D)))
        }
        assertFailsWith<IllegalArgumentException>("выход из конечного") {
            TransitionTable("т", S.entries, S.A, setOf(S.B), mapOf(S.A to setOf(S.B), S.B to setOf(S.C, S.D)))
        }
        assertFailsWith<IllegalArgumentException>("обычный и явный") {
            TransitionTable("т", S.entries, S.A, emptySet(), mapOf(S.A to setOf(S.B, S.C, S.D)), mapOf(S.A to setOf(S.B)))
        }
    }

    @Test
    fun heartbeatFreshnessUsesSeveralThresholdsAndNeverInfersDeath() {
        val th = HeartbeatThresholds(aging = 30.seconds, stale = 2.minutes, unknown = 10.minutes)
        val now = Timestamp(1_000_000_000)
        fun at(ago: kotlin.time.Duration) = HeartbeatFreshness.classify(now - ago, now, th)
        assertEquals(HeartbeatFreshness.FRESH, at(0.seconds))
        assertEquals(HeartbeatFreshness.AGING, at(30.seconds))
        assertEquals(HeartbeatFreshness.STALE, at(2.minutes))
        assertEquals(HeartbeatFreshness.UNKNOWN, at(10.minutes))
        assertEquals(HeartbeatFreshness.UNKNOWN, at(1000.minutes), "долгое молчание — неизвестно, а не мертво")
        assertEquals(HeartbeatFreshness.UNKNOWN, HeartbeatFreshness.classify(null, now, th))
        assertEquals(HeartbeatFreshness.DEAD, HeartbeatFreshness.classify(now, now, th, processConfirmedAbsent = true))
        assertEquals(HeartbeatFreshness.FRESH, HeartbeatFreshness.classify(now + 5.seconds, now, th), "часы впереди")
        assertFailsWith<IllegalArgumentException> { HeartbeatThresholds(10.seconds, 5.seconds, 20.seconds) }
    }
}
