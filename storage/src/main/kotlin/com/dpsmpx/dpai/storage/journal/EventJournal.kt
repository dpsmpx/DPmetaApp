package com.dpsmpx.dpai.storage.journal

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.EventId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

/** Запись журнала: номер (с 1), событие, хеш записи. */
data class JournalRecord(val position: Long, val event: EventEnvelope, val hash: ContentHash)

/**
 * Журнал событий только на дописывание (D-12, D-24; ТЗ, §57).
 *
 * Строка — канонический `dpai.journal-record`: номер, хеш предыдущей
 * записи, событие, хеш этой записи. Цепочка хешей ловит и порчу, и
 * перестановку, и потерю записи в середине. [append] возвращает успех
 * только после fsync: подтверждённая запись переживает сбой.
 *
 * Открытие ([open]) — восстановление:
 *
 * - оборванный хвост (последняя строка без перевода строки или испорченная
 *   последняя запись — она не была подтверждена) отрезается, отрезанные
 *   байты сохраняются в карантин, предупреждение `JOURNAL_TAIL_TRUNCATED`;
 * - испорченная запись, за которой есть целые, — `JOURNAL_CORRUPTED`:
 *   журнал не открывается, пока пользователь явно не подтвердит карантин
 *   хвоста (`quarantineCorruption = true`, `docs/RECOVERY.md`, §4).
 *
 * После отказа записи журнал не принимает новых записей до повторного
 * открытия: что именно легло на диск, неизвестно (ТЗ, §80).
 */
class EventJournal private constructor(private val vfs: Vfs, private val path: String, loaded: List<JournalRecord>) {
    private val records = ArrayList(loaded)
    private val positionOf = HashMap<EventId, Long>().apply { loaded.forEach { put(it.event.id, it.position) } }
    private var broken = false

    val position: Long get() = records.size.toLong()

    val lastHash: ContentHash get() = records.lastOrNull()?.hash ?: GENESIS

    fun hashAt(position: Long): ContentHash? =
        if (position == 0L) GENESIS else records.getOrNull((position - 1).toInt())?.hash

    /** Записи с номера [from] включительно. */
    fun records(from: Long = 1): List<JournalRecord> = records.drop((from - 1).coerceAtLeast(0).toInt())

