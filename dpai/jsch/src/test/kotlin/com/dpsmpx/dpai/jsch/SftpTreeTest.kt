package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.TreeArtifacts
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Файлы ПК по SFTP (D-162) на двойнике сервера: пути Windows, ссылки и негодные
 * имена не видны, пределы, куски и отмена, только чтение, поиск артефактов по
 * шаблонам; живой SFTP — на настоящем target (`-Ddpai.test.ssh`).
 */
class SftpTreeTest {
    /** Сервер-двойник: файлы — путь → байты; ссылки — отдельно; [opened] — что читали. */
    private class FakeSftp(initial: Map<String, ByteArray>, private val links: Set<String> = emptySet(), val renameOver: Boolean = true) : SftpOps {
        val files: MutableMap<String, ByteArray> = initial.toMutableMap()
        val listed = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var closed = false

        /** Сколько следующих вызовов кончатся обрывом связи. */
        var drops = 0
        private val made = mutableSetOf<String>()
        private val dirs: Set<String> get() = files.keys.flatMap { f -> generateSequence(f.substringBeforeLast('/', "")) { it.substringBeforeLast('/', "").takeIf { p -> p.isNotEmpty() } }.toList() }.toSet() + made

        private fun net() { if (drops > 0) { drops--; throw java.io.IOException("Connection reset") } }

        override fun write(path: String, input: InputStream) { net(); calls += "write $path"; files[path] = input.readBytes() }

        override fun rename(from: String, to: String) {
            calls += "rename $from"
            if (to in files && !renameOver) throw java.io.IOException("Failure")
            files[to] = files.remove(from) ?: throw SftpOps.NoSuchFile(from)
        }

        override fun remove(path: String) { calls += "remove $path"; files.remove(path) ?: throw SftpOps.NoSuchFile(path) }

        override fun mkdir(path: String) { calls += "mkdir $path"; made += path }

        override fun realpath(path: String): String = if (path == ".") "/C:/Users/me" else throw SftpOps.NoSuchFile(path)

        override fun list(path: String): List<SftpOps.Entry> {
            net()
            listed += path
            if (path !in dirs) throw SftpOps.NoSuchFile(path)
            val prefix = "$path/"
            val names = (files.keys + dirs + links).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.toSet()
            return listOf(SftpOps.Entry(".", true, false, 0, 0), SftpOps.Entry("..", true, false, 0, 0)) + names.map { stat("$prefix$it") }
        }

        override fun stat(path: String): SftpOps.Entry = when (path) {
            in links -> SftpOps.Entry(path.substringAfterLast('/'), false, true, 10, 1)
            in dirs -> SftpOps.Entry(path.substringAfterLast('/'), true, false, 0, 1_700_000_000)
            in files -> SftpOps.Entry(path.substringAfterLast('/'), false, false, files.getValue(path).size.toLong(), 1_700_000_000)
            else -> throw SftpOps.NoSuchFile(path)
        }

        override fun open(path: String): InputStream = ByteArrayInputStream(files[path] ?: throw SftpOps.NoSuchFile(path))

        override fun close() { closed = true }
    }

    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    @Test
    fun windowsPathsAreSftpPaths() {
        assertEquals("/C:/Users/me/proj", SftpTree.remotePath(TargetPlatform.WINDOWS, "C:\\Users\\me\\proj"))
        assertEquals("/D:/w", SftpTree.remotePath(TargetPlatform.WINDOWS, "D:/w"))
        assertEquals("/home/me/proj", SftpTree.remotePath(TargetPlatform.POSIX, "/home/me/proj"))
        assertEquals("proj", SftpTree.remotePath(TargetPlatform.POSIX, "proj"))
    }

