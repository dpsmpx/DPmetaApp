package com.dpsmpx.dpai.storage.backup

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException

data class BackupFile(val path: String, val size: Long, val hash: ContentHash)

/** Манифест копии (ТЗ, §60): версия, время, версия схемы, файлы, хеш манифеста. */
data class BackupManifest(val createdAt: Timestamp, val schemaVersion: Int, val files: List<BackupFile>, val hash: ContentHash)

/**
 * Резервные копии каталога данных (D-25; ТЗ, §59–§61).
 *
 * Копия — файлы данных как есть и манифест, который пишется последним:
 * копия без манифеста — незавершённая и не применяется. Восстановление
 * применяет только копию, у которой сошлись манифест и каждый файл;
 * прерванное восстановление довершается [resumeIfInterrupted] по маркеру
 * `restore.pending`. Прежние файлы не удаляются, а переименовываются в
 * `<имя>.replaced` (ТЗ, §5.8: исходники сохраняются).
 */
class BackupService(private val vfs: Vfs, private val clock: Clock) {
    fun create(dataDir: String, backupDir: String, schemaVersion: Int): Outcome<BackupManifest> = io("копия не создана", backupDir) {
        if (vfs.exists(Vfs.join(backupDir, MANIFEST))) {
            return@io Outcome.failure(DiagCode.VALUE_INVALID, "в каталоге уже есть копия: перезапись запрещена", backupDir)
        }
        vfs.mkdirs(backupDir)
        val files = vfs.list(dataDir).filter(::isDataFile).map { name ->
            val bytes = vfs.read(Vfs.join(dataDir, name)) ?: ByteArray(0)
            val target = Vfs.join(backupDir, name)
            vfs.write(target, bytes)
            vfs.fsync(target)
            BackupFile(name, bytes.size.toLong(), Hashing.sha256(bytes))
        }
        val body = manifestBody(clock.now(), schemaVersion, files)
        val hash = Hashing.sha256(body)
        writeAtomically(backupDir, MANIFEST, withHash(body, hash))
        Outcome.success(BackupManifest(clock.now(), schemaVersion, files, hash))
    }

    /** Копия цела: манифест полон и сходится по хешу, каждый файл — по размеру и SHA-256. */
    fun verify(backupDir: String): Outcome<BackupManifest> = io("копия не прочитана", backupDir) {
        val raw = vfs.read(Vfs.join(backupDir, MANIFEST))
            ?: return@io Outcome.failure(DiagCode.BACKUP_INVALID, "нет манифеста: копия не завершена", backupDir)
        val manifest = parseManifest(raw) ?: return@io Outcome.failure(DiagCode.BACKUP_INVALID, "манифест испорчен", backupDir)
        val problems = DiagnosticCollector()
        for (f in manifest.files) {
            val bytes = vfs.read(Vfs.join(backupDir, f.path))
            when {
                bytes == null -> problems.error(DiagCode.BACKUP_INVALID, "файла нет: копия частичная", f.path)
                bytes.size.toLong() != f.size || Hashing.sha256(bytes) != f.hash ->
                    problems.error(DiagCode.BACKUP_INVALID, "файл не совпадает с манифестом", f.path)
            }
        }
        if (problems.hasErrors) Outcome.Failure(problems.all) else Outcome.success(manifest)
    }

    /** Применить годную копию к [dataDir]. Негодная — отказ, данные не тронуты. */
    fun restore(backupDir: String, dataDir: String): Outcome<BackupManifest> {
        val verified = verify(backupDir)
        val manifest = (verified as? Outcome.Success)?.value ?: return verified
        return io("восстановление прервано", dataDir) {
            vfs.mkdirs(dataDir)
            val marker = JsonObject(
                linkedMapOf(
                    *FormatHeader.write(MARKER_FORMAT, 1),
                    "backup" to JsonString(backupDir),
                    "manifest" to JsonString(manifest.hash.toString()),
                ),
            )
            writeAtomically(dataDir, MARKER, marker)
            apply(backupDir, dataDir, manifest)
            Outcome.success(manifest)
        }
    }

