package com.dpsmpx.dpai.core.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ClockTest {
    @Test
    fun manualClockMovesOnlyByAdvance() {
        val clock = ManualClock()
        val start = clock.now()
        assertEquals(start, clock.now())
        clock.advance(90.seconds)
        assertEquals(start + 90.seconds, clock.now())
        assertEquals(90.seconds.inWholeNanoseconds, clock.monotonicNanos())
    }

    @Test
    fun timeDoesNotGoBackwards() {
        assertFailsWith<IllegalArgumentException> { ManualClock().advance((-1).seconds) }
    }

    @Test
    fun wallClockJumpDoesNotMoveMonotonicTime() {
        val clock = ManualClock()
        clock.advance(5.minutes)
        val mono = clock.monotonicNanos()
        clock.jumpWallClock(Timestamp(0))
        assertEquals(Timestamp(0), clock.now())
        assertEquals(mono, clock.monotonicNanos())
    }

    @Test
    fun timestampArithmeticAndIso() {
        val t = Timestamp(1_767_225_600_000)
        assertEquals("2026-01-01T00:00:00Z", t.toIsoString())
        assertEquals(30.seconds, (t + 30.seconds) - t)
        assertEquals((-30).seconds, t - (t + 30.seconds))
        assertEquals(true, t < t + 1.seconds)
    }

    @Test
    fun seededRandomIsReproducibleAndSecureIsNot() {
        val a = RandomSource.seeded(42)
        val b = RandomSource.seeded(42)
        repeat(10) { assertEquals(a.nextLong(), b.nextLong()) }
        assertEquals(a.nextBytes(16).toList(), b.nextBytes(16).toList())
        assertNotEquals(RandomSource.secure().nextBytes(16).toList(), RandomSource.secure().nextBytes(16).toList())
        repeat(100) { assertEquals(true, a.nextInt(7) in 0 until 7) }
    }
}