    @Test
    fun homeIsServerPath() {
        assertEquals("/C:/Users/me", assertIs<Outcome.Success<String>>(SftpTree(FakeSftp(emptyMap()), "", TargetPlatform.WINDOWS).home()).value)
        // Сервер без realpath (двойник по умолчанию) — отказ, а не пустой путь.
        val bare = object : SftpOps {
            override fun list(path: String) = emptyList<SftpOps.Entry>()
            override fun stat(path: String) = throw SftpOps.NoSuchFile(path)
            override fun open(path: String) = throw SftpOps.NoSuchFile(path)
            override fun write(path: String, input: InputStream) = Unit
            override fun rename(from: String, to: String) = Unit
            override fun remove(path: String) = Unit
            override fun mkdir(path: String) = Unit
            override fun close() = Unit
        }
        assertIs<Outcome.Failure>(SftpTree(bare, "", TargetPlatform.POSIX).home())
    }

    @Test
    fun listsWithoutLinksAndBadNamesAndFindsArtifacts() {
        val apk = ByteArray(3 * SftpTree.CHUNK + 17) { (it % 251).toByte() }
        val fake = FakeSftp(
            mapOf(
                "/C:/p/app/build/outputs/apk/debug/app-debug.apk" to apk,
                "/C:/p/app/src/Main.kt" to "fun main() {}".toByteArray(),
                "/C:/p/.gradle/cache/x.apk" to ByteArray(4),
                "/C:/p/bad\u0001name.apk" to ByteArray(4),
            ),
            links = setOf("/C:/p/link.apk"),
        )
        val tree = SftpTree(fake, "C:\\p", TargetPlatform.WINDOWS)
        val top = ok(tree.list("")).map { it.name }.sorted()
        assertEquals(listOf(".gradle", "app"), top, "без ссылки, негодного имени и «.»/«..»")
        val found = ok(TreeArtifacts.find(tree, TreeArtifacts.DEFAULT))
        assertEquals(listOf("app/build/outputs/apk/debug/app-debug.apk"), found.items.map { it.path })
        assertEquals(apk.size.toLong(), found.items.single().size)

        val out = ByteArrayOutputStream()
        val chunks = mutableListOf<Int>()
        assertEquals(apk.size.toLong(), ok(tree.copy("app/build/outputs/apk/debug/app-debug.apk", 64L shl 20) { b, n -> chunks += n; out.write(b, 0, n) }))
        assertTrue(apk.contentEquals(out.toByteArray()))
        assertTrue(chunks.all { it <= SftpTree.CHUNK } && chunks.size >= 4, chunks.toString())
        assertEquals(apk.size, ok(tree.meta("app/build/outputs/apk/debug/app-debug.apk")).size.toInt())

        tree.close()
        assertTrue(fake.closed)
    }

