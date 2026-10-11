package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.Projection
import com.dpsmpx.dpai.storage.snapshot.Snapshot
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проекция для тестов: число событий и сумма номеров из полезной нагрузки — чувствительна к порядку и пропускам. */
private object CountingProjection : Projection<Pair<Long, Long>> {
    override val name = "counting"

    override fun initial() = 0L to 0L

    override fun apply(state: Pair<Long, Long>, event: EventEnvelope): Pair<Long, Long> {
        val n = (event.payload["n"] as JsonNumber).toLongExact()!!
        return (state.first + 1) to (state.second * 31 + n)
    }

    override fun encode(state: Pair<Long, Long>) = JsonObject.of("count" to json(state.first), "acc" to json(state.second))

    override fun decode(json: JsonObject): Outcome<Pair<Long, Long>> =
        Outcome.success((json["count"] as JsonNumber).toLongExact()!! to (json["acc"] as JsonNumber).toLongExact()!!)
}

class SnapshotRecoveryTest {
    private val recovery = StateRecovery(CountingProjection)

    /** Свойство (ТЗ, §58, §91): снимок в любой точке + хвост = полный пересчёт. */
    @Test
    fun snapshotPlusTailEqualsFullReplay() {
        val gen = Gen.int(0..30).let { n -> Gen { r -> val total = n.next(r); total to Gen.int(0..total).next(r) } }
        Property.forAll(gen) { (total, at) ->
            val vfs = SimulatedVfs()
            val f = EventFactory()
            val journal = (EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value
            val snapshots = SnapshotStore(DocumentStore(vfs, "data"))
            repeat(at) { journal.append(f.next()) }
            snapshots.save(recovery.snapshotOf(journal, recovery.replayAll(journal)))
            repeat(total - at) { journal.append(f.next()) }
            val r = recovery.recover(journal, snapshots)
            assertEquals(recovery.replayAll(journal), r.valueOrNull()!!.state)
            assertEquals(true, r.valueOrNull()!!.fromSnapshot)
            assertTrue(r.diagnostics.isEmpty())
        }
    }

    @Test
    fun snapshotThatDoesNotMatchTheJournalIsIgnored() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val journal = (EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value
        val snapshots = SnapshotStore(DocumentStore(vfs, "data"))
        repeat(3) { journal.append(f.next()) }
        // Снимок «из другого журнала»: хеш записи не тот.
        val foreign = Snapshot("counting", 3, EventJournal.GENESIS, CountingProjection.encode(99L to 99L))
        snapshots.save(foreign)
        val r = recovery.recover(journal, snapshots)
        assertEquals(recovery.replayAll(journal), r.valueOrNull()!!.state)
        assertEquals(false, r.valueOrNull()!!.fromSnapshot)
        assertEquals(DiagCode.SNAPSHOT_INVALID, r.warnings.single().code)
        // Журнал короче снимка (хвост отрезан после сбоя).
        snapshots.save(Snapshot("counting", 10, journal.lastHash, CountingProjection.encode(1L to 1L)))
        assertEquals(DiagCode.SNAPSHOT_INVALID, recovery.recover(journal, snapshots).warnings.single().code)
    }

    @Test
    fun corruptedSnapshotFallsBackToReplay() {
        val vfs = SimulatedVfs()
        val f = EventFactory()
        val journal = (EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value
        val snapshots = SnapshotStore(DocumentStore(vfs, "data"))
        repeat(2) { journal.append(f.next()) }
        snapshots.save(recovery.snapshotOf(journal, recovery.replayAll(journal)))
        vfs.corrupt("data/snapshot-counting", 40)
        val r = recovery.recover(journal, snapshots)
        assertEquals(recovery.replayAll(journal), r.valueOrNull()!!.state)
        assertEquals(DiagCode.SNAPSHOT_INVALID, r.warnings.single().code)
    }
}
