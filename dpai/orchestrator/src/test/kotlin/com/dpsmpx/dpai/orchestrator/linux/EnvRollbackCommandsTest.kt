package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Команды и файлы отката окружения (D-194): world, кеш apk, точные версии. */
class EnvRollbackCommandsTest {
    @TempDir
    lateinit var dir: Path

    private fun env(): LinuxEnvironment {
        Files.createDirectories(dir.resolve("linux/rootfs/etc/apk"))
        return LinuxEnvironment(NioVfs(dir))
    }

    @Test
    fun worldIsWrittenOnlyAsConstraints() {
        val e = env()
        assertIs<Outcome.Success<Unit>>(e.writeWorld(listOf("busybox", "git", "bash><Q1LOlgbxwxQ4KXtA31h12862avtnU=", "python3~3.12")))
        assertEquals("busybox\ngit\nbash><Q1LOlgbxwxQ4KXtA31h12862avtnU=\npython3~3.12\n", Files.readString(dir.resolve("linux/rootfs/etc/apk/world")))
        for (bad in listOf(listOf("git; rm -rf /"), listOf("a b"), listOf(""))) assertIs<Outcome.Failure>(e.writeWorld(bad), bad.toString())
    }

    @Test
    fun cacheLinkIsCreatedOnceAndForeignSetupIsKept() {
        val e = env()
        assertEquals(true, e.enableCache().valueOrNull())
        assertTrue(Files.isSymbolicLink(dir.resolve("linux/rootfs/etc/apk/cache")))
        assertEquals("/var/cache/apk", Files.readSymbolicLink(dir.resolve("linux/rootfs/etc/apk/cache")).toString())
        assertEquals(true, e.enableCache().valueOrNull(), "второй раз — то же")
        assertEquals(true, e.disableCache().valueOrNull())
        assertFalse(Files.exists(dir.resolve("linux/rootfs/etc/apk/cache"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(dir.resolve("linux/rootfs/var/cache/apk")), "каталог кеша не тронут")
        assertEquals(true, e.enableCache().valueOrNull())
        Files.writeString(dir.resolve("linux/rootfs/var/cache/apk/bash-5.3.9-r1.2ce9606f.apk"), "pkg")
        Files.writeString(dir.resolve("linux/rootfs/var/cache/apk/APKINDEX.630cb808.tar.gz"), "idx")
        assertEquals(listOf("bash-5.3.9-r1.2ce9606f.apk"), e.cachedPackages(), "индексы — не файлы пакетов")
        assertEquals(1, e.dropCached(listOf("bash-5.3.9-r1.2ce9606f.apk", "APKINDEX.630cb808.tar.gz", "../../secret")).valueOrNull())
        assertTrue(Files.exists(dir.resolve("linux/rootfs/var/cache/apk/APKINDEX.630cb808.tar.gz")))
        assertFalse(Files.exists(dir.resolve("linux/rootfs/var/cache/apk/bash-5.3.9-r1.2ce9606f.apk")))
        // Человек сам сделал /etc/apk/cache каталогом — не трогаем.
        Files.delete(dir.resolve("linux/rootfs/etc/apk/cache"))
        Files.createDirectories(dir.resolve("linux/rootfs/etc/apk/cache"))
        assertEquals(false, e.enableCache().valueOrNull())
    }

    @Test
    fun symlinkedVarIsRefused() {
        val e = env()
        Files.createDirectories(dir.resolve("app-private"))
        assumeTrue(runCatching { Files.createSymbolicLink(dir.resolve("linux/rootfs/var"), dir.resolve("app-private")) }.isSuccess)
        val r = e.enableCache()
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, r.errors.single().code)
        assertEquals(emptyList(), e.cachedPackages())
        assertEquals(0, Files.list(dir.resolve("app-private")).use { it.count() }.toInt())
    }

    @Test
    fun commandsValidateNamesVersionsAndFiles() {
        assertEquals(listOf("apk", "fix", "-U", "--no-progress"), LinuxEnvironment.apk(LinuxEnvironment.Apk.APPLY_WORLD, emptyList()).valueOrNull()!!.command)
        assertEquals(listOf("apk", "upgrade", "-U", "--available", "--no-progress", "git"), LinuxEnvironment.apk(LinuxEnvironment.Apk.UPGRADE_AVAILABLE_SOME, listOf("git")).valueOrNull()!!.command)
        assertEquals(listOf("apk", "add", "-U", "--no-progress", "/var/cache/apk/bash-5.3.9-r1.2ce9606f.apk"), LinuxEnvironment.apkAddCached(listOf("bash-5.3.9-r1.2ce9606f.apk")).valueOrNull()!!.command)
        assertIs<Outcome.Failure>(LinuxEnvironment.apkAddCached(listOf("../../etc/passwd")))
        assertIs<Outcome.Failure>(LinuxEnvironment.apkAddCached(emptyList()))
        assertEquals(listOf("apk", "add", "-U", "--no-progress", "git=2.54.0-r0"), LinuxEnvironment.apkPin("git", "2.54.0-r0").valueOrNull()!!.command)
        assertIs<Outcome.Failure>(LinuxEnvironment.apkPin("git", "2.54.0 -r0"))
        assertIs<Outcome.Failure>(LinuxEnvironment.apkPin("-git", "1.0-r0"))
    }
}
