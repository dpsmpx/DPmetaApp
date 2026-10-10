package com.dpsmpx.dpai.core.ids

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

class IdsTest {
    @Test
    fun formatAndRoundTrip() {
        val clock = ManualClock()
        val ids = IdGenerator(clock, RandomSource.seeded(7))
        val t = ids.next(IdKinds.TASK)
        assertTrue(Regex("tsk_[0-7][0-9A-HJKMNP-TV-Z]{25}").matches(t.value), t.value)
        assertEquals(t, IdKinds.TASK.parse(t.value).valueOrNull())
        assertEquals(clock.now(), IdKinds.TASK.createdAt(t))
    }

    @Test
    fun wrongKindAndGarbageAreRejected() {
        val s = IdGenerator(ManualClock(), RandomSource.seeded(1)).next(IdKinds.SESSION)
        val wrong = IdKinds.TASK.parse(s.value)
        assertIs<Outcome.Failure>(wrong)
        assertEquals(DiagCode.ID_WRONG_KIND, wrong.errors.single().code)
        for (bad in listOf("", "tsk", "tsk_", "tsk_123", "xyz_01J9Z3M4N5P6Q7R8S9T0V1W2X3", "tsk_01J9Z3M4N5P6Q7R8S9T0V1W2XU", "tsk_81J9Z3M4N5P6Q7R8S9T0V1W2X3")) {
            assertEquals(DiagCode.ID_INVALID, IdKinds.TASK.parse(bad).errors.single().code, bad)
        }
    }

    @Test
    fun sameSeedSameSequence() {
        fun run() = IdGenerator(ManualClock(), RandomSource.seeded(99)).let { g -> List(5) { g.next(IdKinds.EVENT).value } }
        assertEquals(run(), run())
    }

    @Test
    fun monotonicWithinMillisecondAndAfterClockJumpBack() {
        val clock = ManualClock()
        val g = IdGenerator(clock, RandomSource.seeded(3))
        val a = g.next(IdKinds.EVENT)
        val b = g.next(IdKinds.EVENT)
        clock.jumpWallClock(Timestamp(clock.now().epochMillis - 60_000))
        val c = g.next(IdKinds.EVENT)
        assertTrue(a.value < b.value && b.value < c.value)
    }

    /** Свойство: идентификаторы одного генератора упорядочены по времени создания. */
    @Test
    fun idsAreOrderedByCreationTime() {
        Property.forAll(Gen.list(Gen.long(0L..120_000L), 2..40)) { steps ->
            val clock = ManualClock()
            val g = IdGenerator(clock, RandomSource.seeded(steps.sum()))
            val made = steps.map { step ->
                clock.advance(step.milliseconds)
                g.next(IdKinds.TASK)
            }
            assertEquals(made.map { it.value }.sorted(), made.map { it.value })
            assertEquals(made.size, made.toSet().size)
        }
    }

    @Test
    fun providerSessionIdIsSeparateAndValidated() {
        val uuid = "0f8fad5b-d9cb-469f-a165-70867728950e"
        assertEquals(uuid, ProviderSessionId.parse(uuid).valueOrNull()?.value)
        assertIs<Outcome.Failure>(ProviderSessionId.parse("с пробелом"))
        assertIs<Outcome.Failure>(ProviderSessionId.parse(""))
        assertIs<Outcome.Failure>(IdKinds.SESSION.parse(uuid))
    }

    @Test
    fun prefixesAreUnique() {
        assertEquals(IdKinds.ALL.size, IdKinds.ALL.map { it.prefix }.toSet().size)
        assertEquals(15, IdKinds.ALL.size)
    }

    @Test
    fun clockFarInFutureStillEncodes() {
        val clock = ManualClock(Timestamp(4_000_000_000_000))
        val g = IdGenerator(clock, RandomSource.seeded(5))
        clock.advance(10.minutes)
        val id = g.next(IdKinds.CONTEXT)
        assertEquals(clock.now(), IdKinds.CONTEXT.createdAt(id))
    }
}
