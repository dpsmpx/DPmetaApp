package com.dpsmpx.dpai.sync

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonPath
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Файл копии: путь от каталога контура и содержимое. */
class CopyEntry(val path: String, val bytes: ByteArray)

/** Что за копия: когда и с какого устройства, сколько файлов и байт. */
data class CopyInfo(val createdAt: Timestamp, val device: String, val files: Int, val bytes: Long)

/**
 * Архив копии (D-107): оглавление `dpai.cloud-archive` v1 (пути, размеры,
 * SHA-256) и файлы подряд. Только пути внутри разрешённых корней
 * ([ROOTS]): копия, даже расшифрованная, не положит файл вне каталога
 * контура и не заменит то, чего в копии быть не должно (T20, T14).
 */
object CloudArchive {
    const val FORMAT = "dpai.cloud-archive"
    const val VERSION = 1

    /** Корни копии в каталоге контура: каталог контура, документы приложения, известные ключи хостов. */
    val ROOTS = listOf("live", "docs", "known_hosts")

    /** Документы приложения, которые в копию не идут: политику прогона включает только человек (D-106). */
    private const val DOCS = "docs"
    private const val EXCLUDED = "$DOCS/auto-policy"
    private const val QUARANTINE = "live/quarantine-confirmed"
    private const val JOURNAL_COPIES = "live/journal-copies/"

    const val MAX_FILES = 100_000
    const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_MANIFEST = 32 * 1024 * 1024
    private val DEVICE = Regex("[A-Za-z0-9_-]{1,40}")

    /** Что не так с путём файла копии; `null` — годится. */
    fun pathProblem(path: String): String? = when {
        path.any { it < ' ' || it == '\u007f' } -> "путь с управляющими знаками"
        !Vfs.isSafeRelative(path) -> "путь «$path» — не относительный или с `..`"
        !(path == "known_hosts" || path.startsWith("live/") || path.startsWith("$DOCS/")) -> "путь «$path» — вне разрешённых каталогов копии"
        // Сам документ и его прежняя и временная версии (`.prev`, `.tmp` DocumentStore).
        path == EXCLUDED || path.startsWith("$EXCLUDED.") -> "файл «$path» в копию не идёт"
        // Одноразовое «да» человека карантину журнала (D-24) — только этому телефону и этой сборке.
        path == QUARANTINE -> "файл «$path» в копию не идёт"
        else -> null
    }

    /**
     * Брать ли файл каталога контура в копию: временные файлы, вытесненное
     * прежними восстановлениями и копии сегментов журнала (D-95: запас на
     * порчу носителя, а не данные — копия удвоила бы журнал) — нет.
     */
    fun wanted(path: String): Boolean =
        pathProblem(path) == null && !path.endsWith(".tmp") && ".replaced-" !in path && !path.startsWith(JOURNAL_COPIES)

    fun deviceProblem(device: String): String? = if (DEVICE.matches(device)) null else "имя устройства — латиница, цифры, «-» и «_», до 40 знаков"

