package com.dpsmpx.dpai.storage.vfs

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * [Vfs] на `java.nio` внутри каталога [root]. fsync — `FileChannel.force`;
 * fsync каталога — открытие каталога на чтение и `force` (Linux и Android);
 * где ОС этого не позволяет, остаётся только переименование, и это видно
 * в `docs/RECOVERY.md` как риск потери последней неподтверждённой записи.
 */
class NioVfs(private val root: Path) : Vfs {
    private fun p(path: String): Path {
        require(path.isEmpty() || Vfs.isSafeRelative(path)) { "небезопасный путь" }
        return if (path.isEmpty()) root else root.resolve(path)
    }

    override fun read(path: String): ByteArray? = try {
        Files.readAllBytes(p(path))
    } catch (_: NoSuchFileException) {
        null
    }

    override fun exists(path: String): Boolean = Files.isRegularFile(p(path))

    override fun size(path: String): Long? = p(path).takeIf { Files.isRegularFile(it) }?.let(Files::size)

    override fun list(dir: String): List<String> {
        val d = p(dir)
        if (!Files.isDirectory(d)) return emptyList()
        // Не Stream.toList(): его (Java 16) нет в Android 11 (`check_android_api.py`).
        return Files.list(d).use { s -> s.iterator().asSequence().filter { Files.isRegularFile(it) }.map { it.fileName.toString() }.sorted().toList() }
    }

    override fun write(path: String, bytes: ByteArray) {
        FileChannel.open(p(path), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use {
            writeFully(it, bytes)
        }
    }

    override fun append(path: String, bytes: ByteArray) {
        FileChannel.open(p(path), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE).use {
            writeFully(it, bytes)
        }
    }

    override fun appendDurably(path: String, bytes: ByteArray) {
        FileChannel.open(p(path), StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE).use {
            writeFully(it, bytes)
            it.force(true)
        }
    }

    override fun truncate(path: String, size: Long) {
        FileChannel.open(p(path), StandardOpenOption.WRITE).use { it.truncate(size) }
    }

    override fun fsync(path: String) {
        FileChannel.open(p(path), StandardOpenOption.WRITE).use { it.force(true) }
    }

    override fun rename(from: String, to: String) {
        try {
            Files.move(p(from), p(to), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            throw IOException("атомарное переименование не поддерживается", e)
        }
    }

    override fun delete(path: String) {
        Files.deleteIfExists(p(path))
    }

    override fun fsyncDir(dir: String) {
        try {
            FileChannel.open(p(dir), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Не все ОС позволяют fsync каталога; переименование остаётся атомарным.
        }
    }

    override fun mkdirs(dir: String) {
        Files.createDirectories(p(dir))
    }

    override fun symlink(path: String, target: String) {
        val link = p(path)
        Files.deleteIfExists(link)
        // Не Path.of: его (Java 11) нет в Android 11 (`check_android_api.py`).
        Files.createSymbolicLink(link, link.fileSystem.getPath(target))
    }

    override fun isSymlink(path: String): Boolean = Files.isSymbolicLink(p(path))

    override fun setMode(path: String, mode: Int) {
        val view = Files.getFileAttributeView(p(path), PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
        // OWNER_READ … OTHERS_EXECUTE — от бита 0400 к биту 0001.
        view.setPermissions(PosixFilePermission.values().filterIndexed { i, _ -> mode and (0x100 shr i) != 0 }.toSet())
    }

    private fun writeFully(ch: FileChannel, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) ch.write(buf)
    }
}
