package com.dpsmpx.dpai.probe

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Распаковка minirootfs Alpine (`tar.gz`) в каталог данных пробника:
 * каталоги, файлы с правами, символьные ссылки (в minirootfs 3.24.3 только
 * они — проверено 23 октября 2026); жёсткая ссылка — копией; длинные имена
 * GNU и PAX — по их записям. Путь вне [dir] (`..`, абсолютный) не
 * распаковывается — он в отчёте как пропущенный. Ссылки и права — через
 * [Links]: на устройстве [AndroidLinks], в тесте на JVM — `java.nio`.
 */
object Rootfs {
    data class Summary(val files: Int, val dirs: Int, val symlinks: Int, val bytes: Long, val skipped: List<String>)

    fun unpack(input: InputStream, dir: File, links: Links = AndroidLinks): Summary {
        val tar = GZIPInputStream(input.buffered())
        val header = ByteArray(BLOCK)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var bytes = 0L
        val skipped = mutableListOf<String>()
        var longName: String? = null
        val root = dir.canonicalFile
        dir.mkdirs()
        while (read(tar, header)) {
            if (header.all { it.toInt() == 0 }) break
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val name = longName ?: (text(header, 345, 155).let { if (it.isEmpty()) "" else "$it/" } + text(header, 0, 100))
            longName = null
            when (type) {
                'L' -> {
                    longName = String(body(tar, size), Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x' -> {
                    longName = pax(String(body(tar, size), Charsets.UTF_8))
                    continue
                }
                'g' -> {
                    body(tar, size)
                    continue
                }
            }
            val rel = name.removePrefix("./").trimEnd('/')
            val target = File(root, rel)
            if (rel.isEmpty() && type == '5') {
                skip(tar, size + pad(size))
                continue
            }
            // Родитель — по настоящему пути: ссылка внутри архива не выводит запись за каталог.
            val parent = target.parentFile?.canonicalPath.orEmpty()
            if (rel.startsWith("/") || rel.split('/').any { it == ".." } || !(parent == root.path || parent.startsWith(root.path + "/"))) {
                skipped += "$rel (вне каталога)"
                skip(tar, size + pad(size))
                continue
            }
            val mode = octal(header, 100, 8).toInt() and 0x1ff
            when (type) {
                '5' -> {
                    target.mkdirs()
                    chmod(links, target, mode)
                    dirs++
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    try {
                        links.symlink(text(header, 157, 100), target.path)
                        symlinks++
                    } catch (e: Exception) {
                        skipped += "$rel (символьная ссылка: ${e.message})"
                    }
                }
                '1' -> {
                    val from = File(root, text(header, 157, 100).removePrefix("./"))
                    target.parentFile?.mkdirs()
                    if (from.isFile) from.copyTo(target, overwrite = true).also { chmod(links, it, mode) } else skipped += "$rel (жёсткая ссылка без файла)"
                    files++
                }
                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    target.delete()
                    FileOutputStream(target).use { out -> copy(tar, out, size) }
                    skip(tar, pad(size))
                    chmod(links, target, mode)
                    files++
                    bytes += size
                    continue
                }
                else -> skipped += "$rel (тип ${type.code})"
            }
            skip(tar, size + pad(size))
        }
        return Summary(files, dirs, symlinks, bytes, skipped)
    }

    private const val BLOCK = 512

    private fun read(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off == buf.size
            off += n
        }
        return true
    }

    private fun text(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun octal(h: ByteArray, off: Int, len: Int): Long = text(h, off, len).trim().ifEmpty { "0" }.toLong(8)

    private fun pad(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    private fun body(input: InputStream, size: Long): ByteArray {
        val buf = ByteArray(size.toInt())
        read(input, buf)
        skip(input, pad(size))
        return buf
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun copy(input: InputStream, out: FileOutputStream, size: Long) {
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }

    /** Имя из записи PAX `длина path=имя`. */
    private fun pax(records: String): String? =
        records.lineSequence().map { it.substringAfter(' ', "") }.firstOrNull { it.startsWith("path=") }?.removePrefix("path=")

    private fun chmod(links: Links, f: File, mode: Int) {
        try {
            links.chmod(f.path, mode)
        } catch (_: Exception) {
            // Права — не главное в распаковке: файл на месте, а отказ запуска будет виден в отчёте.
        }
    }
}

/** Символьные ссылки и права файлов. */
interface Links {
    fun symlink(target: String, path: String)

    fun chmod(path: String, mode: Int)
}

/** На устройстве — системные вызовы Android. */
object AndroidLinks : Links {
    override fun symlink(target: String, path: String) = Os.symlink(target, path)

    override fun chmod(path: String, mode: Int) = Os.chmod(path, mode)
}
