package com.dpsmpx.dpai.android

import android.content.Context
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.ArtifactCopies
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
 * Источник и версия входят в ключ копии; завершённое не удаляется автоматически
 * (DPmetaApp:D-193). Подписанные приложением копии — отдельно ([signedDir]): очистка их не трогает, и файл с ПК с тем же именем с ними
 * не смешивается.
 */
object PcArtifacts {
    const val MAX_BYTES: Long = 512L shl 20
    private const val PART = ".part-"

    fun dir(context: Context, target: String): File =
        File(context.applicationContext.filesDir, "pc-artifacts/" + target.map { if (it.isLetterOrDigit() || it in "._-") it else '_' }.joinToString("").take(64))

    /** Копии APK с ПК, подписанные ключом автора в приложении (D-127): живут, пока их не удалят. */
    fun signedDir(context: Context, target: String): File = File(dir(context, target), "signed")

    /** Завершённая копия именно этого источника/версии, без повторного переноса байтов. */
    fun local(context: Context, target: String, root: String, rel: String, size: Long, modified: Long): File? =
        ArtifactCopies.local(dir(context, target), target, root, rel, size, modified)

    /** Скачать [rel] из [tree] на телефон; [cancelled] — прервать между кусками. */
    @Synchronized
    fun download(context: Context, tree: SftpTree, rel: String, target: String, root: String, cancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> }): Outcome<File> {
        val name = rel.substringAfterLast('/')
        if (!FileNames.segmentValid(name) || name.startsWith(PART)) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "имя файла недопустимо", rel)
        val meta = when (val m = tree.meta(rel)) {
            is Outcome.Failure -> return m
            is Outcome.Success -> m.value
        }
        if (meta.size < 0 || meta.size > MAX_BYTES) return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.VALUE_INVALID, "файл больше ${MAX_BYTES shr 20} МБ — заберите его иначе", rel)
        local(context, target, root, rel, meta.size, meta.modified)?.let { onProgress(meta.size, meta.size); return Outcome.success(it) }
        ArtifactCopies.clearIncomplete(dir(context, target))
        StorageSpace.check(context, SpaceGuard.Kind.DOWNLOAD, meta.size, "файл с ПК").let { if (it is Outcome.Failure) return it }
        val saved = File(dir(context, target), ArtifactCopies.relative(target, root, rel, meta.size, meta.modified))
        val folder = saved.parentFile!!.apply { mkdirs() }
        val vfs = NioVfs(folder.toPath())
        val part = PART + name
        return try {
            vfs.write(part, ByteArray(0))
            var have = 0L
            onProgress(0, meta.size)
            val copied = tree.copy(rel, MAX_BYTES, cancelled) { b, n ->
                vfs.append(part, b.copyOf(n))
                have += n
                onProgress(have, meta.size)
            }
            when (copied) {
                is Outcome.Failure -> { runCatching { vfs.delete(part) }; copied }
                is Outcome.Success -> {
                    val after = when (val checked = tree.meta(rel)) {
                        is Outcome.Failure -> { vfs.delete(part); return checked }
                        is Outcome.Success -> checked.value
                    }
                    if (have != meta.size || after != meta) {
                        vfs.delete(part)
                        return Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, "размер источника изменился при копировании", rel)
                    }
                    vfs.fsync(part)
                    vfs.rename(part, name)
                    Outcome.success(File(folder, name))
                }
            }
        } catch (e: IOException) {
            runCatching { vfs.delete(part) }
            Outcome.failure(com.dpsmpx.dpai.core.diagnostics.DiagCode.STORAGE_IO_FAILED, "файл не записан: ${e.javaClass.simpleName}", rel)
        }
    }

}