    @Synchronized
    fun append(event: EventEnvelope): Outcome<Long> {
        if (broken) return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "журнал закрыт после отказа записи: нужно открыть заново", path)
        positionOf[event.id]?.let { known ->
            val same = records[(known - 1).toInt()].event == event
            return if (same) {
                Outcome.success(known, listOf(Diagnostic.info(DiagCode.EVENT_DUPLICATE, "событие уже в журнале под №$known", path)))
            } else {
                Outcome.failure(DiagCode.EVENT_SEQUENCE_CONFLICT, "идентификатор события уже занят другим содержимым", path)
            }
        }
        val record = build(position + 1, lastHash, event)
        val line = (CanonicalJson.write(record.second) + "\n").toByteArray(Charsets.UTF_8)
        return try {
            val created = !vfs.exists(path)
            vfs.mkdirs(Vfs.parentOf(path))
            vfs.append(path, line)
            vfs.fsync(path)
            if (created) vfs.fsyncDir(Vfs.parentOf(path))
            records += record.first
            positionOf[event.id] = record.first.position
            Outcome.success(record.first.position)
        } catch (e: IOException) {
            broken = true
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "запись в журнал не подтверждена", e, path))
        }
    }

    companion object {
        const val FORMAT = "dpai.journal-record"
        const val VERSION = 1
        val GENESIS: ContentHash = Hashing.sha256("dpai.journal genesis")

        fun open(vfs: Vfs, path: String, quarantineCorruption: Boolean = false): Outcome<EventJournal> {
            val bytes = try {
                vfs.read(path)
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "журнал не прочитан", e, path))
            } ?: return Outcome.success(EventJournal(vfs, path, emptyList()))

            val loaded = ArrayList<JournalRecord>()
            var validEnd = 0 // байт после последней целой записи
            var start = 0
            var badLine: Int? = null
            var linesAfterBad = 0
            var lineNo = 0
            while (start < bytes.size) {
                val nl = indexOf(bytes, '\n'.code.toByte(), start)
                if (nl < 0) break // оборванная строка без перевода строки — хвост
                lineNo++
                if (badLine == null) {
                    val rec = parseRecord(bytes.copyOfRange(start, nl), loaded.size + 1L, loaded.lastOrNull()?.hash ?: GENESIS)
                    if (rec != null) {
                        loaded += rec
                        validEnd = nl + 1
                    } else {
                        badLine = lineNo
                    }
                } else {
                    linesAfterBad++
                }
                start = nl + 1
            }
            val diags = mutableListOf<Diagnostic>()
            if (badLine != null && linesAfterBad > 0 && !quarantineCorruption) {
                return Outcome.failure(
                    DiagCode.JOURNAL_CORRUPTED,
                    "запись №$badLine испорчена, за ней ещё $linesAfterBad: журнал открыт только после подтверждения карантина хвоста",
                    path,
                )
            }
            if (validEnd < bytes.size) {
                val removed = bytes.copyOfRange(validEnd, bytes.size)
                try {
                    val q = quarantine(vfs, path, removed)
                    vfs.truncate(path, validEnd.toLong())
                    vfs.fsync(path)
                    diags += Diagnostic.warning(
                        DiagCode.JOURNAL_TAIL_TRUNCATED,
                        "отрезан неподтверждённый или испорченный хвост (${removed.size} байт) после записи №${loaded.size}; сохранён в $q",
                        path,
                    )
                } catch (e: IOException) {
                    return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "хвост журнала не отрезан", e, path))
                }
            }
            return Outcome.success(EventJournal(vfs, path, loaded), diags)
        }

        private fun build(position: Long, prev: ContentHash, event: EventEnvelope): Pair<JournalRecord, JsonObject> {
            val body = JsonObject(
                linkedMapOf(
                    *FormatHeader.write(FORMAT, VERSION),
                    "position" to JsonNumber.of(position),
                    "prev" to JsonString(prev.toString()),
                    "event" to event.toJson(),
                ),
            )
            val hash = Hashing.sha256(body)
            val full = JsonObject(LinkedHashMap(body.fields).apply { put("hash", JsonString(hash.toString())) })
            return JournalRecord(position, event, hash) to full
        }

        /** Запись цела: формат, номер подряд, хеш предыдущей, свой хеш, событие. Иначе — `null`. */
        private fun parseRecord(line: ByteArray, expectedPosition: Long, expectedPrev: ContentHash): JournalRecord? {
            val value = JsonParser().parse(line.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject ?: return null
            val diags = DiagnosticCollector()
            val f = JsonFields(value, "$", diags)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, diags) ?: return null
            val position = f.long("position")
            val prev = f.string("prev")
            val event = f.raw("event")
            val hash = f.string("hash")?.let { ContentHash.parse(it).valueOrNull() }
            f.finish()
            if (diags.hasErrors || position != expectedPosition || prev != expectedPrev.toString() || event == null || hash == null) {
                return null
            }
            val body = JsonObject(value.fields - "hash")
            if (Hashing.sha256(body) != hash) return null
            val envelope = EventEnvelope.fromJson(event).valueOrNull() ?: return null
            return JournalRecord(position, envelope, hash)
        }

        private fun quarantine(vfs: Vfs, path: String, bytes: ByteArray): String {
            var n = 1
            while (vfs.exists("$path.quarantine-$n")) n++
            val q = "$path.quarantine-$n"
            vfs.write(q, bytes)
            vfs.fsync(q)
            vfs.fsyncDir(Vfs.parentOf(path))
            return q
        }

        private fun indexOf(bytes: ByteArray, b: Byte, from: Int): Int {
            for (i in from until bytes.size) if (bytes[i] == b) return i
            return -1
        }
    }
}
