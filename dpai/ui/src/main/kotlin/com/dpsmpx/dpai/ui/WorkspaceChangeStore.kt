package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.files.TextFile
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.Base64

data class WorkspaceBytes(val path: String, val before: ByteArray, val after: ByteArray, val beforeExists: Boolean = true, val afterExists: Boolean = true)
data class WorkspaceChangeCopy(val root: String, val files: List<WorkspaceBytes>, val pending: Boolean, val applied: Boolean)

/**
 * Зашифрованная копия до проектной записи и долговечная пауза (D-205).
 * Вызывающий сериализует операции замком проекта. В guard нет текста и путей;
 * обе его версии становятся pending до первой записи исходника.
 */
class WorkspaceChangeStore(private val vfs: Vfs, private val cipher: DraftCipher) {
    private val bounded = object : Vfs by vfs {
        override fun read(path: String): ByteArray? {
            if ((vfs.size(path) ?: 0) > 13L * 1024 * 1024) throw IOException("копия превышает предел")
            return vfs.read(path)
        }
    }
    private val guards = DocumentStore(bounded, "workspace-guards")
    private data class Guard(val current: String, val previous: String, val pending: Boolean, val applied: Boolean)
    private fun id(project: String) = Hashing.sha256(project).hex
    private fun copies(project: String) = DocumentStore(bounded, "workspace-copies/${id(project)}")
    private fun aad(project: String, version: Long = 2) = "dpai.workspace-change.v$version:$project"

    fun blocked(project: String): Boolean = when (val result = guard(project)) {
        is Outcome.Failure -> true
        is Outcome.Success -> result.value?.pending == true
    }

    fun begin(project: String, root: String, files: List<WorkspaceBytes>): Outcome<Unit> {
        val old = when (val result = guard(project)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        if (old?.pending == true) return Outcome.failure(DiagCode.STATE_CONFLICT, "сначала восстановите прерванную правку")
        if (project.length > 4096 || root.length > 16_384 || !valid(files)) return Outcome.failure(DiagCode.VALUE_INVALID, "недопустимая копия проектной правки")
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeUTF(project); data.writeUTF(root); data.writeInt(files.size)
            for (file in files) {
                data.writeUTF(file.path); data.writeBoolean(file.beforeExists); data.writeBoolean(file.afterExists)
                for (bytes in listOf(file.before, file.after)) { data.writeInt(bytes.size); data.write(bytes) }
            }
        }
        val sealed = when (val result = cipher.seal(aad(project), out.toByteArray())) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        if (sealed.size > WorkspaceEdits.MAX_BYTES + 1024 * 1024) return Outcome.failure(DiagCode.VALUE_INVALID, "зашифрованная копия превышает предел")
        val key = Hashing.sha256(sealed).hex
        val content = JsonObject.of("format" to JsonString(FORMAT), "version" to JsonNumber.of(2), "sealed" to JsonString(Base64.getEncoder().encodeToString(sealed)))
        when (val result = copies(project).write(key, content)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> Unit
        }
        return writeGuard(project, Guard(key, old?.current.orEmpty(), pending = true, applied = false))
    }

    fun load(project: String): Outcome<WorkspaceChangeCopy?> {
        val guard = when (val result = guard(project)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value ?: return Outcome.success(null)
        }
        val doc = when (val result = copies(project).read(guard.current)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value ?: return corrupt()
        }
        val version = (doc.content["version"] as? JsonNumber)?.toLongExact()
        if (doc.fromLastKnownGood || (doc.content["format"] as? JsonString)?.value != FORMAT || version !in setOf(1L, 2L)) return corrupt()
        val sealed = try { Base64.getDecoder().decode((doc.content["sealed"] as? JsonString)?.value ?: return corrupt()) }
            catch (_: IllegalArgumentException) { return corrupt() }
        if (Hashing.sha256(sealed).hex != guard.current) return corrupt()
        val plain = when (val result = cipher.open(aad(project, checkNotNull(version)), sealed)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value
        }
        return try {
            DataInputStream(ByteArrayInputStream(plain)).use { data ->
                if (data.readUTF() != project) return corrupt()
                val root = data.readUTF()
                val count = data.readInt()
                if (count !in 1..WorkspaceEdits.MAX_FILES || root.length > 16_384) return corrupt()
                val files = ArrayList<WorkspaceBytes>()
                var size = 0L
                repeat(count) {
                    val path = data.readUTF()
                    val beforeExists = if (version == 2L) data.readBoolean() else true
                    val afterExists = if (version == 2L) data.readBoolean() else true
                    fun bytes(): ByteArray {
                        val length = data.readInt()
                        size += length
                        if (length < 0 || length > TextFile.MAX_EDIT_BYTES || size > WorkspaceEdits.MAX_BYTES) throw IOException("размер копии")
                        return ByteArray(length).also(data::readFully)
                    }
                    files += WorkspaceBytes(path, bytes(), bytes(), beforeExists, afterExists)
                }
                if (data.available() != 0 || !valid(files)) return corrupt()
                Outcome.success(WorkspaceChangeCopy(root, files, guard.pending, guard.applied))
            }
        } catch (_: IOException) { corrupt() }
    }

