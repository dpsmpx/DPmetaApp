package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.journal.JournalRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EventJournalTest {
    private val path = "data/journal.log"

    private fun open(vfs: SimulatedVfs, quarantine: Boolean = false) =
        assertIs<Outcome.Success<EventJournal>>(EventJournal.open(vfs, path, quarantine)).value

    @Test
    fun encodedLinesAndHashesMatchTheOriginalGenericV1Codec() {
        val factory = EventFactory()
        val events = List(4) { index ->
            factory.next().copy(payload = com.dpsmpx.dpai.core.serialization.JsonObject.of(
                "text" to com.dpsmpx.dpai.core.serialization.JsonString("строка $index\n😀\"\\\u0000".repeat(80)),
                "hash" to com.dpsmpx.dpai.core.serialization.JsonString("это поле внутри события"),
                "position" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(Long.MAX_VALUE),
                "nested" to com.dpsmpx.dpai.core.serialization.JsonArray(listOf(
                    com.dpsmpx.dpai.core.serialization.JsonNumber("1e22"),
                    com.dpsmpx.dpai.core.serialization.JsonString("雪"),
                )),
            ))
        }
        var previous = EventJournal.GENESIS
        val reference = buildString {
            for ((index, event) in events.withIndex()) {
                val body = com.dpsmpx.dpai.core.serialization.JsonObject.of(
                    *com.dpsmpx.dpai.core.serialization.FormatHeader.write(EventJournal.FORMAT, EventJournal.VERSION),
                    "position" to com.dpsmpx.dpai.core.serialization.JsonNumber.of(index + 1),
                    "prev" to com.dpsmpx.dpai.core.serialization.JsonString(previous.toString()),
                    "event" to event.toJson(),
                )
                previous = com.dpsmpx.dpai.core.hashing.Hashing.sha256(body)
                val full = com.dpsmpx.dpai.core.serialization.JsonObject(body.fields +
                    ("hash" to com.dpsmpx.dpai.core.serialization.JsonString(previous.toString())))
                append(com.dpsmpx.dpai.core.serialization.CanonicalJson.write(full)); append('\n')
            }
        }
        val vfs = SimulatedVfs()
        val journal = open(vfs)
        assertTrue(journal.append(events.first()).isSuccess)
        assertTrue(journal.appendAll(events.drop(1)).isSuccess)
        assertEquals(reference, vfs.read(path)!!.toString(Charsets.UTF_8))
        assertEquals(previous, journal.lastHash)
        assertEquals(events, open(vfs).records().map { it.event })
    }

    @Test
    fun appendAndReopenKeepsChain() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val events = List(5) { f.next() }
        val j = open(vfs)
        events.forEach { assertTrue(j.append(it).isSuccess) }
        val reopened = open(vfs)
        assertEquals(events, reopened.records().map { it.event })
        assertEquals(j.lastHash, reopened.lastHash)
        assertEquals(listOf(4L, 5L), reopened.records(4).map { it.position })
    }

    @Test
    fun duplicateAppendIsIdempotentAndConflictIsRejected() {
        val vfs = SimulatedVfs()
        val e = EventFactory().next()
        val j = open(vfs)
        assertEquals(1L, j.append(e).valueOrNull())
        val again = j.append(e)
        assertEquals(1L, again.valueOrNull())
        assertEquals(DiagCode.EVENT_DUPLICATE, again.diagnostics.single().code)
        assertEquals(1L, j.position)
        val conflict = j.append(e.copy(payload = com.dpsmpx.dpai.core.serialization.JsonObject.of("n" to com.dpsmpx.dpai.core.serialization.json(42))))
        assertEquals(DiagCode.EVENT_SEQUENCE_CONFLICT, assertIs<Outcome.Failure>(conflict).errors.single().code)
    }

    /** Групповая запись: та же цепочка, что поштучная; повтор пропускается; при сбое — подтверждённое цело, пачка — префиксом или никак. */
    @Test
    fun groupCommitKeepsChainAndSurvivesCrashes() {
        val f = EventFactory()
        val events = List(6) { f.next() }
        val single = SimulatedVfs()
        open(single).let { j -> events.forEach { j.append(it) } }
        val grouped = SimulatedVfs()
        val g = open(grouped)
        assertEquals((1L..6L).toList(), g.appendAll(events).valueOrNull())
        assertEquals((1L..6L).toList(), g.appendAll(events).valueOrNull(), "повтор пачки идемпотентен")
        assertEquals(open(single).records(), open(grouped).records(), "цепочка хешей та же")

        val first = List(2) { f.next() }
        val batch = List(4) { f.next("пачка ".repeat(30)) }
        var acknowledged = false
        crashEverywhere(
            setup = { vfs -> acknowledged = false; open(vfs).appendAll(first) },
            action = { vfs -> acknowledged = open(vfs).appendAll(batch).isSuccess },
        ) { vfs, k ->
            val j = assertIs<Outcome.Success<EventJournal>>(EventJournal.open(vfs, path), "сбой на операции $k").value
            val got = j.records().map { it.event }
            assertEquals(first, got.take(2), "подтверждённое до пачки цело (операция $k)")
            assertEquals(batch.take(got.size - 2), got.drop(2), "из пачки — только префикс (операция $k)")
            if (acknowledged) assertEquals(first + batch, got, "подтверждённая пачка цела (операция $k)")
        }
    }

    /** Подтверждённые записи переживают сбой на любой операции; неподтверждённая — либо целиком, либо нет. */
    @Test
    fun crashAtEveryStepKeepsAcknowledgedRecords() {
        val f = EventFactory()
        val first = List(3) { f.next() }
        val more = List(3) { f.next("длинный вывод ".repeat(20)) }
        var acknowledged = 0
        crashEverywhere(
            setup = { vfs ->
                acknowledged = 0
                open(vfs).let { j -> first.forEach { j.append(it) } }
            },
            action = { vfs -> open(vfs).let { j -> more.forEach { if (j.append(it).isSuccess) acknowledged++ } } },
        ) { vfs, k ->
            val r = EventJournal.open(vfs, path)
            val j = assertIs<Outcome.Success<EventJournal>>(r, "сбой на операции $k").value
            val got = j.records().map { it.event }
            assertTrue(got.size >= first.size + acknowledged, "сбой на операции $k: потеряны подтверждённые записи")
            assertEquals((first + more).take(got.size), got, "сбой на операции $k: порядок или содержимое")
            // После восстановления журнал снова принимает записи.
            assertTrue(j.append(f.next()).isSuccess || got.size == first.size + more.size)
        }
    }

    @Test
    fun tornTailIsTruncatedAndQuarantined() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val j = open(vfs)
        repeat(2) { j.append(f.next()) }
        vfs.append(path, "{\"format\":\"dpai.jour".toByteArray())
        val r = EventJournal.open(vfs, path)
        assertEquals(2L, assertIs<Outcome.Success<EventJournal>>(r).value.position)
        assertEquals(DiagCode.JOURNAL_TAIL_TRUNCATED, r.warnings.single().code)
        assertTrue(vfs.exists("$path.quarantine-1"))
        assertTrue(EventJournal.open(vfs, path).warnings.isEmpty(), "хвост отрезан один раз")
    }

    @Test
    fun corruptionInTheMiddleNeedsExplicitQuarantine() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val j = open(vfs)
        val events: List<EventEnvelope> = List(4) { f.next() }
        events.forEach { j.append(it) }
        val secondLineStart = vfs.read(path)!!.indexOf('\n'.code.toByte()) + 1
        vfs.corrupt(path, secondLineStart + 30)
        val refused = EventJournal.open(vfs, path)
        assertEquals(DiagCode.JOURNAL_CORRUPTED, assertIs<Outcome.Failure>(refused).errors.single().code)
        val accepted = open(vfs, quarantine = true)
        assertEquals(listOf(events[0]), accepted.records().map { it.event })
        assertTrue(vfs.exists("$path.quarantine-1"), "испорченный хвост сохранён, а не удалён")
    }

    @Test
    fun writeFailureClosesJournalUntilReopen() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val j = open(vfs)
        j.append(f.next())
        vfs.failAtOp = vfs.ops
        assertEquals(DiagCode.STORAGE_IO_FAILED, assertIs<Outcome.Failure>(j.append(f.next())).errors.single().code)
        assertEquals(DiagCode.STORAGE_UNAVAILABLE, assertIs<Outcome.Failure>(j.append(f.next())).errors.single().code)
        val reopened = open(vfs)
        assertTrue(reopened.position in 1L..2L)
    }

    @Test
    fun genesisAndHashAt() {
        val j = open(SimulatedVfs())
        assertEquals(EventJournal.GENESIS, j.hashAt(0))
        assertEquals(null, j.hashAt(1))
    }

    /** Ревью A-13: хвост после номера — то же, что полное чтение с фильтром; испорченный хвост — не тихо. */
    @Test
    fun readCommittedAfterGivesTheTailOfFullRead() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val j = open(vfs)
        repeat(50) { assertTrue(j.append(f.next()).isSuccess) }
        val all = assertIs<Outcome.Success<List<JournalRecord>>>(EventJournal.readCommitted(vfs, path)).value
        for (after in listOf(0L, 1L, 25L, 49L, 50L, 1000L)) {
            val (tail, last) = EventJournal.readCommittedAfter(vfs, path, after).valueOrNull()!!
            assertEquals(all.filter { it.event.sequence > after }, tail, "после $after")
            assertEquals(50L, last)
        }
        // Испорченная запись в хвосте: полное чтение решает — JOURNAL_CORRUPTED, а не пропуск.
        val text = vfs.read(path)!!.toString(Charsets.UTF_8).lines().filter { it.isNotEmpty() }.toMutableList()
        text[45] = text[45].replace("вывод", "подмена")
        vfs.write(path, (text.joinToString("\n") + "\n").toByteArray())
        assertEquals(DiagCode.JOURNAL_CORRUPTED, assertIs<Outcome.Failure>(EventJournal.readCommittedAfter(vfs, path, 40)).errors.single().code)
    }
}
