package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class NioVfsTest {
    @TempDir
    lateinit var dir: Path

    /** Часть файла (D-119): с места и длины, за концом — пусто, нет файла — `null`; то же — по умолчанию интерфейса. */
    /** writeFresh: ссылка на месте файла убирается сама, цель не трогается; файл создаётся заново. */
    @Test
    fun writeFreshDoesNotFollowALink(@TempDir tmp: Path) {
        val outside = java.nio.file.Files.writeString(tmp.resolve("outside.txt"), "цель")
        val root = java.nio.file.Files.createDirectories(tmp.resolve("root"))
        java.nio.file.Files.createSymbolicLink(root.resolve("t.tmp"), outside)
        val vfs = NioVfs(root)
        vfs.writeFresh("t.tmp", "новое".toByteArray())
        assertEquals("цель", java.nio.file.Files.readString(outside))
        assertFalse(java.nio.file.Files.isSymbolicLink(root.resolve("t.tmp")))
        assertEquals("новое", java.nio.file.Files.readString(root.resolve("t.tmp")))
        vfs.writeFresh("t.tmp", "ещё".toByteArray())
        assertEquals("ещё", java.nio.file.Files.readString(root.resolve("t.tmp")), "остаток прошлой записи — заменён")
    }

    @Test
    fun readRangeReadsAPartWithoutTheWholeFile() {
        val vfs = NioVfs(dir)
        vfs.write("m.gguf", ByteArray(100) { it.toByte() })
        assertContentEquals(byteArrayOf(10, 11, 12), vfs.readRange("m.gguf", 10, 3))
        assertContentEquals(byteArrayOf(98, 99), vfs.readRange("m.gguf", 98, 50))
        assertEquals(0, vfs.readRange("m.gguf", 100, 5)!!.size)
        assertNull(vfs.readRange("нет", 0, 5))
        // Умолчание интерфейса — чтением целиком (у SimulatedVfs своего нет).
        val plain = SimulatedVfs()
        plain.write("m.gguf", ByteArray(100) { it.toByte() })
        assertContentEquals(vfs.readRange("m.gguf", 40, 7), plain.readRange("m.gguf", 40, 7))
        assertContentEquals(byteArrayOf(98, 99), plain.readRange("m.gguf", 98, 50))
        assertEquals(0, plain.readRange("m.gguf", 200, 7)!!.size)
    }

    @Test
    fun basicOperationsOnRealFiles() {
        val vfs = NioVfs(dir)
        vfs.mkdirs("data")
        assertNull(vfs.read("data/a"))
        vfs.write("data/a", "один".toByteArray())
        vfs.append("data/a", "+два".toByteArray())
        vfs.fsync("data/a")
        assertContentEquals("один+два".toByteArray(), vfs.read("data/a"))
        vfs.rename("data/a", "data/b")
        vfs.fsyncDir("data")
        assertFalse(vfs.exists("data/a"))
        assertEquals(listOf("b"), vfs.list("data"))
        vfs.truncate("data/b", 3)
        assertEquals(3, vfs.read("data/b")!!.size)
        vfs.delete("data/b")
        assertTrue(vfs.list("data").isEmpty())
        assertFailsWith<IllegalArgumentException> { vfs.read("../outside") }
    }

    @Test
    fun documentStoreOnRealFiles() {
        val store = DocumentStore(NioVfs(dir), "data")
        store.write("settings", JsonObject.of("x" to json(1)))
        store.write("settings", JsonObject.of("x" to json(2)))
        assertEquals(JsonObject.of("x" to json(2)), store.read("settings").valueOrNull()?.content)
    }

    /** Ссылки и права — для корневой системы Linux-среды в приложении (D-75): цель — как есть, права — младшие 9 бит. */
    @Test
    fun symlinksAndModes() {
        // Ссылки на Windows требуют особых прав, а среда в приложении — только Android: проверка — на POSIX.
        val posix = java.nio.file.Files.getFileAttributeView(dir, java.nio.file.attribute.PosixFileAttributeView::class.java)
        org.junit.jupiter.api.Assumptions.assumeTrue(posix != null, "прав POSIX нет (Windows)")
        val vfs = NioVfs(dir)
        vfs.mkdirs("bin")
        vfs.write("bin/busybox", byteArrayOf(1))
        vfs.setMode("bin/busybox", "755".toInt(8))
        vfs.symlink("bin/sh", "/bin/busybox")
        assertEquals("/bin/busybox", java.nio.file.Files.readSymbolicLink(dir.resolve("bin/sh")).toString())
        // Повтор заменяет ссылку, а не падает.
        vfs.symlink("bin/sh", "busybox")
        assertEquals("busybox", java.nio.file.Files.readSymbolicLink(dir.resolve("bin/sh")).toString())
        assertEquals("rwxr-xr-x", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(dir.resolve("bin/busybox"))))
        vfs.setMode("bin/busybox", "600".toInt(8))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(java.nio.file.Files.getPosixFilePermissions(dir.resolve("bin/busybox"))))
    }

    @Test
    fun safePaths() {
        assertTrue(Vfs.isSafeRelative("data/journal.log"))
        for (bad in listOf("", "/etc/passwd", "../x", "a/../b", "a//b", "a\\b", "C:x", "./a")) assertFalse(Vfs.isSafeRelative(bad), bad)
    }
}
