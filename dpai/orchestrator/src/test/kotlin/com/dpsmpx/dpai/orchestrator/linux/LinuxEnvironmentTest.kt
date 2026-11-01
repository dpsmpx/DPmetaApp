package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.ProotHost
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Linux-среда в приложении, шаг 5 D-75: установка один раз, DNS гостя, пакеты только по именам. */
class LinuxEnvironmentTest {
    @TempDir
    lateinit var dir: Path

    private fun vfs(): Vfs = object : Vfs by NioVfs(dir) {
        override fun symlink(path: String, target: String) = Unit

        override fun setMode(path: String, mode: Int) = Unit
    }

    /** Архив ustar в gzip из файлов [files]; [cut] — оборвать после первого заголовка. */
    private fun archive(files: Map<String, String>, cut: Boolean = false): ByteArray {
        val tar = ByteArrayOutputStream()
        for ((name, text) in files) {
            val body = text.toByteArray()
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644".toByteArray().copyInto(h, 100)
            "%011o".format(body.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            tar.write(h)
            tar.write(body)
            tar.write(ByteArray((512 - body.size % 512) % 512))
        }
        tar.write(ByteArray(1024))
        val bytes = if (cut) tar.toByteArray().copyOf(600) else tar.toByteArray()
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
    }

    @Test
    fun installsOnceAndMarksReadyLast() {
        val env = LinuxEnvironment(vfs())
        assertNull(env.installed(), "до установки — не готова")
        val r = env.install("alpine-3.24.3") { ByteArrayInputStream(archive(mapOf("etc/alpine-release" to "3.24.3\n"))) }
        assertIs<Outcome.Success<*>>(r, r.diagnostics.toString())
        assertEquals("alpine-3.24.3", env.installed())
        assertTrue(dir.resolve("linux/rootfs/etc/alpine-release").toFile().isFile)

        // Готовая среда второй раз не распаковывается: в ней пакеты и вход человека.
        var opened = false
        val again = env.install("alpine-3.25.0") { opened = true; ByteArrayInputStream(ByteArray(0)) }
        assertNull(again.valueOrNull())
        assertFalse(opened, "архив не открывается")
        assertEquals("alpine-3.24.3", env.installed())
    }

    @Test
    fun brokenArchiveLeavesNoReadyMark() {
        val env = LinuxEnvironment(vfs())
        val r = env.install("alpine-3.24.3") { ByteArrayInputStream(archive(mapOf("etc/a" to "x".repeat(2000)), cut = true)) }
        assertEquals(DiagCode.STORAGE_IO_FAILED, (r as Outcome.Failure).errors.first().code)
        assertNull(env.installed(), "оборванная установка — не готова: в следующий раз — заново")
        // И повтор с целым архивом доводит её до конца.
        assertTrue(env.install("alpine-3.24.3") { ByteArrayInputStream(archive(mapOf("etc/a" to "x"))) }.isSuccess)
        assertEquals("alpine-3.24.3", env.installed())
    }

    @Test
    fun dnsFromPhoneNetwork() {
        val env = LinuxEnvironment(vfs())
        assertEquals(2, env.writeDns(listOf("192.168.1.1", "fe80::1%wlan0", "192.168.1.1", "мусор", "999.1.1.1")).valueOrNull())
        assertEquals("nameserver 192.168.1.1\nnameserver fe80::1\n", dir.resolve("linux/rootfs/etc/resolv.conf").toFile().readText())
        val none = env.writeDns(emptyList())
        assertIs<Outcome.Failure>(none, "сети нет — файл не трогается")
        assertEquals("nameserver 192.168.1.1\nnameserver fe80::1\n", dir.resolve("linux/rootfs/etc/resolv.conf").toFile().readText())
        assertFalse(LinuxEnvironment.isIpLiteral("01.2.3.4"), "ведущий ноль — не адрес")
        assertTrue(LinuxEnvironment.isIpLiteral("2001:db8::53"))
    }

    @Test
    fun packagesOnlyByNameAndApkWithoutProc() {
        val add = LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, listOf("git", " nodejs ", "npm", "")).valueOrNull()!!
        assertEquals(listOf("apk", "add", "-U", "--no-progress", "git", "nodejs", "npm"), add.command)
        assertTrue(ProotHost.needsNoProc(add.command), "apk — без /proc у гостя (D-82)")
        // Ключ под видом имени и команда оболочки — отказ, а не подстановка.
        for (bad in listOf("--allow-untrusted", "git;rm", "Git", "pkg name", "../x")) {
            assertEquals(DiagCode.VALUE_INVALID, (LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, listOf(bad)) as Outcome.Failure).errors.single().code, bad)
        }
        assertEquals(DiagCode.FIELD_MISSING, (LinuxEnvironment.apk(LinuxEnvironment.Apk.DEL, emptyList()) as Outcome.Failure).errors.single().code)
        assertEquals(listOf("apk", "upgrade", "-U", "--no-progress"), LinuxEnvironment.apk(LinuxEnvironment.Apk.UPGRADE, emptyList()).valueOrNull()!!.command)
        assertEquals("/root/.claude-slot-2", LinuxEnvironment.slotDir(2))
    }
}
