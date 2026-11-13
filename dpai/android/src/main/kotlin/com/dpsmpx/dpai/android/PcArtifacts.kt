package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileNames
import com.dpsmpx.dpai.jsch.SftpTree
import com.dpsmpx.dpai.storage.space.SpaceGuard
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.io.IOException

/**
 * Артефакты ПК-target на телефоне (`DPmetaApp:D-127`; D-162): файл дерева ПК
 * скачивается по SFTP в хранилище приложения (`pc-artifacts/<target>`), не больше
 * [MAX_BYTES], с проверкой места; сначала во временный файл, затем переименование.
 * На target хранятся [KEEP] последних файлов — прежние удаляются. Подписанные приложением
 * копии — отдельно ([signedDir]): очистка их не трогает, и файл с ПК с тем же именем с ними
 * не смешивается.
 */
object PcArtifacts {
    const val MAX_BYTES: Long = 512L shl 20
    const val KEEP: Int = 3
    private const val PART = ".part-"

    fun dir(context: Context, target: String): File =
        File(context.applicationContext.filesDir, "pc-artifacts/" + target.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(64))

    /** Копии APK с ПК, подписанные ключом автора в приложении (D-127): живут, пока их не удалят. */
    fun signedDir(context: Context, target: String): File = File(dir(context, target), "signed")

    /** Уже скачанный файл с именем [name], если он того же размера, что на ПК, и не старше его. */
    fun local(context: Context, target: String, name: String, size: Long, modified: Long): File? =
        File(dir(context, target), name).takeIf { FileNames.segmentValid(name) && it.isFile && it.length() == size && it.lastModified() >= modified }

    /** Скачать [rel] из [tree] на телефон; [cancelled] — прервать между кусками. */
    fun download(context: Context, tree: SftpTree, rel: String, target: String, cancelled: () -> Boolean = { false }): Outcome<File> {
        val name = rel.substringAfterLast('/')
        if (!FileNames.segmentValid(name) || name.startsWith(PART)) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "имя файла недопустимо", rel)
        val meta = when (val m = tree.meta(rel)) {
            is Outcome.Failure -> return m
            is Outcome.Success -> m.value
        }
        if (meta.size > MAX_BYTES) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "файл больше ${MAX_BYTES shr 20} МБ — заберите его иначе", rel)
        StorageSpace.check(context, SpaceGuard.Kind.DOWNLOAD, meta.size, "файл с ПК").let { if (it is Outcome.Failure) return it }
        val folder = dir(context, target).apply { mkdirs() }
        val vfs = NioVfs(folder.toPath())
        val part = PART + name
        return try {
            vfs.write(part, ByteArray(0))
            val copied = tree.copy(rel, MAX_BYTES, cancelled) { b, n -> vfs.append(part, b.copyOf(n)) }
            when (copied) {
                is Outcome.Failure -> { runCatching { vfs.delete(part) }; copied }
                is Outcome.Success -> {
                    vfs.fsync(part)
                    vfs.rename(part, name)
                    prune(vfs, folder, keep = name)
                    Outcome.success(File(folder, name))
                }
            }
        } catch (e: IOException) {
            runCatching { vfs.delete(part) }
            Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, "файл не записан: ${e.javaClass.simpleName}", rel)
        }
    }

    /** Оставить [KEEP] новейших файлов (и [keep]); временные от оборванных загрузок — убрать. */
    private fun prune(vfs: NioVfs, folder: File, keep: String) {
        val names = vfs.list("")
        names.filter { it.startsWith(PART) }.forEach { runCatching { vfs.delete(it) } }
        val files = names.filter { !it.startsWith(PART) && it != keep }
            .sortedByDescending { File(folder, it).lastModified() }
        files.drop(KEEP - 1).forEach { runCatching { vfs.delete(it) } }
    }
}
