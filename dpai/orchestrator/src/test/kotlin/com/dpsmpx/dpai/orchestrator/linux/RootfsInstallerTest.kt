package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Корневая система Linux-среды в приложении (D-75, D-82): распаковка
 * minirootfs через Vfs. Ссылки и права записываются в память теста —
 * поведение одно на Linux и Windows; настоящий minirootfs Alpine — если
 * сборка приложения уже загрузила его в кеш Gradle.
 */
class RootfsInstallerTest {
    @TempDir
    lateinit var dir: Path

    private val links = HashMap<String, String>()
    private val modes = HashMap<String, Int>()

    private fun vfs(): Vfs = object : Vfs by NioVfs(dir) {
        override fun symlink(path: String, target: String) {
            links[path] = target
        }

        override fun setMode(path: String, mode: Int) {
            modes[path] = mode
        }
    }

    /** Заголовок ustar: имя, права, размер, тип, цель ссылки; сумма заголовка установщику не нужна. */
    private fun entry(out: ByteArrayOutputStream, name: String, type: Char, body: ByteArray = ByteArray(0), link: String = "", mode: String = "0000644") {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        mode.toByteArray().copyInto(h, 100)
        "%011o".format(body.size).toByteArray().copyInto(h, 124)
        h[156] = type.code.toByte()
        link.toByteArray().copyInto(h, 157)
        out.write(h)
        out.write(body)
        out.write(ByteArray((512 - body.size % 512) % 512))
    }

