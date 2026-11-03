package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.ids.CorrelationId
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/**
 * Связь папки автора с её копией в Linux (D-99): [tree] — адрес папки
 * системного окна (`content://…`), [dir] — каталог копии в
 * `~/files/`, [title] — имя папки для экрана, [lastSync] — время
 * последней синхронизации (мс), [base] — сведения о файлах после неё.
 */
data class FileLink(
    val id: CorrelationId,
    val title: String,
    val tree: String,
    val dir: String,
    val lastSync: Long?,
    val base: Map<String, BaseEntry>,
)

/** Связи папок — документ `dpai.file-links` v1 (`docs/DATA_MODEL.md`). */
object FileLinkBook {
    const val FORMAT = "dpai.file-links"
    const val VERSION = 1
    const val MAX_LINKS = 32

    private val SHA = Regex("[0-9a-f]{64}")

    fun toJson(links: List<FileLink>): JsonObject = JsonObject(
        linkedMapOf(
            *FormatHeader.write(FORMAT, VERSION),
            "links" to JsonArray(
                links.map { l ->
                    JsonObject(
                        linkedMapOf<String, JsonValue>(
                            "id" to JsonString(l.id.value),
                            "title" to JsonString(l.title),
                            "tree" to JsonString(l.tree),
                            "dir" to JsonString(l.dir),
                            "files" to JsonArray(
                                l.base.toSortedMap().map { (path, b) ->
                                    JsonObject.of(
                                        "path" to JsonString(path),
                                        "sha256" to JsonString(b.sha256),
                                        "remoteSize" to JsonNumber.of(b.remote.size),
                                        "remoteModified" to JsonNumber.of(b.remote.modified),
                                        "localSize" to JsonNumber.of(b.local.size),
                                        "localModified" to JsonNumber.of(b.local.modified),
                                    )
                                },
                            ),
                        ).also { m -> l.lastSync?.let { m["lastSync"] = JsonNumber.of(it) } },
                    )
                },
            ),
        ),
    )

    /** Чтение строгое (D-09): находка — отказ с путём; база с чужим путём или хешем не читается. */
    fun fromJson(value: JsonValue): Outcome<List<FileLink>> {
        val d = DiagnosticCollector()
        val f = JsonFields.root(value, d) ?: return Outcome.Failure(d.all)
        FormatHeader.read(f, FORMAT, 1..VERSION, d) ?: return Outcome.Failure(d.all)
        val items = f.array("links")?.items.orEmpty()
        f.finish()
        if (items.size > MAX_LINKS) d.error(DiagCode.VALUE_INVALID, "связей больше $MAX_LINKS", "$.links")
        val out = items.take(MAX_LINKS).mapIndexedNotNull { i, item ->
            val at = "$.links[$i]"
            val obj = item as? JsonObject ?: return@mapIndexedNotNull null.also { d.error(DiagCode.FIELD_TYPE, "ожидался объект", at) }
            val l = JsonFields(obj, at, d)
            val id = l.string("id")?.let { IdKinds.CORRELATION.parse(it).valueOrNull() }
            val title = l.string("title")
            val tree = l.string("tree")
            val dir = l.string("dir")
            val last = l.optLong("lastSync")
            val files = l.array("files")?.items.orEmpty()
            l.finish()
            if (files.size > FolderSync.MAX_FILES) d.error(DiagCode.VALUE_INVALID, "файлов больше ${FolderSync.MAX_FILES}", "$at.files")
            val base = LinkedHashMap<String, BaseEntry>()
            files.take(FolderSync.MAX_FILES).forEachIndexed { j, e ->
                val fat = "$at.files[$j]"
                val fo = e as? JsonObject ?: return@forEachIndexed d.error(DiagCode.FIELD_TYPE, "ожидался объект", fat)
                val x = JsonFields(fo, fat, d)
                val path = x.string("path")
                val sha = x.string("sha256")
                val rs = x.long("remoteSize")
                val rm = x.long("remoteModified")
                val ls = x.long("localSize")
                val lm = x.long("localModified")
                x.finish()
                if (path == null || !FileNames.pathValid(path) || sha == null || !SHA.matches(sha) || rs == null || rm == null || ls == null || lm == null) {
                    d.error(DiagCode.VALUE_INVALID, "сведения о файле неполны или путь недопустим", fat)
                } else {
                    base[path] = BaseEntry(sha, FileMeta(rs, rm), FileMeta(ls, lm))
                }
            }
            if (id == null || title == null || tree == null || !tree.startsWith("content://") || dir == null || !FileNames.segmentValid(dir)) {
                d.error(DiagCode.VALUE_INVALID, "связь неполна: id, папка content:// или каталог копии", at)
                null
            } else {
                FileLink(id, title, tree, dir, last, base)
            }
        }
        out.groupBy { it.dir }.filterValues { it.size > 1 }.keys.forEach { d.error(DiagCode.VALUE_INVALID, "каталог копии повторяется: $it", "$.links") }
        return if (d.hasErrors) Outcome.Failure(d.all) else Outcome.success(out)
    }
}
