package com.dpsmpx.dpai.core.measure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class MeasureTest {
    @Test
    fun unknownIsNotZero() {
        val total = listOf(Measure.observed(10L), Measure.unknown("нет данных провайдера"), Measure.observed(5L)).sum()
        val u = assertIs<Measure.Unknown>(total)
        assertEquals("нет данных провайдера", u.reason)
        assertNull(total.orNull())
    }

    @Test
    fun derivationWeakensSource() {
        assertEquals(Measure.Known(15L, MeasureSource.DERIVED), listOf(Measure.observed(10L), Measure.observed(5L)).sum())
        assertEquals(Measure.Known(2L, MeasureSource.DERIVED), Measure.observed(1L).map { it * 2 })
        assertEquals(Measure.Known(3L, MeasureSource.ESTIMATED), Measure.estimated(1L).combine(Measure.observed(2L)) { a, b -> a + b })
        assertEquals(Measure.Known(0L, MeasureSource.OBSERVED), emptyList<Measure<Long>>().sum())
    }

    @Test
    fun bothUnknownReasonsAreKept() {
        val m = Measure.unknown("нет heartbeat").combine(Measure.unknown("target недоступен")) { a: Long, b: Long -> a + b }
        assertEquals("нет heartbeat; target недоступен", assertIs<Measure.Unknown>(m).reason)
    }
}
