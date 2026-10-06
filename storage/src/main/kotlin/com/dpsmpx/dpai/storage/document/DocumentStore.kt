package com.dpsmpx.dpai.storage.document

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.diagnostics.Severity
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.migration.Migrator
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

/** Прочитанный документ; [fromLastKnownGood] — основная версия была испорчена или недописана. */
data class StoredDocument(val content: JsonObject, val hash: ContentHash, val fromLastKnownGood: Boolean)

/**
 * Документы «ключ → JSON» с атомарной заменой и last known good (D-24;
 * ТЗ, §5.1, §71).
 *
 * Запись: временный файл → fsync → прежняя целая версия становится `.prev`
 * → временный файл становится основным → fsync каталога. Прерванная в
 * любой точке запись оставляет прежнюю или новую версию, но не смесь
 * (`DocumentStoreTest`). Каждая версия хранится в конверте `dpai.stored`
 * с хешем содержимого: испорченный файл не читается как верный.
 *
 * Чтение: основная версия, если цела; иначе — дописанная, но не
 * переименованная новая (`.tmp`, если цела); иначе — прежняя (`.prev`) с
 * предупреждением `STORAGE_RECOVERED_LKG`. Ничего целого, хотя что-то
 * было, — `STORAGE_CORRUPTED`.
 */
class DocumentStore(private val vfs: Vfs, private val dir: String) {
    fun keys(): List<String> = try {
        vfs.list(dir).filter { KEY.matches(it) }
    } catch (_: IOException) {
        emptyList()
    }

    fun write(key: String, content: JsonObject): Outcome<ContentHash> {
        checkKey(key)?.let { return it }
        val (bytes, hash) = encode(content)
        val main = Vfs.join(dir, key)
        val tmp = "$main$TMP"
        return try {
            vfs.mkdirs(dir)
            vfs.write(tmp, bytes)
            vfs.fsync(tmp)
            // Испорченную основную версию не делаем «прежней»: last known good остаётся целым.
            if (vfs.exists(main) && decode(vfs.read(main)) != null) vfs.rename(main, "$main$PREV")
            vfs.rename(tmp, main)
            vfs.fsyncDir(dir)
            Outcome.success(hash)
        } catch (e: IOException) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "запись документа не завершена", e, key))
        }
    }

    fun read(key: String): Outcome<StoredDocument?> {
        checkKey(key)?.let { return it }
        val main = Vfs.join(dir, key)
        return try {
            val candidates = listOf(main, "$main$TMP", "$main$PREV")
            var sawAny = false
            for ((i, c) in candidates.withIndex()) {
                val bytes = vfs.read(c) ?: continue
                sawAny = true
                val doc = decode(bytes) ?: continue
                if (i == 0) return Outcome.success(StoredDocument(doc.first, doc.second, fromLastKnownGood = false))
                val warn = Diagnostic.warning(
                    DiagCode.STORAGE_RECOVERED_LKG,
                    if (c.endsWith(PREV)) "основная версия испорчена или недописана — прочитана прежняя" else "прочитана дописанная, но не переименованная версия",
                    key,
                )
                return Outcome.success(StoredDocument(doc.first, doc.second, fromLastKnownGood = c.endsWith(PREV)), listOf(warn))
            }
            if (sawAny) Outcome.failure(DiagCode.STORAGE_CORRUPTED, "ни одна версия документа не цела", key) else Outcome.success(null)
        } catch (e: IOException) {
            Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "чтение документа не удалось", e, key))
        }
    }

    /**
     * Чтение с миграцией до текущей версии формата (ТЗ, §62): старый документ
     * переводится по цепочке, проверяется и атомарно заменяется; прежняя
     * версия остаётся `.prev` — точкой отката.
     */
    fun readMigrated(key: String, migrator: Migrator): Outcome<StoredDocument?> {
        val read = read(key)
        val doc = (read as? Outcome.Success)?.value ?: return read
        return when (val m = migrator.migrate(doc.content)) {
            is Outcome.Failure -> Outcome.Failure(read.diagnostics + m.diagnostics)
            is Outcome.Success ->
                if (m.value.applied.isEmpty()) {
                    read
                } else {
                    when (val w = write(key, m.value.document)) {
                        is Outcome.Failure -> Outcome.Failure(read.diagnostics + m.diagnostics + w.diagnostics)
                        is Outcome.Success -> Outcome.success(
                            StoredDocument(m.value.document, w.value, doc.fromLastKnownGood),
                            read.diagnostics + m.diagnostics,
                        )
                    }
                }
        }
    }

    private fun checkKey(key: String): Outcome.Failure? =
        if (KEY.matches(key)) null else Outcome.failure(DiagCode.VALUE_INVALID, "неверный ключ документа", key)

    companion object {
        const val FORMAT = "dpai.stored"
        const val VERSION = 1
        private const val TMP = ".tmp"
        private const val PREV = ".prev"
        private val KEY = Regex("[a-z0-9][a-z0-9_-]{0,63}")

        internal fun encode(content: JsonObject): Pair<ByteArray, ContentHash> {
            val hash = Hashing.sha256(content)
            val envelope = JsonObject(
                linkedMapOf(*FormatHeader.write(FORMAT, VERSION), "sha256" to JsonString(hash.toString()), "content" to content),
            )
            return (CanonicalJson.write(envelope) + "\n").toByteArray(Charsets.UTF_8) to hash
        }

        /** Конверт цел: формат, версия, хеш содержимого сходятся. Иначе — `null`. */
        internal fun decode(bytes: ByteArray?): Pair<JsonObject, ContentHash>? {
            if (bytes == null) return null
            val value = JsonParser().parse(bytes.toString(Charsets.UTF_8)).valueOrNull() ?: return null
            val diags = DiagnosticCollector()
            val f = JsonFields.root(value, diags) ?: return null
            FormatHeader.read(f, FORMAT, VERSION..VERSION, diags) ?: return null
            val declared = f.string("sha256")?.let { ContentHash.parse(it).valueOrNull() }
            val content = f.raw("content") as? JsonObject
            f.finish()
            if (diags.all.any { it.severity == Severity.ERROR } || declared == null || content == null) return null
            return if (Hashing.sha256(content) == declared) content to declared else null
        }
    }
}