    fun pack(entries: List<CopyEntry>, createdAt: Timestamp, device: String): Outcome<ByteArray> {
        deviceProblem(device)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, FORMAT) }
        if (entries.size > MAX_FILES) return Outcome.failure(DiagCode.VALUE_INVALID, "файлов больше $MAX_FILES", FORMAT)
        val sorted = entries.sortedBy { it.path }
        sorted.firstNotNullOfOrNull { pathProblem(it.path) }?.let { return Outcome.failure(DiagCode.VALUE_INVALID, it, FORMAT) }
        if (sorted.zipWithNext().any { (a, b) -> a.path == b.path }) return Outcome.failure(DiagCode.VALUE_INVALID, "файл дважды", FORMAT)
        val total = sorted.sumOf { it.bytes.size.toLong() }
        if (total > MAX_BYTES) return Outcome.failure(DiagCode.VALUE_INVALID, "копия больше ${MAX_BYTES / 1024 / 1024} МиБ", FORMAT)
        val manifest = CanonicalJson.bytes(
            JsonObject(
                linkedMapOf(
                    *FormatHeader.write(FORMAT, VERSION),
                    "createdAt" to JsonNumber.of(createdAt.epochMillis),
                    "device" to JsonString(device),
                    "files" to JsonArray(
                        sorted.map { e ->
                            JsonObject.of("path" to JsonString(e.path), "size" to JsonNumber.of(e.bytes.size.toLong()), "sha256" to JsonString(Hashing.sha256(e.bytes).hex))
                        },
                    ),
                ),
            ),
        )
        val out = ByteArrayOutputStream(manifest.size + 4 + total.toInt())
        out.write(ByteBuffer.allocate(4).putInt(manifest.size).array())
        out.write(manifest)
        sorted.forEach { out.write(it.bytes) }
        return Outcome.success(out.toByteArray())
    }

    /**
     * Разобрать расшифрованную копию: оглавление строгое (D-09), каждый путь
     * — по [pathProblem], размеры сходятся до байта, хеш каждого файла — по
     * оглавлению. Любое расхождение — отказ всей копии, а не её части.
     */
    fun unpack(bytes: ByteArray): Outcome<Pair<CopyInfo, List<CopyEntry>>> {
        if (bytes.size < 4) return invalid("копия короче оглавления")
        val len = ByteBuffer.wrap(bytes, 0, 4).int
        if (len !in 2..MAX_MANIFEST || 4 + len > bytes.size) return invalid("оглавление копии повреждено")
        val d = DiagnosticCollector()
        val f = JsonParser().parse(bytes.copyOfRange(4, 4 + len).toString(Charsets.UTF_8)).valueOrNull()?.let { JsonFields.root(it, d) }
            ?: return invalid("оглавление копии не JSON")
        FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return Outcome.Failure(d.all)
        val createdAt = f.long("createdAt")
        val device = f.string("device")
        val items = f.array("files")?.items.orEmpty()
        f.finish()
        if (d.hasErrors || createdAt == null || device == null) return Outcome.Failure(d.all.ifEmpty { listOf(com.dpsmpx.dpai.core.diagnostics.Diagnostic.of(DiagCode.BACKUP_INVALID, "оглавление неполно", FORMAT)) })
        deviceProblem(device)?.let { return invalid(it) }
        if (items.size > MAX_FILES) return invalid("файлов больше $MAX_FILES")
        var at = 4L + len
        val out = ArrayList<CopyEntry>(items.size)
        val seen = HashSet<String>()
        for ((i, v) in items.withIndex()) {
            val path = JsonPath.index("$.files", i)
            val ff = (v as? JsonObject)?.let { JsonFields(it, path, d) } ?: return invalid("файл $i — не объект")
            val p = ff.string("path")
            val size = ff.long("size")
            val sha = ff.string("sha256")
            ff.finish()
            if (d.hasErrors || p == null || size == null || sha == null) return Outcome.Failure(d.all)
            pathProblem(p)?.let { return invalid(it) }
            if (!seen.add(p)) return invalid("файл «$p» дважды")
            if (size < 0 || at + size > bytes.size) return invalid("размер «$p» не сходится с копией")
            val body = bytes.copyOfRange(at.toInt(), (at + size).toInt())
            if (Hashing.sha256(body).hex != sha) return Outcome.failure(DiagCode.HASH_MISMATCH, "файл «$p» не сходится с оглавлением", FORMAT)
            out += CopyEntry(p, body)
            at += size
        }
        if (at != bytes.size.toLong()) return invalid("после файлов копии — лишние байты")
        return Outcome.success(CopyInfo(Timestamp(createdAt), device, out.size, at - 4 - len) to out)
    }

    private fun invalid(why: String): Outcome<Nothing> = Outcome.failure(DiagCode.BACKUP_INVALID, why, FORMAT)
}
