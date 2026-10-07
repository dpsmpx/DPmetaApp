package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.journal.EventJournal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Сегменты журнала (D-37): память — активный сегмент, история — на диске, цепочка сквозная. */
class EventJournalSegmentsTest {
    private val path = "data/journal.log"

    private fun open(vfs: SimulatedVfs, size: Int = 3) =
        assertIs<Outcome.Success<EventJournal>>(EventJournal.open(vfs, path, segmentSize = size)).value

    @Test
    fun fullSegmentIsSealedAndHistoryReadsAcrossSegments() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val events = List(8) { f.next() }
        val j = open(vfs)
        events.forEach { assertTrue(j.append(it).isSuccess) }
        assertEquals(2, j.sealedSegments)
        assertEquals(7L, j.activeFrom, "в памяти — только активный сегмент")
        assertEquals(events, j.records().map { it.event })
        assertEquals(listOf(5L, 6L, 7L, 8L), j.records(5).map { it.position })
        assertEquals(listOf("data/journal.log", "data/journal.log.seg-000000000001", "data/journal.log.seg-000000000004"), vfs.list("data").map { "data/$it" })

        val reopened = open(vfs)
        assertEquals(8L, reopened.position)
        assertEquals(j.lastHash, reopened.lastHash)
        assertEquals(events, reopened.records().map { it.event })
        assertEquals(j.hashAt(3), reopened.hashAt(3), "хеш записи из запечатанного сегмента")
        assertTrue(reopened.append(f.next()).isSuccess)
        assertEquals(9L, reopened.position)
    }

    @Test
    fun groupCommitAndDuplicatesWithinActiveSegment() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val j = open(vfs, size = 4)
        val batch = List(5) { f.next() }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), j.appendAll(batch).valueOrNull(), "пачка не делится между сегментами")
        assertEquals(1, j.sealedSegments)
        val e = f.next()
        assertEquals(6L, j.append(e).valueOrNull())
        assertEquals(DiagCode.EVENT_DUPLICATE, j.append(e).diagnostics.single().code)
        assertEquals(6L, open(vfs, size = 4).position)
    }

    @Test
    fun crashAtEveryStepOfSealingKeepsAcknowledgedRecords() {
        val f = EventFactory()
        val first = List(2) { f.next() }
        val third = f.next()
        val fourth = f.next()
        crashEverywhere(
            setup = { vfs -> open(vfs).let { j -> first.forEach { e -> j.append(e) } } },
            action = { vfs ->
                val j = open(vfs)
                j.append(third) // третья запись заполняет сегмент — печать
                j.append(fourth)
            },
        ) { vfs, k ->
            val j = assertIs<Outcome.Success<EventJournal>>(EventJournal.open(vfs, path, segmentSize = 3), "сбой на операции $k").value
            val got = j.records().map { it.event }
            assertTrue(got == first || got == first + third || got == first + third + fourth, "сбой на операции $k: ${got.size} записей")
            // Журнал после сбоя принимает запись и остаётся цепочкой.
            assertTrue(j.append(f.next()).isSuccess, "сбой на операции $k: запись после восстановления")
            val again = assertIs<Outcome.Success<EventJournal>>(EventJournal.open(vfs, path, segmentSize = 3)).value
            assertEquals((1L..again.position).toList(), again.records().map { it.position }, "сбой на операции $k: номера подряд")
        }
    }
}
