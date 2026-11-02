package com.dpsmpx.dpai.storage.journal

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.EventId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.map
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
 * Сегменты (D-37): активный сегмент — файл [path]; когда в нём
 * [segmentSize] записей, он атомарно переименовывается в
 * `<path>.seg-<номер первой записи>`, и следующая запись начинает новый
 * активный сегмент — номера и цепочка хешей сквозные. В памяти — только
 * активный сегмент: память журнала не растёт с его историей. Старые
 * сегменты читаются потоково ([forEachRecord]); идемпотентность повтора
 * события (тот же id) — в пределах активного сегмента.
 *
 * Открытие ([open]) — восстановление:
 *
 * - оборванный хвост (последняя строка без перевода строки или испорченная
 *   последняя запись — она не была подтверждена) отрезается, отрезанные
 *   байты сохраняются в карантин, предупреждение `JOURNAL_TAIL_TRUNCATED`;
 * - испорченная запись, за которой есть целые, — `JOURNAL_CORRUPTED`:
 *   журнал не открывается, пока пользователь явно не подтвердит карантин
 *   хвоста (`quarantineCorruption = true`, `docs/RECOVERY.md`, §4);
 * - стык с запечатанным сегментом проверяется по его последней записи.
 *
 * После отказа записи журнал не принимает новых записей до повторного
 * открытия: что именно легло на диск, неизвестно (ТЗ, §80).
 */
