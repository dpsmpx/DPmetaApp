package com.dpsmpx.dpai.orchestrator.linux

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Репозитории Alpine гостя (D-193): ветка выпуска или edge, testing только у edge, зеркало прежнее. */
class AlpineRepositoriesTest {
    @TempDir
    lateinit var dir: Path

    private val stable = "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.24/community\n"

    @Test
    fun textAndParse() {
        assertEquals("v3.24", AlpineRepositories.branch("3.24.3\n"))
        assertNull(AlpineRepositories.branch("edge"))
        assertEquals(stable, AlpineRepositories.text(AlpineRepositories.Choice(false, false), "3.24.3").valueOrNull())
        val edge = AlpineRepositories.text(AlpineRepositories.Choice(true, true), "3.24.3").valueOrNull()!!
        assertEquals(3, edge.lines().count { it.contains("/edge/") })
        assertEquals(AlpineRepositories.Choice(false, false), AlpineRepositories.parse(stable.lines() + "# заметка", "3.24.3"))
        assertEquals(AlpineRepositories.Choice(true, true), AlpineRepositories.parse(edge.lines(), "3.24.3"))
        assertNull(AlpineRepositories.parse(listOf("https://mirror.example/alpine/v3.24/main"), "3.24.3"), "правили руками — свой список")
        assertIs<Outcome.Failure>(AlpineRepositories.text(AlpineRepositories.Choice(false, false), null), "выпуск не понятен — не угадываем")
        assertFailsWith<IllegalArgumentException> { AlpineRepositories.Choice(edge = false, testing = true) }
        assertNull(AlpineRepositories.warning(AlpineRepositories.Choice(false, false)))
    }

    @Test
    fun writeOnlyMirrorAddressesAndNotThroughSymlink() {
        val vfs = NioVfs(dir)
        val env = LinuxEnvironment(vfs)
        Files.createDirectories(dir.resolve("linux/rootfs/etc/apk"))
        assertIs<Outcome.Success<Unit>>(env.writeRepositories(stable))
        assertEquals(stable, Files.readString(dir.resolve("linux/rootfs/etc/apk/repositories")))
        val foreign = env.writeRepositories("https://evil.example/alpine/v3.24/main\n")
        assertIs<Outcome.Failure>(foreign)
        assertEquals(DiagCode.VALUE_INVALID, foreign.errors.single().code)
        assertIs<Outcome.Failure>(env.writeRepositories("${AlpineRepositories.MIRROR}/v3.24/main extra\n"))
        // `/etc/apk` гостя подменён ссылкой на файлы приложения — запись не идёт туда.
        Files.writeString(dir.resolve("secret"), "токен")
        Files.createDirectories(dir.resolve("app-dir"))
        Files.walk(dir.resolve("linux/rootfs/etc/apk")).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        assumeTrue(runCatching { Files.createSymbolicLink(dir.resolve("linux/rootfs/etc/apk"), dir.resolve("app-dir")) }.isSuccess)
        val r = env.writeRepositories(stable)
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, r.errors.single().code)
        assertEquals(0, Files.list(dir.resolve("app-dir")).use { it.count() }.toInt())
    }

    @Test
    fun upgradeAvailableCommand() {
        assertEquals(listOf("apk", "upgrade", "-U", "--available", "--no-progress"), LinuxEnvironment.apk(LinuxEnvironment.Apk.UPGRADE_AVAILABLE, emptyList()).valueOrNull()!!.command)
    }
}
