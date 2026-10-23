package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Корневая система Alpine Linux-среды в приложении (D-61, D-68): распаковка
 * minirootfs (`tar.gz`) в каталог [Vfs] — каталоги, файлы с правами,
 * символьные ссылки; жёсткая ссылка — копией (Android жёстких ссылок не
 * даёт, `docs/research/LINUX_IN_APP.md`, §9); длинные имена GNU и PAX.
 *
 * Запись — только внутрь корня: путь с `..`, абсолютный или через
 * символьную ссылку, созданную этим же архивом, не распаковывается, а
 * попадает в итог пропущенным. Файл больше [MAX_FILE] — тоже: архив
 * испорчен, а память телефона не резиновая.
 */
object RootfsInstaller {
    data class Summary(val files: Int, val dirs: Int, val symlinks: Int, val bytes: Long, val skipped: List<String>)

    /** Самый большой файл, который распаковывается целиком в память (в minirootfs 3.24.3 — около 1 МиБ). */
    const val MAX_FILE: Long = 64L * 1024 * 1024

    private const val BLOCK = 512

    fun unpack(input: InputStream, vfs: Vfs, dir: String): Outcome<Summary> = try {
        Outcome.success(unpackOrThrow(GZIPInputStream(input.buffered()), vfs, dir))
    } catch (e: IOException) {
        Outcome.Failure(listOf(Diagnostic.fromThrowable(DiagCode.STORAGE_IO_FAILED, "корневая система не распакована", e, dir)))
    }

    private fun unpackOrThrow(tar: InputStream, vfs: Vfs, dir: String): Summary {
        val header = ByteArray(BLOCK)
        var files = 0
        var dirs = 0
        var symlinks = 0
        var bytes = 0L
        val skipped = mutableListOf<String>()
        val links = HashSet<String>()
        var longName: String? = null
        vfs.mkdirs(dir)
        while (readBlock(tar, header)) {
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
            if (rel.isEmpty()) {
                skip(tar, size + pad(size))
                continue
            }
            val parents = rel.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }
            if (!Vfs.isSafeRelative(rel) || parents.any { it in links }) {
                skipped += "$rel (вне каталога)"
                skip(tar, size + pad(size))
                continue
            }
            val path = Vfs.join(dir, rel)
            val mode = octal(header, 100, 8).toInt() and 0x1ff
            when (type) {
                '5' -> {
                    vfs.mkdirs(path)
                    vfs.setMode(path, mode)
                    dirs++
                }
                '2' -> {
                    vfs.mkdirs(Vfs.parentOf(path))
                    vfs.symlink(path, text(header, 157, 100))
                    links += rel
                    symlinks++
                }
                '1' -> {
                    val from = text(header, 157, 100).removePrefix("./")
                    val content = if (Vfs.isSafeRelative(from) && from !in links) vfs.read(Vfs.join(dir, from)) else null
                    if (content == null) {
                        skipped += "$rel (жёсткая ссылка без файла)"
                    } else {
                        vfs.mkdirs(Vfs.parentOf(path))
                        vfs.write(path, content)
                        vfs.setMode(path, mode)
                        files++
                    }
                }
                '0', '\u0000', '7' -> {
                    if (size > MAX_FILE) {
                        skipped += "$rel (больше ${MAX_FILE / 1024 / 1024} МиБ)"
                        skip(tar, size + pad(size))
                        continue
                    }
                    vfs.mkdirs(Vfs.parentOf(path))
                    vfs.write(path, body(tar, size))
                    vfs.setMode(path, mode)
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

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw IOException("архив оборван посреди заголовка")
            }
            off += n
        }
        return true
    }

    private fun text(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun octal(h: ByteArray, off: Int, len: Int): Long =
        text(h, off, len).trim().ifEmpty { "0" }.toLongOrNull(8) ?: throw IOException("испорченный заголовок архива")

    private fun pad(size: Long): Long = (BLOCK - size % BLOCK) % BLOCK

    /** Тело записи целиком и выравнивание за ним; оборванный архив — отказ. */
    private fun body(input: InputStream, size: Long): ByteArray {
        val buf = ByteArray(size.toInt())
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("архив оборван посреди файла")
            off += n
        }
        skip(input, pad(size))
        return buf
    }

    private fun skip(input: InputStream, size: Long) {
        var left = size
        val buf = ByteArray(8192)
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("архив оборван")
            left -= n
        }
    }

    /** Имя из записи PAX `длина path=имя`. */
    private fun pax(records: String): String? =
        records.lineSequence().map { it.substringAfter(' ', "") }.firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
}
