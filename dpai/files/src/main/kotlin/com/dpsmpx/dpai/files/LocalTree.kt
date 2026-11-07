package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Дерево файлов на файловой системе (D-99): копия папки в Linux, домашняя
 * папка гостя, общее хранилище в режиме всех файлов. Запись — через `Vfs`
 * хранилища (D-24): во временный файл и переименованием.
 *
 * Символьные ссылки не проходятся (T17): гость Linux может их создать, и
 * ссылка на данные приложения вынесла бы их в папку автора, а запись по
 * ссылке-каталогу ушла бы за пределы корня. Поэтому в списке ссылок нет,
 * а путь, в котором хоть одна часть — ссылка, отвергается.
 *
 * [hidden] — что не показывать и не трогать (секреты домашней папки
 * Linux, D-96); путь — относительный от корня.
 */
class LocalTree(private val root: Path, private val hidden: (String) -> Boolean = { false }) : FileTree {
    private val vfs = NioVfs(root)

    /** Путь внутри корня без ссылок в частях; [leafMayBeMissing] — для записи нового файла. */
    private fun resolve(rel: String, leafMayBeMissing: Boolean = false): Outcome<Path> {
        if (rel.isNotEmpty() && !FileNames.pathValid(rel)) return Outcome.failure(DiagCode.VALUE_INVALID, "путь недопустим", rel)
        if (rel.isNotEmpty() && hidden(rel)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "секрет — не показывается", rel)
        var p = root
        val parts = if (rel.isEmpty()) emptyList() else rel.split('/')
        for ((i, part) in parts.withIndex()) {
            p = p.resolve(part)
            if (Files.isSymbolicLink(p)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "символьная ссылка не проходится", rel)
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !(leafMayBeMissing)) {
                return Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "нет такого файла", rel)
            }
            if (i < parts.size - 1 && Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                return Outcome.failure(DiagCode.VALUE_INVALID, "часть пути — не каталог", rel)
            }
        }
        return Outcome.success(p)
    }

    override fun list(dir: String): Outcome<List<FileEntry>> {
        val d = when (val r = resolve(dir)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!Files.isDirectory(d, LinkOption.NOFOLLOW_LINKS)) return Outcome.failure(DiagCode.VALUE_INVALID, "не каталог", dir)
        return try {
            val out = mutableListOf<FileEntry>()
            Files.newDirectoryStream(d).use { s ->
                for (child in s) {
                    val name = child.fileName.toString()
                    if (name.startsWith(FileNames.TMP_PREFIX) || hidden(FileNames.join(dir, name))) continue
                    val a = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    if (a.isSymbolicLink || !(a.isDirectory || a.isRegularFile)) continue
                    out += FileEntry(name, a.isDirectory, if (a.isDirectory) 0 else a.size(), a.lastModifiedTime().toMillis())
                }
            }
            Outcome.success(out.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() })))
        } catch (e: IOException) {
            Outcome.failure(DiagCode.STORAGE_IO_FAILED, "каталог не прочитан: ${e.javaClass.simpleName}", dir)
        }
    }

    override fun read(path: String, maxBytes: Long): Outcome<ByteArray> {
        val p = when (val r = resolve(path)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return try {
            val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            when {
                !a.isRegularFile -> Outcome.failure(DiagCode.VALUE_INVALID, "не файл", path)
                a.size() > maxBytes -> Outcome.failure(DiagCode.VALUE_INVALID, "файл больше ${maxBytes / 1024} КиБ", path)
                else -> Outcome.success(Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS).use { it.readBytes() })
            }
        } catch (e: IOException) {
            Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл не прочитан: ${e.javaClass.simpleName}", path)
        }
    }

    override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> {
        if (path.isEmpty()) return Outcome.failure(DiagCode.VALUE_INVALID, "путь пуст", path)
        val p = when (val r = resolve(path, leafMayBeMissing = true)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) return Outcome.failure(DiagCode.VALUE_INVALID, "на этом месте каталог", path)
        val parent = FileNames.parent(path)
        val tmp = FileNames.join(parent, FileNames.TMP_PREFIX + FileNames.name(path))
        return try {
            if (parent.isNotEmpty()) vfs.mkdirs(parent)
            vfs.write(tmp, bytes)
            vfs.rename(tmp, path)
            val a = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            Outcome.success(FileMeta(a.size(), a.lastModifiedTime().toMillis()))
        } catch (e: IOException) {
            runCatching { vfs.delete(tmp) }
            Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл не записан: ${e.javaClass.simpleName}", path)
        }
    }

    override fun delete(path: String): Outcome<Unit> {
        val p = when (val r = resolve(path)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return Outcome.failure(DiagCode.VALUE_INVALID, "удаляются только файлы", path)
        return try {
            vfs.delete(path)
            Outcome.success(Unit)
        } catch (e: IOException) {
            Outcome.failure(DiagCode.STORAGE_IO_FAILED, "файл не удалён: ${e.javaClass.simpleName}", path)
        }
    }
}
