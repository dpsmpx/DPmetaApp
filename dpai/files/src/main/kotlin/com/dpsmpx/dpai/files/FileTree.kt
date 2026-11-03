package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/** Размер и время изменения файла (мс): по ним синхронизация видит, что файл менялся (D-99). */
data class FileMeta(val size: Long, val modified: Long)

/** Элемент каталога: имя, каталог ли, размер и время изменения. */
data class FileEntry(val name: String, val dir: Boolean, val size: Long, val modified: Long) {
    val meta: FileMeta get() = FileMeta(size, modified)
}

/**
 * Дерево файлов (D-99): папка системного окна Android (SAF), копия в
 * Linux, общее хранилище. Пути — относительные, через `/`, каждое имя —
 * [FileNames.segmentValid]; символьных ссылок дерево не показывает и не
 * проходит. Ожидаемые отказы — `Outcome` (D-05).
 */
interface FileTree {
    /** Каталог [dir] (`""` — корень): файлы и каталоги. */
    fun list(dir: String): Outcome<List<FileEntry>>

    /** Файл целиком, не больше [maxBytes]. */
    fun read(path: String, maxBytes: Long): Outcome<ByteArray>

    /** Записать файл, создав недостающие каталоги; итог — сведения о записанном. */
    fun write(path: String, bytes: ByteArray): Outcome<FileMeta>

    fun delete(path: String): Outcome<Unit>
}

object FileTrees {
    /**
     * Все файлы дерева: путь → сведения. Больше [maxFiles] — отказ: копия
     * без части файлов выглядела бы целой. Негодные имена (`FileNames`)
     * пропускаются — их список в [Scan.skipped].
     */
    fun scan(tree: FileTree, maxFiles: Int = FolderSync.MAX_FILES): Outcome<Scan> {
        val files = LinkedHashMap<String, FileMeta>()
        val skipped = mutableListOf<String>()
        val queue = ArrayDeque(listOf(""))
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            val entries = when (val r = tree.list(dir)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val seen = HashSet<String>()
            for (e in entries) {
                val path = FileNames.join(dir, e.name)
                if (!FileNames.segmentValid(e.name) || !seen.add(e.name)) {
                    skipped += path
                    continue
                }
                if (e.dir) {
                    queue.addLast(path)
                } else {
                    files[path] = e.meta
                    if (files.size > maxFiles) return Outcome.failure(DiagCode.VALUE_INVALID, "файлов больше $maxFiles — папка слишком велика для копии", dir.ifEmpty { "/" })
                }
            }
        }
        return Outcome.success(Scan(files, skipped))
    }

    data class Scan(val files: Map<String, FileMeta>, val skipped: List<String>)
}

/** Имена и пути деревьев файлов. */
object FileNames {
    /** Остаток прерванной записи — не файл автора: не показывается и не переносится. */
    const val TMP_PREFIX = ".dpai-tmp-"

    /**
     * Годное имя: не пустое, не `.` и `..`, без `/`, `\`, `:` и управляющих
     * знаков, до 255 байт UTF-8. `:` и `\` запрещает и хранилище (`Vfs`).
     */
    fun segmentValid(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' || it.isISOControl() } &&
            name.toByteArray(Charsets.UTF_8).size <= 255 && !name.startsWith(TMP_PREFIX)

    fun pathValid(path: String): Boolean = path.isNotEmpty() && path.split('/').all(::segmentValid)

    fun join(dir: String, name: String): String = if (dir.isEmpty()) name else "$dir/$name"

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun name(path: String): String = path.substringAfterLast('/')

    /**
     * Имя каталога копии в Linux для папки [display]: знаки, которые
     * оболочке и хранилищу мешают, — `_`; занятое — с номером.
     */
    fun localDir(display: String, taken: Set<String>): String {
        val clean = display.map { c -> if (c.isLetterOrDigit() || c in "._- ") c else '_' }.joinToString("").trim().trim('.').take(64)
        val base = clean.ifEmpty { "folder" }
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }
}