    private fun gz(tar: ByteArrayOutputStream, end: Boolean = true): ByteArray {
        if (end) tar.write(ByteArray(1024))
        return ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(tar.toByteArray()) } }.toByteArray()
    }

    @Test
    fun unpacksFilesDirsLinksAndLongNames() {
        val tar = ByteArrayOutputStream()
        entry(tar, "./", '5')
        entry(tar, "./bin/", '5', mode = "0000755")
        entry(tar, "./bin/busybox", '0', "bb".toByteArray(), mode = "0000755")
        entry(tar, "./bin/sh", '2', link = "/bin/busybox")
        entry(tar, "./bin/ash", '1', link = "./bin/busybox", mode = "0000755")
        val long = "usr/share/" + "x".repeat(120)
        entry(tar, "././@LongLink", 'L', (long + "\u0000").toByteArray())
        entry(tar, "ignored", '0', "long".toByteArray())
        val paxName = "etc/pax-" + "y".repeat(110)
        val record = "path=$paxName\n"
        entry(tar, "PaxHeader", 'x', "${record.length + 4} $record".toByteArray())
        entry(tar, "ignored2", '0', "pax".toByteArray())
        entry(tar, "pax_global_header", 'g', "20 comment=global\n".toByteArray())
        val s = assertIs<Outcome.Success<RootfsInstaller.Summary>>(RootfsInstaller.unpack(gz(tar).inputStream(), vfs(), "rootfs")).value
        assertEquals(emptyList(), s.skipped)
        assertEquals(4, s.files)
        assertEquals(1, s.dirs)
        assertEquals(1, s.symlinks)
        assertEquals("bb", File(dir.toFile(), "rootfs/bin/busybox").readText())
        assertEquals("bb", File(dir.toFile(), "rootfs/bin/ash").readText(), "жёсткая ссылка — копией")
        assertEquals("/bin/busybox", links["rootfs/bin/sh"])
        assertEquals("755".toInt(8), modes["rootfs/bin/busybox"])
        assertEquals("long", File(dir.toFile(), "rootfs/$long").readText())
        assertEquals("pax", File(dir.toFile(), "rootfs/$paxName").readText())
    }

    @Test
    fun entriesOutsideTheRootAreSkipped() {
        val tar = ByteArrayOutputStream()
        entry(tar, "./etc/ok", '0', "ok".toByteArray())
        entry(tar, "../evil", '0', "x".toByteArray())
        entry(tar, "/abs", '0', "x".toByteArray())
        entry(tar, "./out", '2', link = "/")
        entry(tar, "./out/escape", '0', "x".toByteArray())
        val s = assertIs<Outcome.Success<RootfsInstaller.Summary>>(RootfsInstaller.unpack(gz(tar).inputStream(), vfs(), "rootfs")).value
        assertEquals("ok", File(dir.toFile(), "rootfs/etc/ok").readText())
        assertFalse(File(dir.toFile(), "evil").exists())
        assertFalse(File(dir.toFile(), "rootfs/out/escape").exists(), "запись через ссылку архива")
        assertEquals(3, s.skipped.size, s.skipped.toString())
        assertTrue(s.skipped.all { "вне каталога" in it })
    }

    /** Настоящая ссылка на диске; где их нельзя создать (Windows без прав) — проверка пропущена. */
    private fun realLink(link: Path, target: String) {
        link.parent.toFile().mkdirs()
        assumeTrue(runCatching { Files.createSymbolicLink(link, link.fileSystem.getPath(target)) }.isSuccess, "символьные ссылки недоступны")
    }

    /**
     * A-8 аудита безопасности 1 ноября 2026: файл на месте ссылки — вместо
     * неё, а не сквозь неё; жёсткая ссылка из ссылки или через неё — пропуск;
     * ссылка, оставшаяся на диске от оборванной установки, — тоже не дверь.
     */
    @Test
    fun linksAreNeverWrittenThrough() {
        val secret = dir.resolve("secret").also { it.toFile().writeText("s") }
        realLink(dir.resolve("probe"), "secret")
        val tar = ByteArrayOutputStream()
        entry(tar, "./etc/x", '2', link = "../../secret")
        entry(tar, "./etc/x", '0', "new".toByteArray())
        entry(tar, "./etc/y", '2', link = "../../secret")
        entry(tar, "./etc/h", '1', link = "./etc/y")
        entry(tar, "./up", '2', link = "..")
        entry(tar, "./h2", '1', link = "up/secret")
        // Каталог должен разрешать проход и без root: образец проверяет
        // замену ссылки, а не отказ из-за режима обычного файла 0644.
        entry(tar, "./var/", '5', mode = "0000755")
        entry(tar, "./var/log", '0', "log".toByteArray())
        // Оборванная прошлая установка оставила ./var ссылкой наружу.
        val outside = dir.resolve("outside").also { it.toFile().mkdirs() }
        realLink(dir.resolve("rootfs/var"), outside.toString())
        val s = assertIs<Outcome.Success<RootfsInstaller.Summary>>(RootfsInstaller.unpack(gz(tar).inputStream(), NioVfs(dir), "rootfs")).value
        assertEquals("s", secret.toFile().readText(), "цель ссылки не тронута")
        assertFalse(Files.isSymbolicLink(dir.resolve("rootfs/etc/x")))
        assertEquals("new", dir.resolve("rootfs/etc/x").toFile().readText())
        assertTrue(s.skipped.any { it.startsWith("etc/h ") } && s.skipped.any { it.startsWith("h2 ") }, s.skipped.toString())
        assertFalse(Files.isSymbolicLink(dir.resolve("rootfs/var")), "каталог архива — вместо ссылки")
        assertEquals("log", dir.resolve("rootfs/var/log").toFile().readText())
        assertEquals(0, outside.toFile().list()!!.size, "наружу ничего не записано")
    }

    @Test
    fun oversizedNameRecordFailsTheWholeUnpack() {
        val tar = ByteArrayOutputStream()
        entry(tar, "././@LongLink", 'L', ByteArray((RootfsInstaller.MAX_META + 1).toInt()) { 'a'.code.toByte() })
        entry(tar, "ignored", '0', "x".toByteArray())
        val r = RootfsInstaller.unpack(gz(tar).inputStream(), vfs(), "rootfs")
        assertEquals(DiagCode.STORAGE_IO_FAILED, assertIs<Outcome.Failure>(r).errors.first().code, "отказ, а не исключение и не память телефона")
    }

    @Test
    fun truncatedArchiveIsAFailureNotAPartialSuccess() {
        val tar = ByteArrayOutputStream()
        entry(tar, "./etc/file", '0', ByteArray(2000) { 7 })
        val whole = tar.toByteArray()
        val cut = ByteArrayOutputStream().apply { write(whole, 0, 700) }
        val r = RootfsInstaller.unpack(gz(cut, end = false).inputStream(), vfs(), "rootfs")
        assertEquals(DiagCode.STORAGE_IO_FAILED, assertIs<Outcome.Failure>(r).errors.first().code)
    }

    /** Настоящий minirootfs Alpine 3.24.3 из кеша сборки приложения (D-82); его нет — проверка пропущена. */
    @Test
    fun unpacksRealAlpineMinirootfs() {
        val gradleHome = System.getenv("GRADLE_USER_HOME") ?: File(System.getProperty("user.home"), ".gradle").path
        val archive = File(gradleHome, "caches/dpai-linux/alpine-minirootfs-3.24.3-aarch64.tar.gz")
        assumeTrue(archive.isFile, "minirootfs ещё не загружен сборкой приложения")
        val s = archive.inputStream().use { assertIs<Outcome.Success<RootfsInstaller.Summary>>(RootfsInstaller.unpack(it, vfs(), "rootfs")).value }
        // minirootfs 3.24.3: 83 файла, 96 каталогов без корня, 335 символьных ссылок (23 октября 2026).
        assertEquals(emptyList(), s.skipped)
        assertEquals(83, s.files)
        assertEquals(96, s.dirs)
        assertEquals(335, s.symlinks)
        assertEquals("3.24.3", File(dir.toFile(), "rootfs/etc/alpine-release").readText().trim())
        assertEquals("/bin/busybox", links["rootfs/bin/sh"])
    }
}