    /**
     * При запуске: прерванное восстановление довершается из той же копии
     * (`RESTORE_RESUMED`); копия больше не годна — отказ, каталог данных
     * не используется, пока пользователь не решит (`docs/RECOVERY.md`, §4).
     */
    fun resumeIfInterrupted(dataDir: String): Outcome<BackupManifest?> = io("восстановление не довершено", dataDir) {
        val raw = vfs.read(Vfs.join(dataDir, MARKER)) ?: return@io Outcome.success(null)
        val diags = DiagnosticCollector()
        val f = JsonParser().parse(raw.toString(Charsets.UTF_8)).valueOrNull()?.let { JsonFields.root(it, diags) }
        val backupDir = f?.string("backup")
        val expected = f?.string("manifest")
        if (backupDir == null || expected == null) {
            return@io Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "маркер восстановления испорчен: каталог данных не используется", dataDir)
        }
        when (val v = verify(backupDir)) {
            is Outcome.Failure -> Outcome.Failure(
                listOf(Diagnostic.of(DiagCode.STORAGE_UNAVAILABLE, "прерванное восстановление нельзя довершить", dataDir, causes = v.errors)),
            )
            is Outcome.Success -> {
                if (v.value.hash.toString() != expected) {
                    return@io Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "копия изменилась после начала восстановления", dataDir)
                }
                apply(backupDir, dataDir, v.value)
                Outcome.success(v.value, listOf(Diagnostic.warning(DiagCode.RESTORE_RESUMED, "прерванное восстановление довершено", dataDir)))
            }
        }
    }

    /** Шаги восстановления, которые можно повторять: итог один и тот же. */
    private fun apply(backupDir: String, dataDir: String, manifest: BackupManifest) {
        val wanted = manifest.files.associateBy { it.path }
        for (name in vfs.list(dataDir)) {
            val path = Vfs.join(dataDir, name)
            when {
                name == MARKER || name.endsWith(REPLACED) -> Unit
                name.endsWith(".tmp") -> vfs.delete(path)
                wanted[name]?.let { w -> vfs.read(path)?.let { Hashing.sha256(it) == w.hash } } == true -> Unit
                else -> vfs.rename(path, "$path$REPLACED")
            }
        }
        vfs.fsyncDir(dataDir)
        for (file in manifest.files) {
            val path = Vfs.join(dataDir, file.path)
            if (vfs.exists(path)) continue
            val bytes = vfs.read(Vfs.join(backupDir, file.path)) ?: throw IOException("файл копии пропал: ${file.path}")
            vfs.write("$path.tmp", bytes)
            vfs.fsync("$path.tmp")
            vfs.rename("$path.tmp", path)
        }
        vfs.fsyncDir(dataDir)
        vfs.delete(Vfs.join(dataDir, MARKER))
        vfs.fsyncDir(dataDir)
    }

    private fun writeAtomically(dir: String, name: String, json: JsonObject) {
        val path = Vfs.join(dir, name)
        vfs.write("$path.tmp", (CanonicalJson.write(json) + "\n").toByteArray(Charsets.UTF_8))
        vfs.fsync("$path.tmp")
        vfs.rename("$path.tmp", path)
        vfs.fsyncDir(dir)
    }

    private inline fun <T> io(message: String, subject: String, block: () -> Outcome<T>): Outcome<T> = try {
        block()
    } catch (e: IOException) {
        Outcome.failure(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, message, e, subject))
    }

    companion object {
        const val FORMAT = "dpai.backup-manifest"
        const val VERSION = 1
        const val MANIFEST = "manifest.json"
        const val MARKER = "restore.pending"
        private const val MARKER_FORMAT = "dpai.restore-marker"
        private const val REPLACED = ".replaced"

        /** В копию идут данные; временные файлы, прежние версии и вытесненное — нет. */
        fun isDataFile(name: String): Boolean =
            name != MARKER && !name.endsWith(".tmp") && !name.endsWith(".prev") && !name.endsWith(REPLACED)

        private fun manifestBody(createdAt: Timestamp, schemaVersion: Int, files: List<BackupFile>) = JsonObject(
            linkedMapOf(
                *FormatHeader.write(FORMAT, VERSION),
                "createdAt" to JsonNumber.of(createdAt.epochMillis),
                "schemaVersion" to JsonNumber.of(schemaVersion),
                "files" to JsonArray(
                    files.map {
                        JsonObject.of("path" to JsonString(it.path), "size" to JsonNumber.of(it.size), "sha256" to JsonString(it.hash.toString()))
                    },
                ),
            ),
        )

        private fun withHash(body: JsonObject, hash: ContentHash) =
            JsonObject(LinkedHashMap(body.fields).apply { put("hash", JsonString(hash.toString())) })

        private fun parseManifest(raw: ByteArray): BackupManifest? {
            val value = JsonParser().parse(raw.toString(Charsets.UTF_8)).valueOrNull() as? JsonObject ?: return null
            val diags = DiagnosticCollector()
            val f = JsonFields(value, "$", diags)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, diags) ?: return null
            val createdAt = f.long("createdAt")
            val schema = f.int("schemaVersion")
            val files = f.array("files")?.items?.mapIndexed { i, item ->
                val o = item as? JsonObject ?: return null
                val ff = JsonFields(o, "$.files[$i]", diags)
                val path = ff.string("path")
                val size = ff.long("size")
                val hash = ff.string("sha256")?.let { ContentHash.parse(it).valueOrNull() }
                ff.finish()
                if (path == null || size == null || hash == null || !Vfs.isSafeRelative(path) || '/' in path) return null
                BackupFile(path, size, hash)
            }
            val hash = f.string("hash")?.let { ContentHash.parse(it).valueOrNull() }
            f.finish()
            if (diags.hasErrors || createdAt == null || schema == null || files == null || hash == null) return null
            if (files.map { it.path }.toSet().size != files.size) return null
            if (Hashing.sha256(JsonObject(value.fields - "hash")) != hash) return null
            return BackupManifest(Timestamp(createdAt), schema, files, hash)
        }
    }
}
