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
    /** Каталог копий запечатанных сегментов (D-95); `null` — без копий. */
    private val copies: String?,
    private val sealed: MutableList<String>,
    private var offset: Long,
    private var baseHash: ContentHash,
    loaded: List<JournalRecord>,
) {
    private val records = ArrayList(loaded)
    private val positionOf = HashMap<EventId, Long>().apply { loaded.forEach { put(it.event.id, it.position) } }
    private var broken = false

    /**
     * Сегмент восстановлен из копии или копия не записалась (D-95) — для
     * журнала приложения (D-43). Зовётся потоком, который читал или писал.
     */
    @Volatile
    var onRepair: (Diagnostic) -> Unit = {}

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

    fun hashAt(position: Long): ContentHash? {
        synchronized(this) {
            when {
                position == 0L -> return GENESIS
                position == offset -> return baseHash
                position > offset -> return records.getOrNull((position - offset - 1).toInt())?.hash
            }
        }
        var found: ContentHash? = null
        forEachRecord(position) { r -> if (r.position == position) found = r.hash; false }
        return found
    }

    /**
     * Сегменты, начало активного и его записи — копией под монитором (аудит
     * стабильности 2 ноября 2026, A-9): журнал дописывают поток такта и
     * `accept` (D-94), а читают экраны — без копии чтение шло по списку,
     * который в этот миг рос или очищался ротацией.
     */
    @Synchronized
    private fun view(): Triple<List<String>, Long, List<JournalRecord>> = Triple(sealed.toList(), offset, ArrayList(records))

    /**
     * Записи с номера [from] включительно — списком. Для чтения всей истории
     * длинного журнала — [forEachRecord]: список держит её в памяти.
     */
    fun records(from: Long = 1): List<JournalRecord> {
        synchronized(this) { if (from > offset) return records.drop((from - offset - 1).coerceAtLeast(0).toInt()) }
        val out = ArrayList<JournalRecord>()
        forEachRecord(from) { out += it; true }
        return out
    }

    /**
     * Записи с номера [from] по порядку, по одному сегменту в памяти;
     * [body] возвращает `false`, чтобы остановиться. Запечатанный сегмент,
     * который не читается, — [JournalSegmentUnreadable]: его записи когда-то
     * были подтверждены (D-24), молча их пропустить нельзя, а оболочка
     * называет причину словами (аудит надёжности, A-6).
     */
    fun forEachRecord(from: Long = 1, body: (JournalRecord) -> Boolean) {
        val (names, active, records) = view()
        for ((i, name) in names.withIndex()) {
            val nextFirst = names.getOrNull(i + 1)?.let(::firstOf) ?: (active + 1)
            if (nextFirst <= from) continue
            val read = vfs.read(name)
            val own = read?.let { scan(it, null) }
            // Пропал или испорчен — из копии, если она сходится (D-95); нет — подтверждённые записи потеряны.
            val parsed = if (read == null || own == null || own.badLine != null || own.validEnd < read.size) {
                val copy = copies?.let { restoreFromCopy(vfs, it, name, nextFirst) }
                    ?: throw JournalSegmentUnreadable(if (read == null) "запечатанный сегмент $name пропал" else "запечатанный сегмент $name испорчен (запись №${own?.badLine})")
                onRepair(restored(name))
                scan(copy, null)
            } else {
                keepCopy(name, read)
                own
            }
            // Стык с началом следующего: разрыв — пропавший между ними сегмент, а не короткая история.
            val last = parsed.loaded.lastOrNull()?.position ?: (firstOf(name) - 1)
            if (last + 1 != nextFirst) throw JournalSegmentUnreadable("пропали записи №${last + 1}…${nextFirst - 1} после сегмента $name")
            for (r in parsed.loaded) if (r.position >= from && !body(r)) return
        }
        for (r in records) if (r.position >= from && !body(r)) return
    }

    /** Копия запечатанного сегмента, если её ещё нет (D-95); не записалась — сказать, а не молчать. */
    private fun keepCopy(name: String, bytes: ByteArray) {
        val dir = copies ?: return
        val copy = copyOf(dir, name)
        try {
            if (vfs.exists(copy)) return
            vfs.mkdirs(dir)
            vfs.write(copy, bytes)
            vfs.fsync(copy)
            vfs.fsyncDir(dir)
        } catch (e: IOException) {
            onRepair(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "копия сегмента журнала не записана (D-95)", e, copy))
        }
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
        // Копия запечатанного — один раз, он больше не меняется (D-95).
        if (copies != null) runCatching { vfs.read(name) }.getOrNull()?.let { keepCopy(name, it) }
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
        val line = record.second.toByteArray(Charsets.UTF_8)
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
            bytes.write(record.second.toByteArray(Charsets.UTF_8))
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

        /** Каталог копий запечатанных сегментов рядом с журналом (D-95). */
        const val COPIES = "journal-copies"

        private fun copyOf(copies: String, name: String) = Vfs.join(copies, name.substringAfterLast('/'))

        private fun restored(name: String) =
            Diagnostic.warning(DiagCode.JOURNAL_SEGMENT_RESTORED, "запечатанный сегмент пропал или испорчен — восстановлен из копии (D-95)", name)

        /**
         * Вернуть сегмент [name] из копии, если она годна (D-95): читается
         * целиком, записи — по цепочке хешей, номера — с начала сегмента и
         * до [nextFirst] (известен — стык проверяется). Годна — записана на
         * место с `fsync`; итог — её байты, иначе `null`.
         */
        private fun restoreFromCopy(vfs: Vfs, copies: String, name: String, nextFirst: Long?): ByteArray? {
            val bytes = runCatching { vfs.read(copyOf(copies, name)) }.getOrNull() ?: return null
            val scan = scan(bytes, null)
            val first = scan.loaded.firstOrNull() ?: return null
            if (scan.badLine != null || scan.validEnd < bytes.size || first.position != firstOf(name)) return null
            if (nextFirst != null && scan.loaded.last().position + 1 != nextFirst) return null
            return try {
                vfs.write(name, bytes)
                vfs.fsync(name)
                vfs.fsyncDir(Vfs.parentOf(name))
                bytes
            } catch (_: IOException) {
                null
            }
        }

        /**
         * [keepCopies] — копии запечатанных сегментов в каталоге [COPIES]
         * рядом с журналом и восстановление из них (D-95): журнал контроллера.
         */
        fun open(vfs: Vfs, path: String, quarantineCorruption: Boolean = false, segmentSize: Int = NO_SEGMENTS, keepCopies: Boolean = false): Outcome<EventJournal> {
            require(segmentSize > 0) { "размер сегмента — положительный" }
            val dir = Vfs.parentOf(path)
            val copiesDir = if (keepCopies) Vfs.join(dir, COPIES) else null
            val prefix = path.substringAfterLast('/') + SEGMENT_MARK
            fun segments(d: String) = vfs.list(d).filter { it.startsWith(prefix) && it.removePrefix(prefix).all(Char::isDigit) }.sorted()
            val sealed = try {
                segments(dir).map { Vfs.join(dir, it) }
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "каталог журнала не прочитан", e, path))
            }.toMutableList()
            val repairs = mutableListOf<Diagnostic>()
            // Копия есть, а сегмента нет — он пропал: сегменты не удаляются намеренно (D-95).
            if (copiesDir != null) {
                val lost = runCatching { segments(copiesDir) }.getOrDefault(emptyList()).map { Vfs.join(dir, it) }.filter { it !in sealed }
                for (name in lost) {
                    val next = (sealed + lost).filter { firstOf(it) > firstOf(name) }.minOfOrNull(::firstOf)
                    if (restoreFromCopy(vfs, copiesDir, name, next) != null) {
                        sealed += name
                        repairs += restored(name)
                    }
                }
                sealed.sortBy(::firstOf)
            }
            // История начинается с записи №1: первый сегмент пропал — подтверждённые записи потеряны,
            // а не «журнал короче» (D-24; аудит надёжности, A-6). Сегменты не удаляются намеренно.
            sealed.firstOrNull()?.takeIf { firstOf(it) != 1L }?.let { first ->
                return Outcome.failure(DiagCode.JOURNAL_CORRUPTED, "пропал запечатанный сегмент с записями №1…${firstOf(first) - 1}", first)
            }
            // Стык: последняя запись последнего запечатанного сегмента — начало цепочки активного.
            var offset = 0L
            var baseHash = GENESIS
            sealed.lastOrNull()?.let { last ->
                val read = try {
                    vfs.read(last)
                } catch (e: IOException) {
                    return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "сегмент журнала не прочитан", e, last))
                }
                val own = read?.let { scan(it, null) }
                val parsed = if (read == null || own == null || own.badLine != null || own.loaded.isEmpty() || own.validEnd < read.size) {
                    // Испорчен или пуст — из копии (D-95); нет годной копии — как прежде, отказ.
                    val copy = copiesDir?.let { restoreFromCopy(vfs, it, last, null) }
                        ?: return Outcome.failure(DiagCode.JOURNAL_CORRUPTED, if (read == null) "запечатанный сегмент пропал" else "запечатанный сегмент испорчен или пуст", last)
                    repairs += restored(last)
                    scan(copy, null)
                } else {
                    own
                }
                val tail = parsed.loaded.last()
                offset = tail.position
                baseHash = tail.hash
            }
            val bytes = try {
                vfs.read(path)
            } catch (e: IOException) {
                return Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "журнал не прочитан", e, path))
            } ?: return Outcome.success(EventJournal(vfs, path, segmentSize, copiesDir, sealed, offset, baseHash, emptyList()), repairs)

            val scan = scan(bytes, offset + 1 to baseHash)
            val loaded = scan.loaded
            val validEnd = scan.validEnd
            val badLine = scan.badLine
            val linesAfterBad = scan.linesAfterBad
            val diags = repairs.toMutableList()
            // Первая запись активного цела сама по себе, но не стыкуется с последним сегментом: между ними пропал
            // сегмент. Это не порча хвоста — карантин отрезал бы целый активный файл (A-6), поэтому отказ и при
            // подтверждённом карантине.
            if (badLine == 1) {
                val own = scan(bytes, null).loaded.firstOrNull()?.position
                if (own != null && own > offset + 1) {
                    return Outcome.failure(DiagCode.JOURNAL_CORRUPTED, "пропал запечатанный сегмент с записями №${offset + 1}…${own - 1}", segmentName(path, offset + 1))
                }
            }
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
            val journal = EventJournal(vfs, path, segmentSize, copiesDir, sealed, offset, baseHash, loaded)
            // Сегменты, запечатанные до копий (D-95), — копия один раз, если сегмент цел.
            if (copiesDir != null) {
                for (name in sealed) {
                    if (runCatching { vfs.exists(copyOf(copiesDir, name)) }.getOrDefault(true)) continue
                    val b = runCatching { vfs.read(name) }.getOrNull() ?: continue
                    val sc = scan(b, null)
                    if (sc.badLine == null && sc.validEnd == b.size && sc.loaded.isNotEmpty()) journal.keepCopy(name, b)
                }
            }
            return Outcome.success(journal, diags)
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

        /**
         * Каноническая строка схемы v1, в том же порядке полей, что у общего кодека.
         * Событие кодируется один раз для хеша и строки. FORMAT, версия и хеши —
         * безопасные ASCII, позиция — Long; пользовательские данные кодирует CanonicalJson.
         * Побайтовую совместимость проверяет EventJournalTest по общему кодеку.
         */
        private fun build(position: Long, prev: ContentHash, event: EventEnvelope): Pair<JournalRecord, String> {
            val encodedEvent = CanonicalJson.write(event.toJson())
            val head = "{\"event\":$encodedEvent,\"format\":\"$FORMAT\","
            val tail = "\"position\":$position,\"prev\":\"$prev\",\"version\":$VERSION}"
            val hash = Hashing.sha256(head + tail)
            val line = buildString(head.length + tail.length + 96) {
                append(head); append("\"hash\":\""); append(hash); append("\","); append(tail); append('\n')
            }
            return JournalRecord(position, event, hash) to line
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

/**
 * Запечатанный сегмент журнала пропал или испорчен (D-24): его записи были
 * подтверждены, пропустить их нельзя, а отрезать хвост — не поможет: сегмент
 * не в хвосте. Отдельный тип — чтобы оболочка сказала это человеку, а не
 * показала имя исключения.
 */
class JournalSegmentUnreadable(message: String) : IllegalStateException(message)
