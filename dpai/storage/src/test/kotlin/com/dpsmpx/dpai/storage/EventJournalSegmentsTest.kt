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

    /** Аудит надёжности, A-6: пропавший запечатанный сегмент — своим типом, оболочка называет причину словами. */
    @Test
    fun missingSealedSegmentIsNamed() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val j = open(vfs)
        repeat(11) { assertTrue(j.append(f.next()).isSuccess) }
        val last = SimulatedVfs().also { copy -> vfs.list("data").forEach { n -> copy.write("data/$n", vfs.read("data/$n")!!) } }
        val again = SimulatedVfs().also { copy -> vfs.list("data").forEach { n -> copy.write("data/$n", vfs.read("data/$n")!!) } }
        // Пропал первый сегмент: история не «короче», а потеряна — отказ открытия.
        vfs.delete("data/journal.log.seg-000000000001")
        val first = EventJournal.open(vfs, path, segmentSize = 3)
        assertEquals(DiagCode.JOURNAL_CORRUPTED, (first as Outcome.Failure).errors.single().code)
        assertTrue("№1…3" in first.errors.single().message, first.errors.single().message)
        // Пропал сегмент в середине: чтение истории называет разрыв, а не пропускает его.
        again.delete("data/journal.log.seg-000000000004")
        val e = kotlin.test.assertFailsWith<com.dpsmpx.dpai.storage.journal.JournalSegmentUnreadable> { open(again).records(1) }
        assertTrue("№4…6" in e.message.orEmpty(), e.message)
        // Пропал последний запечатанный: активный файл цел, но не стыкуется — не порча хвоста, и карантин его не режет.
        last.delete("data/journal.log.seg-000000000007")
        val gap = EventJournal.open(last, path, quarantineCorruption = true, segmentSize = 3)
        assertEquals("data/journal.log.seg-000000000007", (gap as Outcome.Failure).errors.single().subject)
        assertTrue(last.read(path)!!.isNotEmpty(), "активный файл не тронут")
    }

    /**
     * D-95: запечатанный сегмент — с копией; пропал или испорчен — восстановлен
     * из копии (первый, средний, последний), с предупреждением; копия не
     * годна — отказ, как без неё.
     */
    @Test
    fun sealedSegmentsAreRestoredFromCopies() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val events = List(11) { f.next() }
        val j = assertIs<Outcome.Success<EventJournal>>(EventJournal.open(vfs, path, segmentSize = 3, keepCopies = true)).value
        events.forEach { assertTrue(j.append(it).isSuccess) }
        assertEquals(listOf("journal.log.seg-000000000001", "journal.log.seg-000000000004", "journal.log.seg-000000000007"), vfs.list("data/journal-copies"))
        fun reopen() = EventJournal.open(vfs, path, segmentSize = 3, keepCopies = true)

        vfs.delete("data/journal.log.seg-000000000001")
        vfs.delete("data/journal.log.seg-000000000007")
        val first = assertIs<Outcome.Success<EventJournal>>(reopen())
        assertEquals(2, first.diagnostics.count { it.code == DiagCode.JOURNAL_SEGMENT_RESTORED }, first.diagnostics.toString())
        assertEquals(events, first.value.records().map { it.event }, "история цела")

        // Средний испорчен: замечен при чтении истории — и тоже из копии, с сообщением.
        vfs.write("data/journal.log.seg-000000000004", "мусор\n".toByteArray())
        val again = assertIs<Outcome.Success<EventJournal>>(reopen()).value
        val repairs = mutableListOf<com.dpsmpx.dpai.core.diagnostics.Diagnostic>()
        again.onRepair = { repairs += it }
        assertEquals(events, again.records().map { it.event })
        assertEquals(DiagCode.JOURNAL_SEGMENT_RESTORED, repairs.single().code)

        // Копия не годна — отказ, как без неё.
        vfs.delete("data/journal.log.seg-000000000007")
        vfs.write("data/journal-copies/journal.log.seg-000000000007", "мусор\n".toByteArray())
        assertEquals(DiagCode.JOURNAL_CORRUPTED, (reopen() as Outcome.Failure).errors.single().code)
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
