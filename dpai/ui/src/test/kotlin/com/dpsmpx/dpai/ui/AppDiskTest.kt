package com.dpsmpx.dpai.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Диск приложения (D-96): секретов нет ни в списках, ни по прямому пути;
 * запись — только в `/root` гостя Linux; символьные ссылки не проходятся и
 * не открываются — ни заранее, ни подменой после проверки.
 */
class AppDiskTest {
    @TempDir
    lateinit var root: Path

    private fun file(rel: String, text: String = "x") = root.resolve(rel).also { it.parent.toFile().mkdirs(); it.toFile().writeText(text) }

    @Test
    fun secretsAreNeitherListedNorReachable() {
        file("control-token", "TOKEN")
        file("live/live.journal")
        file("journal-copies/live.journal.seg-000000000001")
        file("${AppDisk.LINUX_HOME}/.claude-slot-1/.credentials.json")
        file("${AppDisk.LINUX_HOME}/.claude-slot-1/settings.json")
        file("${AppDisk.LINUX_HOME}/.ssh/id_ed25519")
        file("${AppDisk.LINUX_HOME}/.claude/.credentials.json")
        file("${AppDisk.LINUX_HOME}/project/main.kt")
        file("${AppDisk.LINUX_HOME}/.config/llama/api-key", "KEY")
        file("${AppDisk.LINUX_HOME}/.config/other/settings")

        assertEquals(listOf("journal-copies", "linux", "live"), AppDisk.children(root, ""), "токена нет в списке")
        assertEquals(listOf("project"), AppDisk.children(root, AppDisk.LINUX_HOME), "скрытые конфиги гостя закрыты по умолчанию")
        assertEquals(emptyList(), AppDisk.children(root, "${AppDisk.LINUX_HOME}/.config"), "конфиги не открываются и по прямому пути")
        assertEquals(emptyList(), AppDisk.children(root, "${AppDisk.LINUX_HOME}/.claude"), ".credentials.json — не виден нигде")
        for (secret in listOf("control-token", "${AppDisk.LINUX_HOME}/.config/llama/api-key", "${AppDisk.LINUX_HOME}/.claude-slot-1/settings.json", "${AppDisk.LINUX_HOME}/.ssh/id_ed25519", "${AppDisk.LINUX_HOME}/.claude/.credentials.json")) {
            assertNull(AppDisk.resolve(root, secret), "по прямому пути — тоже нет: $secret")
        }
        assertNotNull(AppDisk.resolve(root, "live/live.journal"))
        assertNull(AppDisk.resolve(root, "../outside"), "выход из корня")
    }

    @Test
    fun credentialsAndHistoriesAreHiddenAlsoInsideProjects() {
        for (name in listOf(".git-credentials", ".netrc", ".npmrc", ".pypirc", ".codex/auth.json", ".gemini/auth.json",
            ".claude.json", ".gnupg/key", ".bash_history", ".ash_history", ".env", ".env.local", ".config/gh/hosts.yml")) {
            for (parent in listOf(AppDisk.LINUX_HOME, "${AppDisk.LINUX_HOME}/project")) {
                val relative = "$parent/$name"
                file(relative)
                assertTrue(AppDisk.hidden(relative), relative)
                assertNull(AppDisk.resolve(root, relative), relative)
                assertFalse(AppDisk.writable(relative), relative)
            }
        }
        for (name in listOf(".gitignore", ".gitattributes", ".editorconfig")) {
            val relative = "${AppDisk.LINUX_HOME}/project/$name"
            file(relative)
            assertNotNull(AppDisk.resolve(root, relative))
        }
    }

    @Test
    fun writingOnlyInLinuxHome() {
        assertTrue(AppDisk.writable("${AppDisk.LINUX_HOME}/project/main.kt"))
        assertTrue(AppDisk.canCreateIn(AppDisk.LINUX_HOME))
        for (ro in listOf("live/live.journal", "journal-copies/x", "targets.json", "linux/rootfs/etc/resolv.conf", "${AppDisk.LINUX_HOME}/.ssh/config")) {
            assertFalse(AppDisk.writable(ro), "только чтение: $ro")
        }
        assertFalse(AppDisk.canCreateIn("live"))
        assertFalse(AppDisk.validName(".ssh"), "секрет не создать")
        assertFalse(AppDisk.validName(".claude-slot-3"))
        assertFalse(AppDisk.validName("a/b"))
        assertTrue(AppDisk.validName("notes.txt"))
    }

    @Test
    fun symlinksAreNotFollowed() {
        file("control-token", "TOKEN")
        file("${AppDisk.LINUX_HOME}/ok.txt")
        val home = root.resolve(AppDisk.LINUX_HOME)
        assumeTrue(runCatching { Files.createSymbolicLink(home.resolve("token"), home.fileSystem.getPath("../../../control-token")) }.isSuccess, "символьные ссылки недоступны")
        Files.createSymbolicLink(home.resolve("up"), home.fileSystem.getPath("../../.."))
        assertEquals(listOf("ok.txt"), AppDisk.children(root, AppDisk.LINUX_HOME), "ссылки гостя не показываются")
        assertNull(AppDisk.resolve(root, "${AppDisk.LINUX_HOME}/token"))
        assertNull(AppDisk.resolve(root, "${AppDisk.LINUX_HOME}/up/control-token"), "через ссылку-каталог — тоже нет")
        // Подмена после проверки: настоящий путь открытого файла сверяется ещё раз.
        assertEquals("секрет — на диск не выводится", AppDisk.refuseOpened(root, root.toRealPath().resolve("control-token").toString()))
        assertEquals("файл вне данных приложения", AppDisk.refuseOpened(root, "/etc/passwd"))
        assertNull(AppDisk.refuseOpened(root, root.toRealPath().resolve("${AppDisk.LINUX_HOME}/ok.txt").toString()))
    }
}
