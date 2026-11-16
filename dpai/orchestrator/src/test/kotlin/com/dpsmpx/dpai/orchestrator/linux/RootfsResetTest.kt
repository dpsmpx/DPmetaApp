package com.dpsmpx.dpai.orchestrator.linux

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

/** Сброс Alpine целиком (D-196): дом сохраняется, корень — заново, ссылки не проходятся. */
class RootfsResetTest {
    @TempDir
    lateinit var dir: Path

    private fun file(rel: String, text: String = "x") = dir.resolve(rel).also { Files.createDirectories(it.parent); Files.writeString(it, text) }

    private fun rootfs() {
        file("linux/rootfs.ready", "alpine-minirootfs-3.24.3\n")
        file("linux/rootfs/bin/busybox", "bb")
        assumeTrue(runCatching { Files.createSymbolicLink(dir.resolve("linux/rootfs/bin/sh"), dir.fileSystem.getPath("/bin/busybox")) }.isSuccess)
        file("linux/rootfs/usr/local/lib/node_modules/@anthropic-ai/claude-code/package.json", "{}")
        file("linux/rootfs/root/projects/local/hello/main.cpp", "int main(){}")
        file("linux/rootfs/root/ai/claude-1/slot-note.txt", "вход")
        file("linux/rootfs/root/tools/models/m.gguf", "модель")
        file("linux/rootfs/root/.profile", "export A=1")
        file("linux/rootfs/var/cache/apk/bash-5.3.9-r1.2ce9606f.apk", "пакет")
        file("secret", "токен приложения")
        Files.createSymbolicLink(dir.resolve("linux/rootfs/root/projects/leak"), dir.resolve("secret"))
    }

    /** Распаковка заново — как RootfsInstaller: свежий пустой /root и система. */
    private fun unpack() {
        file("linux/rootfs/bin/busybox", "bb-new")
        Files.createDirectories(dir.resolve("linux/rootfs/root"))
        file("linux/rootfs.ready", "alpine-minirootfs-3.24.3\n")
    }

    @Test
    fun keepsProjectsAndAiOnly() {
        rootfs()
        val r = RootfsReset(NioVfs(dir), dir)
        assertIs<Outcome.Success<Int>>(r.begin(wholeHome = false))
        assertFalse(Files.exists(dir.resolve("linux/rootfs.ready")), "готовность снята первой")
        assertFalse(Files.exists(dir.resolve("linux/rootfs")), "корень удалён")
        assertTrue(r.pending())
        assertEquals("токен приложения", Files.readString(dir.resolve("secret")), "цель ссылки не тронута")
        unpack()
        assertEquals(listOf("~/projects", "~/ai"), r.restore().valueOrNull())
        assertEquals("int main(){}", Files.readString(dir.resolve("linux/rootfs/root/projects/local/hello/main.cpp")))
        assertEquals("вход", Files.readString(dir.resolve("linux/rootfs/root/ai/claude-1/slot-note.txt")))
        assertFalse(Files.exists(dir.resolve("linux/rootfs/root/tools")), "остальное в доме — не сохранялось")
        assertFalse(Files.exists(dir.resolve("linux/rootfs/usr/local/lib/node_modules")), "пакеты npm ушли вместе с корнем")
        assertFalse(r.pending())
        assertEquals("пакет", Files.readString(dir.resolve("linux/rootfs/var/cache/apk/bash-5.3.9-r1.2ce9606f.apk")), "кеш apk пережил сброс")
    }

    @Test
    fun keepsWholeHome() {
        rootfs()
        val r = RootfsReset(NioVfs(dir), dir)
        r.begin(wholeHome = true)
        unpack()
        assertEquals(listOf("~"), r.restore().valueOrNull())
        assertEquals("модель", Files.readString(dir.resolve("linux/rootfs/root/tools/models/m.gguf")))
        assertEquals("export A=1", Files.readString(dir.resolve("linux/rootfs/root/.profile")))
        assertEquals("bb-new", Files.readString(dir.resolve("linux/rootfs/bin/busybox")))
    }

    @Test
    fun interruptedResetIsResumedAndConflictsAreNotOverwritten() {
        rootfs()
        val r = RootfsReset(NioVfs(dir), dir)
        r.begin(wholeHome = false)
        // Обрыв и повтор: отложенное не перезаписывается.
        assertIs<Outcome.Success<Int>>(r.begin(wholeHome = false))
        unpack()
        file("linux/rootfs/root/projects/new.txt", "новое после распаковки")
        assertEquals(listOf("~/projects", "~/ai"), r.restore().valueOrNull())
        assertEquals("новое после распаковки", Files.readString(dir.resolve("linux/rootfs/root/projects/new.txt")))
        assertEquals("int main(){}", Files.readString(dir.resolve("linux/rootfs/root/restored-projects/local/hello/main.cpp")))
        assertEquals(listOf("~"), RootfsReset(NioVfs(dir), dir).keeps(true))
    }
}
