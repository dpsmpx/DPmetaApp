package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.hashing.Hashing
import java.io.File
import java.nio.file.Files

/**
 * Копия принадлежит target, корню дерева, пути и версии источника (DPmetaApp:D-193).
 * Одного basename недостаточно: у разных проектов часто есть shell-release.apk.
 * Метаданные SFTP определяют версию; скрытые part никогда не считаются готовыми.
 */
object ArtifactCopies {
    fun relative(target: String, root: String, path: String, size: Long, modified: Long): String {
        val name = path.substringAfterLast('/')
        require(FileNames.segmentValid(name) && !name.startsWith(".part-") && size >= 0) { "недопустимая копия артефакта" }
        val identity = listOf(target, root, path, size.toString(), modified.toString()).joinToString("") { "${it.length}:$it" }
        return "v2/${Hashing.sha256(identity).hex}/$name"
    }

    fun local(folder: File, target: String, root: String, path: String, size: Long, modified: Long): File? {
        val name = path.substringAfterLast('/')
        if (!FileNames.segmentValid(name) || name.startsWith(".part-") || size < 0 || modified <= 0) return null
        return File(folder, relative(target, root, path, size, modified)).takeIf { !Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() == size }
    }

    /** Вызывается без активного writer: завершённые копии и signed не трогаются. */
    fun clearIncomplete(folder: File) {
        fun parts(dir: File) {
            dir.listFiles()?.filter { it.name.startsWith(".part-") && !it.isDirectory }?.forEach { it.delete() }
        }
        parts(folder) // прежний плоский кэш
        val versions = File(folder, "v2")
        if (Files.isSymbolicLink(versions.toPath())) return
        versions.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}")) && it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            ?.forEach(::parts)
    }
}