    /** После успеха, восстановления или явного сохранения текущих файлов. Копия остаётся для отмены. */
    fun finish(project: String, applied: Boolean): Outcome<Unit> {
        val old = when (val result = guard(project)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result.value ?: return corrupt()
        }
        val written = writeGuard(project, old.copy(pending = false, applied = applied))
        if (written is Outcome.Success) {
            val docs = copies(project)
            for (key in docs.keys()) if (key != old.current && key != old.previous) docs.delete(key)
        }
        return written
    }

    private fun guard(project: String): Outcome<Guard?> {
        val read = when (val result = guards.read(id(project))) {
            is Outcome.Failure -> return result
            is Outcome.Success -> result
        }
        val doc = read.value ?: return Outcome.success(null)
        // Отступление к прошлой версии не разрешает запуск ИИ после неизвестного сбоя.
        val c = doc.content
        val current = (c["current"] as? JsonString)?.value ?: return corrupt()
        val previous = (c["previous"] as? JsonString)?.value ?: return corrupt()
        if (!KEY.matches(current) || previous.isNotEmpty() && !KEY.matches(previous) || (c["format"] as? JsonString)?.value != FORMAT || (c["version"] as? JsonNumber)?.toLongExact() != 1L) return corrupt()
        val pending = (c["pending"] as? JsonBool)?.value ?: return corrupt()
        val applied = (c["applied"] as? JsonBool)?.value ?: return corrupt()
        val uncertain = doc.fromLastKnownGood || read.diagnostics.isNotEmpty()
        return Outcome.success(Guard(current, previous, pending || uncertain, applied && !uncertain))
    }

    private fun writeGuard(project: String, guard: Guard): Outcome<Unit> {
        val content = JsonObject.of("format" to JsonString(FORMAT), "version" to JsonNumber.of(1), "current" to JsonString(guard.current), "previous" to JsonString(guard.previous), "pending" to JsonBool(guard.pending), "applied" to JsonBool(guard.applied))
        repeat(2) { when (val result = guards.write(id(project), content)) {
            is Outcome.Failure -> return result
            is Outcome.Success -> Unit
        } }
        return Outcome.success(Unit)
    }

    private fun valid(files: List<WorkspaceBytes>): Boolean = files.size in 1..WorkspaceEdits.MAX_FILES &&
        files.map { it.path }.distinct().size == files.size && files.all { it.path.length <= 2048 && FileNames.pathValid(it.path) && it.path.split('/').none { part -> part.equals(".git", true) } && TextFile.decode(it.before) is Outcome.Success && TextFile.decode(it.after) is Outcome.Success } &&
        files.all { (it.beforeExists || it.before.isEmpty()) && (it.afterExists || it.after.isEmpty()) && (it.beforeExists || it.afterExists) } &&
        files.sumOf { it.before.size.toLong() + it.after.size } <= WorkspaceEdits.MAX_BYTES

    private fun <T> corrupt(): Outcome<T> = Outcome.failure(DiagCode.STORAGE_CORRUPTED, "копия проектной правки не прочитана; проект оставлен на паузе")
    companion object {
        private const val FORMAT = "dpai.workspace-change"
        private val KEY = Regex("[a-f0-9]{64}")
    }
}
