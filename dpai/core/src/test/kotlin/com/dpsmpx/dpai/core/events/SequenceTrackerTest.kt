package com.dpsmpx.dpai.core.events

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import com.dpsmpx.dpai.testkit.flatMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SequenceTrackerTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(5))
    private val stream = StreamRef.of(ids.next(IdKinds.SESSION))

    private fun events(n: Int, s: StreamRef = stream) = (1..n).map {
        EventEnvelope(ids.next(IdKinds.EVENT), EventType.SESSION_OUTPUT, s, it.toLong(), clock.now(), clock.now())
    }

    @Test
    fun acceptDuplicateGapConflict() {
        val t = SequenceTracker()
        val (e1, e2, e3, e4) = events(4)
        assertIs<SequenceTracker.Verdict.Accepted>(t.offer(e1))
        assertEquals(DiagCode.EVENT_DUPLICATE, assertIs<SequenceTracker.Verdict.Duplicate>(t.offer(e1)).diagnostic.code)
        val gap = assertIs<SequenceTracker.Verdict.Gap>(t.offer(e4))
        assertEquals(2L..3L, gap.missing)
        assertEquals(DiagCode.EVENT_SEQUENCE_GAP, gap.diagnostic.code)
        assertEquals(2, t.expected(stream))
        assertIs<SequenceTracker.Verdict.Accepted>(t.offer(e2))
        assertIs<SequenceTracker.Verdict.Accepted>(t.offer(e3))
        assertIs<SequenceTracker.Verdict.Accepted>(t.offer(e4))
        val impostor = e2.copy(id = ids.next(IdKinds.EVENT))
        assertEquals(DiagCode.EVENT_SEQUENCE_CONFLICT, assertIs<SequenceTracker.Verdict.Conflict>(t.offer(impostor)).diagnostic.code)
        val moved = e1.copy(sequence = 5)
        assertIs<SequenceTracker.Verdict.Conflict>(t.offer(moved))
    }

    @Test
    fun streamsAreIndependent() {
        val t = SequenceTracker()
        val other = StreamRef.of(ids.next(IdKinds.TASK))
        assertIs<SequenceTracker.Verdict.Accepted>(t.offer(events(1)[0]))
        assertIs<SequenceTracker.Verdict.Accepted>(t.offer(events(1, other)[0]))
    }

    /**
     * Свойство (ТЗ, §91): любые перестановки с повторами, доставляемые до
     * приёма (отложенное после пропуска — в конец очереди), дают ровно 1…N по
     * порядку, каждое один раз.
     */
    @Test
    fun anyDeliveryOrderYieldsTheSameStream() {
        val gen = Gen.int(1..25).flatMap { n ->
            val evs = events(n)
            Gen.list(Gen.element(evs), 0..n).flatMap { dups -> Gen.shuffled(evs + dups) }
        }
        Property.forAll(gen) { delivery ->
            val t = SequenceTracker()
            val queue = ArrayDeque(delivery)
            val applied = mutableListOf<Long>()
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 10_000) {
                val e = queue.removeFirst()
                when (t.offer(e)) {
                    is SequenceTracker.Verdict.Accepted -> applied += e.sequence
                    is SequenceTracker.Verdict.Gap -> queue.addLast(e)
                    is SequenceTracker.Verdict.Duplicate -> Unit
                    is SequenceTracker.Verdict.Conflict -> error("ложный конфликт для $e")
                }
            }
            val n = delivery.maxOf { it.sequence }
            assertEquals((1..n).toList(), applied)
        }
    }
}
