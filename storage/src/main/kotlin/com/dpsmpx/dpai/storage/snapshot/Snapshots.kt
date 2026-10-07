package com.dpsmpx.dpai.storage.snapshot

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal

/**
 * Производное состояние из событий (D-12): начальное значение, применение
 * события, запись и чтение снимка. Применение детерминировано (D-22).
 */
interface Projection<S> {
    /** Имя проекции: снимок одной проекции не читается другой. */
    val name: String

    fun initial(): S

    fun apply(state: S, event: EventEnvelope): S

    fun encode(state: S): JsonObject

    fun decode(json: JsonObject): Outcome<S>
}

/** Снимок: состояние после записи журнала №[position] с хешем [recordHash] (ТЗ, §58). */
data class Snapshot(val projection: String, val position: Long, val recordHash: ContentHash, val state: JsonObject)

/** Снимки проекций в [DocumentStore] (`dpai.snapshot` v1; ключ — `snapshot-<проекция>`). */
class SnapshotStore(private val documents: DocumentStore) {
    fun save(snapshot: Snapshot): Outcome<ContentHash> = documents.write(
        key(snapshot.projection),
        JsonObject(
            linkedMapOf(
                *FormatHeader.write(FORMAT, VERSION),
                "projection" to JsonString(snapshot.projection),
                "position" to JsonNumber.of(snapshot.position),
                "recordHash" to JsonString(snapshot.recordHash.toString()),
                "state" to snapshot.state,
            ),
        ),
    )

    fun load(projection: String): Outcome<Snapshot?> {
        val read = documents.read(key(projection))
        val doc = (read as? Outcome.Success)?.value ?: return read.let { if (it is Outcome.Failure) it else Outcome.success(null) }
        val diags = DiagnosticCollector()
        val f = JsonFields(doc.content, "$", diags)
        FormatHeader.read(f, FORMAT, VERSION..VERSION, diags)
        val name = f.string("projection")
        val position = f.long("position")
        val hash = f.string("recordHash")?.let { ContentHash.parse(it).valueOrNull() }
        val state = f.obj("state")
        f.finish()
        if (diags.hasErrors || name == null || position == null || hash == null || state == null) {
            return Outcome.Failure(diags.all.ifEmpty { listOf(Diagnostic.error(DiagCode.STORAGE_CORRUPTED, "снимок неполон")) })
        }
        return Outcome.success(Snapshot(name, position, hash, doc.content.fields["state"] as JsonObject), read.diagnostics)
    }

    private fun key(projection: String) = "snapshot-$projection"

    companion object {
        const val FORMAT = "dpai.snapshot"
        const val VERSION = 1
    }
}

/**
 * Восстановление состояния после перезапуска (ТЗ, §54, §58): последний
 * годный снимок + события после него. Снимок, который не сходится с
 * журналом (журнал короче, хеш записи на позиции другой, состояние не
 * читается), не применяется: `SNAPSHOT_INVALID` и полный пересчёт. Итог
 * равен полному пересчёту из журнала (`SnapshotRecoveryTest`).
 */
class StateRecovery<S>(private val projection: Projection<S>) {
    data class Recovered<S>(val state: S, val position: Long, val fromSnapshot: Boolean)

    fun snapshotOf(journal: EventJournal, state: S): Snapshot =
        Snapshot(projection.name, journal.position, journal.lastHash, projection.encode(state))

    /** Состояние из всей истории — потоком, по сегменту в памяти (D-37). */
    fun replayAll(journal: EventJournal, initial: S = projection.initial()): S {
        var s = initial
        journal.forEachRecord { r ->
            s = projection.apply(s, r.event)
            true
        }
        return s
    }

    fun recover(journal: EventJournal, snapshots: SnapshotStore, initial: S = projection.initial()): Outcome<Recovered<S>> {
        val notes = mutableListOf<Diagnostic>()
        val loaded = snapshots.load(projection.name)
        val snapshot = when (loaded) {
            is Outcome.Success -> loaded.value
            is Outcome.Failure -> null.also { notes += invalid("снимок не читается", loaded.diagnostics) }
        }
        notes += loaded.diagnostics.filterNot { it.isError }
        var base: Pair<S, Long>? = null
        if (snapshot != null) {
            val reason = when {
                snapshot.projection != projection.name -> "снимок другой проекции"
                snapshot.position > journal.position -> "журнал короче снимка"
                journal.hashAt(snapshot.position) != snapshot.recordHash -> "хеш записи журнала не совпал со снимком"
                else -> null
            }
            if (reason != null) {
                notes += invalid(reason, emptyList())
            } else {
                when (val st = projection.decode(snapshot.state)) {
                    is Outcome.Success -> base = st.value to snapshot.position
                    is Outcome.Failure -> notes += invalid("состояние снимка не читается", st.diagnostics)
                }
            }
        }
        val (start, from) = base ?: (initial to 0L)
        var state = start
        journal.forEachRecord(from + 1) { r ->
            state = projection.apply(state, r.event)
            true
        }
        return Outcome.success(Recovered(state, journal.position, base != null), notes)
    }

    private fun invalid(reason: String, causes: List<Diagnostic>) =
        Diagnostic.of(DiagCode.SNAPSHOT_INVALID, "$reason — состояние пересчитано из журнала", projection.name, causes = causes.filter { it.isError })
}
