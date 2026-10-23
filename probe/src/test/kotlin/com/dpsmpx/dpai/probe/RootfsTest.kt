package com.dpsmpx.dpai.probe

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Распаковщик minirootfs на JVM (ссылки и права — `java.nio`): настоящий
 * архив Alpine из сборки пробника распаковывается целиком, а записи вне
 * каталога — нет. На устройстве — то же через `android.system.Os`.
 */
class RootfsTest {
    @TempDir
    lateinit var dir: Path

    private val jvm = object : Links {
        override fun symlink(target: String, path: String) {
            Files.createSymbolicLink(Path.of(path), Path.of(target))
        }

        override fun chmod(path: String, mode: Int) {
            val bits = PosixFilePermission.entries.toTypedArray()
            // OWNER_READ … OTHERS_EXECUTE — от старшего бита 0400 к младшему 0001.
            Files.setPosixFilePermissions(Path.of(path), bits.filterIndexed { i, _ -> mode and (0x100 shr i) != 0 }.toSet())
        }
    }

    @Test
    fun unpacksAlpineMinirootfs() {
        val archive = File(requireNotNull(System.getProperty("dpai.probe.rootfs")) { "свойство dpai.probe.rootfs задаёт сборка пробника" })
        val root = dir.resolve("rootfs").toFile()
        val s = archive.inputStream().use { Rootfs.unpack(it, root, jvm) }
        assertEquals(emptyList<String>(), s.skipped)
        // minirootfs 3.24.3: 83 файла, 96 каталогов без корня, 335 символьных ссылок (23 октября 2026).
        assertEquals(83, s.files)
        assertEquals(96, s.dirs)
        assertEquals(335, s.symlinks)
        assertEquals("3.24.3", File(root, "etc/alpine-release").readText().trim())
        assertTrue(Files.isExecutable(root.toPath().resolve("bin/busybox")), "busybox исполняемый")
        assertEquals("/bin/busybox", Files.readSymbolicLink(root.toPath().resolve("bin/sh")).toString())
    }

    /** Заголовок ustar: имя, права, размер, тип, цель ссылки; сумма заголовка распаковщику не нужна. */
    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "") {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        "0000644".toByteArray().copyInto(h, 100)
        "%011o".format(body.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        link.toByteArray().copyInto(h, 157)
        out.write(h)
        out.write(body)
        out.write(ByteArray((512 - body.size % 512) % 512))
    }

    @Test
    fun entriesOutsideTheRootAreSkipped() {
        val tar = ByteArrayOutputStream()
        entry(tar, "./etc/", '5')
        entry(tar, "./etc/ok", '0', "ok".toByteArray())
        entry(tar, "../evil", '0', "x".toByteArray())
        entry(tar, "./out", '2', link = "/")
        entry(tar, "./out/escape", '0', "x".toByteArray())
        tar.write(ByteArray(1024))
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(tar.toByteArray()) } }
        val root = dir.resolve("r").toFile()
        val s = Rootfs.unpack(gz.toByteArray().inputStream(), root, jvm)
        assertEquals("ok", File(root, "etc/ok").readText())
        assertFalse(dir.resolve("evil").toFile().exists())
        assertEquals(2, s.skipped.size, s.skipped.toString())
        assertTrue(s.skipped.all { "вне каталога" in it })
    }
}