    @Test
    fun limitsCancelReadOnlyAndMissingFiles() {
        val fake = FakeSftp(mapOf("/srv/p/big.apk" to ByteArray(2 * SftpTree.CHUNK)), links = setOf("/srv/p/link.apk"))
        val tree = SftpTree(fake, "/srv/p", TargetPlatform.POSIX)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(tree.copy("big.apk", SftpTree.CHUNK.toLong()) { _, _ -> }).diagnostics.first().code)
        var reads = 0
        val cancelled = assertIs<Outcome.Failure>(tree.copy("big.apk", 64L shl 20, cancelled = { reads > 0 }) { _, _ -> reads++ })
        assertEquals(DiagCode.CANCELLED_BY_USER, cancelled.diagnostics.first().code)
        assertEquals(1, reads)
        assertEquals(DiagCode.ENTITY_NOT_FOUND, assertIs<Outcome.Failure>(tree.copy("none.apk", 1L shl 20) { _, _ -> }).diagnostics.first().code)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(tree.copy("link.apk", 1L shl 20) { _, _ -> }).diagnostics.first().code, "ссылка не читается")
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(tree.list("../etc")).diagnostics.first().code)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, assertIs<Outcome.Failure>(tree.write("x.txt", ByteArray(1))).diagnostics.first().code, "без writable — только чтение")
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, assertIs<Outcome.Failure>(tree.delete("big.apk")).diagnostics.first().code)
        assertEquals(2 * SftpTree.CHUNK, ok(tree.read("big.apk", 64L shl 20)).size)
    }

    /** Запись (D-166): временный файл и переименование поверх; Windows — удалить и переименовать; каталоги создаются. */
    @Test
    fun writeGoesThroughTempFileAndCreatesDirs() {
        val posix = FakeSftp(mapOf("/srv/p/src/a.txt" to "old".toByteArray()))
        val tree = SftpTree(posix, "/srv/p", TargetPlatform.POSIX, writable = true)
        assertEquals(3L, ok(tree.write("src/a.txt", "new".toByteArray())).size)
        assertEquals("new", String(posix.files.getValue("/srv/p/src/a.txt")))
        assertTrue(posix.calls.first().startsWith("write /srv/p/src/.dpai-tmp-a.txt"), posix.calls.toString())
        assertTrue(posix.calls.none { it.startsWith("remove") }, "переименование поверх — без удаления")
        assertTrue(posix.files.keys.none { "/.dpai-tmp-" in it }, "временного не осталось")
        ok(tree.write("new/deep/b.txt", "b".toByteArray()))
        assertTrue("mkdir /srv/p/new" in posix.calls && "mkdir /srv/p/new/deep" in posix.calls, posix.calls.toString())
        assertEquals(listOf("a.txt"), ok(tree.list("src")).map { it.name }, "временный файл в списке не виден")
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, assertIs<Outcome.Failure>(tree.delete("src/a.txt")).diagnostics.first().code)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(tree.write("../x", ByteArray(1))).diagnostics.first().code)

        val windows = FakeSftp(mapOf("/C:/p/a.txt" to "old".toByteArray()), renameOver = false)
        ok(SftpTree(windows, "C:\\p", TargetPlatform.WINDOWS, writable = true).write("a.txt", "new".toByteArray()))
        assertEquals("new", String(windows.files.getValue("/C:/p/a.txt")))
        assertTrue("remove /C:/p/a.txt" in windows.calls, windows.calls.toString())
        assertTrue(windows.files.keys.none { "/.dpai-tmp-" in it })
    }

    /** Одно подключение на экран и переподключение после обрыва (D-166). */
    @Test
    fun reopeningTreeReconnectsOnceAfterDrop() {
        var opened = 0
        val fake = FakeSftp(mapOf("/srv/p/a.txt" to ByteArray(1)))
        val tree = ReopeningSftpTree { opened++; Outcome.success(SftpTree(fake, "/srv/p", TargetPlatform.POSIX, writable = true)) }
        ok(tree.list(""))
        ok(tree.list(""))
        assertEquals(1, opened, "одно подключение на несколько действий")
        fake.drops = 1
        ok(tree.list(""))
        assertEquals(2, opened, "обрыв — новое подключение и повтор")
        fake.drops = 2
        assertEquals(DiagCode.SSH_CONNECTION_FAILED, assertIs<Outcome.Failure>(tree.list("")).diagnostics.first().code, "повтор — один")
        assertEquals(DiagCode.ENTITY_NOT_FOUND, assertIs<Outcome.Failure>(tree.list("none")).diagnostics.first().code)
        tree.close()
        assertTrue(fake.closed)
    }

    /** Настоящий SFTP target (`-Ddpai.test.ssh=user@host:port:posix|windows`, `.identity`, `.knownHosts`, `.sftp.root`). */
    @Test
    fun realTargetListsAndCopies() {
        val spec = System.getProperty("dpai.test.ssh")
        val root = System.getProperty("dpai.test.ssh.sftp.root")
        assumeTrue(spec != null && root != null, "dpai.test.ssh и dpai.test.ssh.sftp.root не заданы: проверка SFTP — на настоящем target")
        val (userHost, port, platform) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            identityFile = System.getProperty("dpai.test.ssh.identity"), knownHostsFile = System.getProperty("dpai.test.ssh.knownHosts"),
        ).valueOrNull()!!
        val tree = ok(SftpTree.open(endpoint, if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX, root!!, emptyList()))
        tree.use {
            val entries = ok(it.list(""))
            val file = entries.firstOrNull { e -> !e.dir } ?: return
            var total = 0L
            assertEquals(file.size, ok(it.copy(file.name, 1L shl 30) { _, n -> total += n }))
            assertEquals(file.size, total)
        }
    }
}
