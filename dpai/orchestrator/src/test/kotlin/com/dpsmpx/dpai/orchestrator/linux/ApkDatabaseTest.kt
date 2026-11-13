package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * База пакетов гостя (D-158): повреждённые пакеты — по полю `f:`, как их пишет apk-tools 3.
 * Образец — синтетический, в формате `lib/apk/db/installed` Alpine 3.24 (поле `f:f`
 * проверено на машине сборки: `apk add` после него — «1 error», `apk fix` — исправно,
 * `docs/research/LINUX_IN_APP.md`, §11).
 */
class ApkDatabaseTest {
    @TempDir
    lateinit var dir: Path

    private val sample = """
        C:Q1aaaa=
        P:musl
        V:1.2.6-r3
        A:aarch64
        R:ld-musl-aarch64.so.1

        C:Q1bbbb=
        P:binutils
        V:2.45.1-r1
        A:aarch64
        f:f
        F:usr/bin
        R:ar

        C:Q1cccc=
        P:gcc
        V:15.2.0-r5
        f:fs

        P:busybox
        V:1.37.0-r31
        f:x

        P:Bad;name
        f:f
    """.trimIndent()

    @Test
    fun brokenPackagesComeFromTheFlagField() {
        val broken = ApkDatabase.broken(sample)
        assertEquals(listOf("binutils", "gcc", "busybox"), broken.map { it.name }, "имя вне правила пакета — пропуск")
        assertEquals(ApkDatabase.Broken("binutils", "2.45.1-r1", files = true, script = false, xattrs = false), broken[0])
        assertEquals("не все файлы, скрипт пакета", broken[1].why)
        assertEquals("атрибуты файлов", broken[2].why)
        assertEquals(setOf("musl", "binutils", "gcc", "busybox", "Bad;name"), ApkDatabase.installed(sample))
        assertTrue(ApkDatabase.broken("P:git\nV:2.54.1-r0\n\r\n").isEmpty())
        assertTrue(ApkDatabase.broken("").isEmpty())
    }

    @Test
    fun readsTheGuestFileAndRefusesLinks() {
        val env = LinuxEnvironment(NioVfs(dir))
        assertEquals(emptyList(), env.brokenPackages().valueOrNull(), "базы нет — пусто")
        val db = dir.resolve("linux/rootfs/lib/apk/db").also { Files.createDirectories(it) }
        db.resolve("installed").toFile().writeText(sample.replace("\n", "\r\n"))
        assertEquals(listOf("binutils", "gcc", "busybox"), env.brokenPackages().valueOrNull()!!.map { it.name })

        db.toFile().deleteRecursively()
        val apkDir = dir.resolve("linux/rootfs/lib/apk")
        assumeTrue(runCatching { Files.createSymbolicLink(apkDir.resolve("db"), apkDir.fileSystem.getPath("../../..")) }.isSuccess, "символьные ссылки недоступны")
        val r = ApkDatabase.read(NioVfs(dir), "linux/rootfs")
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, r.errors.single().code)
        assertNull(r.valueOrNull())
    }
}