class EventJournal private constructor(
    private val vfs: Vfs,
    private val path: String,
    private val segmentSize: Int,
    private val sealed: MutableList<String>,
    private var offset: Long,
    private var baseHash: ContentHash,
    loaded: List<JournalRecord>,
) {
    private val records = ArrayList(loaded)
    private val positionOf = HashMap<EventId, Long>().apply { loaded.forEach { put(it.event.id, it.position) } }
    private var broken = false

    /** Закрыт [close]: этот экземпляр больше не пишет — журнал открыт заново другим (D-94). */
    private var closed = false

    /** Номер последней записи (0 — журнал пуст). */
    val position: Long get() = offset + records.size

    /**
     * Журнал закрыт после отказа записи: что легло на диск, неизвестно, и до
     * повторного [open] записи не принимаются (ТЗ, §80). Состояние наружу —
     * чтобы контур показал «хранилище не в порядке», а не «всё хорошо»
     * (D-87, D-13: неизвестное — не ноль).
     */
    val isBroken: Boolean get() = broken

    /**
     * Закрыть на запись (D-94): контур пересобран и открыл журнал заново, а
     * прежний экземпляр с его номером и хешем не должен дописать ни строки —
     * иначе в файле вилка цепочки. Чтение остаётся.
     */
    @Synchronized
    fun close() {
        closed = true
    }

    val lastHash: ContentHash get() = records.lastOrNull()?.hash ?: baseHash

    /** Номер первой записи активного сегмента: всё раньше — в запечатанных сегментах. */
    val activeFrom: Long get() = offset + 1

    /** Сколько сегментов запечатано. */
    val sealedSegments: Int get() = sealed.size

    fun hashAt(position: Long): ContentHash? = when {
        position == 0L -> GENESIS
        position == offset -> baseHash
        position > offset -> records.getOrNull((position - offset - 1).toInt())?.hash
        else -> {
            var found: ContentHash? = null
            forEachRecord(position) { r -> if (r.position == position) found = r.hash; false }
            found
        }
    }

    /**
     * Записи с номера [from] включительно — списком. Для чтения всей истории
     * длинного журнала — [forEachRecord]: список держит её в памяти.
     */
    fun records(from: Long = 1): List<JournalRecord> {
        if (from > offset) return records.drop((from - offset - 1).coerceAtLeast(0).toInt())
        val out = ArrayList<JournalRecord>()
        forEachRecord(from) { out += it; true }
        return out
    }

    /**
     * Записи с номера [from] по порядку, по одному сегменту в памяти;
     * [body] возвращает `false`, чтобы остановиться. Запечатанный сегмент,
     * который не читается, — `IllegalStateException`: его записи когда-то
     * были подтверждены (D-24), молча их пропустить нельзя.
     */
    fun forEachRecord(from: Long = 1, body: (JournalRecord) -> Boolean) {
        val names = sealed.toList()
        for ((i, name) in names.withIndex()) {
            val nextFirst = names.getOrNull(i + 1)?.let(::firstOf) ?: (offset + 1)
            if (nextFirst <= from) continue
            val bytes = checkNotNull(vfs.read(name)) { "запечатанный сегмент $name пропал" }
            val scan = scan(bytes, null)
            check(scan.badLine == null) { "запечатанный сегмент $name испорчен (запись №${scan.badLine})" }
            for (r in scan.loaded) if (r.position >= from && !body(r)) return
        }
        for (r in records) if (r.position >= from && !body(r)) return
    }

    /** Файл журнала уже есть на диске: проверять каталог и делать fsync каталога больше не нужно. */
    private var onDisk = false

    /** Дописать и сделать долговечным; при первой записи — каталог и его fsync (файл мог быть новым). */
    private fun write(bytes: ByteArray) {
        if (onDisk) {
            vfs.appendDurably(path, bytes)
            return
        }
        val created = !vfs.exists(path)
        vfs.mkdirs(Vfs.parentOf(path))
        vfs.appendDurably(path, bytes)
        if (created) vfs.fsyncDir(Vfs.parentOf(path))
        onDisk = true
    }

    /**
     * Активный сегмент полон — запечатать: переименование атомарно, сбой
     * посередине оставляет либо прежний активный, либо запечатанный и пустой
     * активный — оба открываются. Неудача — не отказ записи: попробуем после
     * следующей.
     */
    private fun rotateIfFull() {
        if (records.size < segmentSize) return
        val name = segmentName(path, offset + 1)
        try {
            vfs.rename(path, name)
        } catch (_: IOException) {
            return
        }
        sealed += name
        baseHash = lastHash
        offset = position
        records.clear()
        positionOf.clear()
        onDisk = false
        runCatching { vfs.fsyncDir(Vfs.parentOf(path)) }
    }

    @Synchronized
    fun append(event: EventEnvelope): Outcome<Long> {
        if (closed) return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "журнал закрыт: контур пересобран, пишет новый экземпляр", path)
        if (broken) return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "журнал закрыт после отказа записи: нужно открыть заново", path)
        positionOf[event.id]?.let { known ->
            val same = records[(known - offset - 1).toInt()].event == event
            return if (same) {
                Outcome.success(known, listOf(Diagnostic.info(DiagCode.EVENT_DUPLICATE, "событие уже в журнале под №$known", path)))
            } else {
                Outcome.failure(DiagCode.EVENT_SEQUENCE_CONFLICT, "идентификатор события уже занят другим содержимым", path)
            }
        }
        val record = build(position + 1, lastHash, event)
        val line = (CanonicalJson.write(record.second) + "\n").toByteArray(Charsets.UTF_8)
        return try {
            write(line)
            records += record.first
            positionOf[event.id] = record.first.position
            rotateIfFull()
            Outcome.success(record.first.position)
        } catch (e: IOException) {
            broken = true
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "запись в журнал не подтверждена", e, path))
        }
    }

    /**
     * Групповая запись (этап 17, `docs/PERFORMANCE.md`): пачка событий —
     * одно дописывание и один fsync. Подтверждение — после fsync, для
     * всей пачки сразу; при сбое до него на диске остаётся префикс целых
     * записей или ничего — неподтверждённое, как у [append]. Повтор
     * события, уже лежащего в журнале, пропускается; конфликт — отказ всей
     * пачки до записи. Пачка не делится между сегментами.
     */
    @Synchronized
    fun appendAll(events: List<EventEnvelope>): Outcome<List<Long>> {
        if (closed) return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "журнал закрыт: контур пересобран, пишет новый экземпляр", path)
        if (broken) return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "журнал закрыт после отказа записи: нужно открыть заново", path)
        val positions = ArrayList<Long>(events.size)
        val fresh = ArrayList<JournalRecord>()
        val seen = HashMap<EventId, Long>()
        val bytes = java.io.ByteArrayOutputStream()
        var prev = lastHash
        for (event in events) {
            val known = positionOf[event.id] ?: seen[event.id]
            if (known != null) {
                val existing = records.getOrNull((known - offset - 1).toInt())?.event ?: fresh[(known - position - 1).toInt()].event
                if (existing != event) return Outcome.failure(DiagCode.EVENT_SEQUENCE_CONFLICT, "идентификатор события уже занят другим содержимым", path)
                positions += known
                continue
            }
            val record = build(position + fresh.size + 1, prev, event)
            bytes.write((CanonicalJson.write(record.second) + "\n").toByteArray(Charsets.UTF_8))
            fresh += record.first
            seen[event.id] = record.first.position
            prev = record.first.hash
            positions += record.first.position
        }
        if (fresh.isEmpty()) return Outcome.success(positions)
        return try {
            write(bytes.toByteArray())
            for (r in fresh) {
                records += r
                positionOf[r.event.id] = r.position
            }
            rotateIfFull()
            Outcome.success(positions)
        } catch (e: IOException) {
            broken = true
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "пачка записей не подтверждена", e, path))
        }
    }

    companion object {
        const val FORMAT = "dpai.journal-record"
        const val VERSION = 1
        val GENESIS: ContentHash = Hashing.sha256("dpai.journal genesis")

        /** Без сегментов: журнал запуска агента короткий и читается другим процессом ([readCommitted]). */
        const val NO_SEGMENTS: Int = Int.MAX_VALUE

        private const val SEGMENT_MARK = ".seg-"

        private fun segmentName(path: String, first: Long) = "$path$SEGMENT_MARK${first.toString().padStart(12, '0')}"

        private fun firstOf(name: String): Long = name.substringAfterLast(SEGMENT_MARK).toLong()

        fun open(vfs: Vfs, path: String, quarantineCorruption: Boolean = false, segmentSize: Int = NO_SEGMENTS): Outcome<EventJournal> {
            require(segmentSize > 0) { "размер сегмента — положительный" }
            val dir = Vfs.parentOf(path)
            val prefix = path.substringAfterLast('/') + SEGMENT_MARK
            val sealed = try {
                vfs.list(dir).filter { it.startsWith(prefix) && it.removePrefix(prefix).all(Char::isDigit) }.sorted().map { Vfs.join(dir, it) }
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "каталог журнала не прочитан", e, path))
            }.toMutableList()
            // Стык: последняя запись последнего запечатанного сегмента — начало цепочки активного.
            var offset = 0L
            var baseHash = GENESIS
            sealed.lastOrNull()?.let { last ->
                val bytes = try {
                    vfs.read(last)
                } catch (e: IOException) {
                    return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "сегмент журнала не прочитан", e, last))
                } ?: return Outcome.failure(DiagCode.JOURNAL_CORRUPTED, "запечатанный сегмент пропал", last)
                val scan = scan(bytes, null)
                val tail = scan.loaded.lastOrNull()
                if (scan.badLine != null || tail == null || scan.validEnd < bytes.size) {
                    return Outcome.failure(DiagCode.JOURNAL_CORRUPTED, "запечатанный сегмент испорчен или пуст", last)
                }
                offset = tail.position
                baseHash = tail.hash
            }
            val bytes = try {
                vfs.read(path)
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "журнал не прочитан", e, path))
            } ?: return Outcome.success(EventJournal(vfs, path, segmentSize, sealed, offset, baseHash, emptyList()))

            val scan = scan(bytes, offset + 1 to baseHash)
            val loaded = scan.loaded
            val validEnd = scan.validEnd
            val badLine = scan.badLine
            val linesAfterBad = scan.linesAfterBad
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
                        "отрезан неподтверждённый или испорченный хвост (${removed.size} байт) после записи №${offset + loaded.size}; сохранён в $q",
                        path,
                    )
                } catch (e: IOException) {
                    return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "хвост журнала не отрезан", e, path))
                }
            }
            return Outcome.success(EventJournal(vfs, path, segmentSize, sealed, offset, baseHash, loaded), diags)
        }

        /**
         * Подтверждённые записи без восстановления — для читателя, пока в
         * журнал пишет другой процесс (агент target, D-26): незаконченная
         * последняя строка — запись, которая ещё пишется, она пропускается, а
         * не отрезается. Испорченная запись, за которой есть целые, —
         * `JOURNAL_CORRUPTED`, как у [open]. Только для журналов без сегментов.
         */
        fun readCommitted(vfs: Vfs, path: String): Outcome<List<JournalRecord>> {
            val bytes = try {
                vfs.read(path)
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "журнал не прочитан", e, path))
            } ?: return Outcome.success(emptyList())
            val scan = scan(bytes, 1L to GENESIS)
            if (scan.badLine != null && scan.linesAfterBad > 0) {
                return Outcome.failure(DiagCode.JOURNAL_CORRUPTED, "запись №${scan.badLine} испорчена, за ней ещё ${scan.linesAfterBad}", path)
            }
            return Outcome.success(scan.loaded)
        }

        /**
         * Подтверждённые записи с номером события больше [afterSequence] и
         * номер последнего события (ревью A-13). Читатель — агент: каждый его
         * вызов — новый процесс, и полная сверка журнала длинного хода (20 МБ —
         * ~3 с на Redmi) шла на каждом опросе. Здесь — с конца: опорная запись
         * (номер не больше [afterSequence]) и цепочка хешей от неё до конца;
         * начало журнала сверено, когда его отдавали раньше. Что-то не так в
         * хвосте — полное чтение, как [readCommitted].
         */
        fun readCommittedAfter(vfs: Vfs, path: String, afterSequence: Long): Outcome<Pair<List<JournalRecord>, Long>> {
            fun full(): Outcome<Pair<List<JournalRecord>, Long>> = readCommitted(vfs, path).map { all ->
                all.filter { it.event.sequence > afterSequence } to (all.lastOrNull()?.event?.sequence ?: 0)
            }
            if (afterSequence <= 0) return full()
            val bytes = try {
                vfs.read(path)
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "журнал не прочитан", e, path))
            } ?: return Outcome.success(emptyList<JournalRecord>() to 0L)
            var lineEnd = lastIndexOf(bytes, '\n'.code.toByte(), bytes.size - 1)
            while (lineEnd >= 0) {
                val lineStart = lastIndexOf(bytes, '\n'.code.toByte(), lineEnd - 1) + 1
                val seq = eventSequence(bytes.copyOfRange(lineStart, lineEnd)) ?: return full()
                if (seq <= afterSequence) {
                    val scan = scan(bytes.copyOfRange(lineStart, bytes.size), null)
                    if (scan.badLine != null) return full()
                    return Outcome.success(scan.loaded.filter { it.event.sequence > afterSequence } to (scan.loaded.lastOrNull()?.event?.sequence ?: seq))
                }
                lineEnd = lineStart - 1
            }
            return full()
        }

        private fun eventSequence(line: ByteArray): Long? {
            val value = JsonParser().parse(line.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject ?: return null
            return ((value["event"] as? JsonObject)?.get("sequence") as? JsonNumber)?.toLongExact()
        }

        private fun lastIndexOf(bytes: ByteArray, b: Byte, from: Int): Int {
            for (i in from downTo 0) if (bytes[i] == b) return i
            return -1
        }

        private class Scan(val loaded: List<JournalRecord>, val validEnd: Int, val badLine: Int?, val linesAfterBad: Int)

        /**
         * Записи файла по порядку. [start] — ожидаемые номер и хеш перед первой
         * записью; `null` — взять их из первой записи (запечатанный сегмент,
         * начало которого проверялось, пока он был активным).
         */
        private fun scan(bytes: ByteArray, start: Pair<Long, ContentHash>?): Scan {
            val loaded = ArrayList<JournalRecord>()
            var validEnd = 0 // байт после последней целой записи
            var from = 0
            var badLine: Int? = null
            var linesAfterBad = 0
            var lineNo = 0
            while (from < bytes.size) {
                val nl = indexOf(bytes, '\n'.code.toByte(), from)
                if (nl < 0) break // оборванная строка без перевода строки — хвост
                lineNo++
                if (badLine == null) {
                    val line = bytes.copyOfRange(from, nl)
                    val expected = loaded.lastOrNull()?.let { it.position + 1 to it.hash } ?: start ?: firstHeader(line)
                    val rec = expected?.let { parseRecord(line, it.first, it.second) }
                    if (rec != null) {
                        loaded += rec
                        validEnd = nl + 1
                    } else {
                        badLine = lineNo
                    }
                } else {
                    linesAfterBad++
                }
                from = nl + 1
            }
            return Scan(loaded, validEnd, badLine, linesAfterBad)
        }

        /** Номер и хеш предыдущей из самой записи — для начала запечатанного сегмента. */
        private fun firstHeader(line: ByteArray): Pair<Long, ContentHash>? {
            val value = JsonParser().parse(line.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject ?: return null
            val position = (value["position"] as? JsonNumber)?.toLongExact() ?: return null
            val prev = (value["prev"] as? JsonString)?.value?.let { ContentHash.parse(it).valueOrNull() } ?: return null
            return position to prev
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
