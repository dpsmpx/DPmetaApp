package com.dpsmpx.dpai.files

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Дерево в памяти: время изменения — счётчик записей, как у файловой системы — новое при каждой записи. */
class MemoryTree(files: Map<String, String> = emptyMap()) : FileTree {
    val data = LinkedHashMap<String, Pair<ByteArray, Long>>()
    private var clock = 1000L
    var failWrites = false

    init {
        files.forEach { (p, t) -> put(p, t) }
    }

    fun put(path: String, text: String) {
        data[path] = text.toByteArray() to clock++
    }

    fun text(path: String): String? = data[path]?.first?.toString(Charsets.UTF_8)

    fun scan(): Map<String, FileMeta> = FileTrees.scan(this).valueOrNull()!!.files

    override fun list(dir: String): Outcome<List<FileEntry>> {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val names = data.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val files = names.filter { '/' !in it }.map { n -> data.getValue(prefix + n).let { (b, m) -> FileEntry(n, false, b.size.toLong(), m) } }
        val dirs = names.filter { '/' in it }.map { it.substringBefore('/') }.distinct().map { FileEntry(it, true, 0, 0) }
        return Outcome.success(dirs + files)
    }

    override fun read(path: String, maxBytes: Long): Outcome<ByteArray> =
        data[path]?.first?.let { Outcome.success(it) } ?: Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "нет файла", path)

    override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> {
        if (failWrites) return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "запись запрещена", path)
        data[path] = bytes to clock++
        return Outcome.success(FileMeta(bytes.size.toLong(), data.getValue(path).second))
    }

    override fun delete(path: String): Outcome<Unit> =
        if (data.remove(path) != null) Outcome.success(Unit) else Outcome.failure(DiagCode.STORAGE_UNAVAILABLE, "нет файла", path)
}

/** Синхронизация папки и копии (D-99): ничего не затирается молча, удаление в папке — только с отметкой. */
class FolderSyncTest {
    @Test
    fun stalePlansCannotOverwriteDeleteOrAdoptNewerFiles() {
        for (action in listOf(SyncAction.TO_LOCAL, SyncAction.TO_REMOTE, SyncAction.DELETE_LOCAL, SyncAction.DELETE_REMOTE, SyncAction.ADOPT, SyncAction.CONFLICT)) {
            for (changed in Side.entries) {
                val remote = MemoryTree(mapOf("a.txt" to "old"))
                val local = MemoryTree(mapOf("a.txt" to "old"))
                val r = remote.scan().getValue("a.txt")
                val l = local.scan().getValue("a.txt")
                val base = mapOf("a.txt" to BaseEntry("old-hash", r, l))
                val plan = listOf(SyncItem("a.txt", action, r, l, "old-hash"))
                (if (changed == Side.REMOTE) remote else local).put("a.txt", "newer unsynced contents")
                val report = FolderSync.apply(plan, remote, local, base, SyncOptions(true, mapOf("a.txt" to Resolution.KEEP_REMOTE)))
                assertEquals(if (changed == Side.REMOTE) "newer unsynced contents" else "old", remote.text("a.txt"), "$action/$changed")
                assertEquals(if (changed == Side.LOCAL) "newer unsynced contents" else "old", local.text("a.txt"), "$action/$changed")
                assertEquals(base, report.base)
                assertTrue(report.errors.any { it.code == DiagCode.STATE_CONFLICT })
                assertEquals(SyncAction.CONFLICT, report.skipped.single().action)
            }
        }
    }

    private fun sync(remote: MemoryTree, local: MemoryTree, base: Map<String, BaseEntry>, options: SyncOptions = SyncOptions()): Pair<List<SyncItem>, SyncReport> {
        val plan = FolderSync.plan(remote.scan(), local.scan(), base) { side, p -> FolderSync.hashOf(if (side == Side.REMOTE) remote else local, p) }
        return plan to FolderSync.apply(plan, remote, local, base, options)
    }

    private fun actions(plan: List<SyncItem>) = plan.associate { it.path to it.action }

    @Test
    fun firstSyncCopiesIntoLinux() {
        val remote = MemoryTree(mapOf("a.txt" to "A", "notes/b.md" to "B"))
        val local = MemoryTree()
        val (plan, report) = sync(remote, local, emptyMap())
        assertEquals(mapOf("a.txt" to SyncAction.TO_LOCAL, "notes/b.md" to SyncAction.TO_LOCAL), actions(plan))
        assertEquals("B", local.text("notes/b.md"))
        assertEquals(setOf("a.txt", "notes/b.md"), report.base.keys)
        assertEquals(Hashing.sha256("A".toByteArray()).hex, report.base.getValue("a.txt").sha256)
        // Сразу после — делать нечего.
        val (again, _) = sync(remote, local, report.base)
        assertTrue(again.none { it.visible }, again.toString())
    }

    @Test
    fun changesGoBothWays() {
        val remote = MemoryTree(mapOf("a.txt" to "A", "b.txt" to "B"))
        val local = MemoryTree()
        val base = sync(remote, local, emptyMap()).second.base
        remote.put("a.txt", "A2")
        local.put("b.txt", "B2")
        local.put("new.txt", "N")
        val (plan, report) = sync(remote, local, base)
        assertEquals(SyncAction.TO_LOCAL, actions(plan)["a.txt"])
        assertEquals(SyncAction.TO_REMOTE, actions(plan)["b.txt"])
        assertEquals(SyncAction.TO_REMOTE, actions(plan)["new.txt"])
        assertEquals("A2", local.text("a.txt"))
        assertEquals("B2", remote.text("b.txt"))
        assertEquals("N", remote.text("new.txt"))
        assertTrue(sync(remote, local, report.base).first.none { it.visible })
    }

    @Test
    fun conflictIsNotOverwritten() {
        val remote = MemoryTree(mapOf("a.txt" to "A"))
        val local = MemoryTree()
        val base = sync(remote, local, emptyMap()).second.base
        remote.put("a.txt", "из папки")
        local.put("a.txt", "из Linux")
        val (plan, report) = sync(remote, local, base)
        assertEquals(SyncAction.CONFLICT, actions(plan)["a.txt"])
        assertEquals("из папки", remote.text("a.txt"))
        assertEquals("из Linux", local.text("a.txt"))
        assertEquals(listOf("a.txt"), report.skipped.map { it.path })
        // Решение человека — версия копии.
        val (_, resolved) = sync(remote, local, report.base, SyncOptions(resolutions = mapOf("a.txt" to Resolution.KEEP_LOCAL)))
        assertEquals("из Linux", remote.text("a.txt"))
        assertEquals(1, resolved.done[SyncAction.CONFLICT])
    }

    @Test
    fun sameChangeBothSidesIsNotConflict() {
        val remote = MemoryTree(mapOf("a.txt" to "A"))
        val local = MemoryTree()
        val base = sync(remote, local, emptyMap()).second.base
        remote.put("a.txt", "одно и то же")
        local.put("a.txt", "одно и то же")
        val (plan, report) = sync(remote, local, base)
        assertEquals(SyncAction.ADOPT, actions(plan)["a.txt"])
        assertTrue(sync(remote, local, report.base).first.none { it.visible })
        // Появился с обеих сторон без базы — тоже сравнение по содержимому.
        val r2 = MemoryTree(mapOf("x" to "1"))
        val l2 = MemoryTree(mapOf("x" to "2"))
        assertEquals(SyncAction.CONFLICT, actions(sync(r2, l2, emptyMap()).first)["x"])
    }

    @Test
    fun deletionInFolderOnlyWithMark() {
        val remote = MemoryTree(mapOf("a.txt" to "A", "b.txt" to "B"))
        val local = MemoryTree()
        val base = sync(remote, local, emptyMap()).second.base
        local.delete("a.txt")
        val (plan, report) = sync(remote, local, base)
        assertEquals(SyncAction.DELETE_REMOTE, actions(plan)["a.txt"])
        assertEquals("A", remote.text("a.txt"), "без отметки файл автора остаётся")
        assertEquals(listOf("a.txt"), report.skipped.map { it.path })
        val (_, marked) = sync(remote, local, report.base, SyncOptions(deleteInFolder = true))
        assertEquals(null, remote.text("a.txt"))
        assertFalse("a.txt" in marked.base)
    }

    @Test
    fun deletionInFolderReachesCopy() {
        val remote = MemoryTree(mapOf("a.txt" to "A"))
        val local = MemoryTree()
        val base = sync(remote, local, emptyMap()).second.base
        remote.delete("a.txt")
        val (plan, report) = sync(remote, local, base)
        assertEquals(SyncAction.DELETE_LOCAL, actions(plan)["a.txt"])
        assertEquals(null, local.text("a.txt"))
        assertTrue(report.base.isEmpty())
    }

    @Test
    fun deletedHereChangedThereIsConflict() {
        val remote = MemoryTree(mapOf("a.txt" to "A"))
        val local = MemoryTree()
        val base = sync(remote, local, emptyMap()).second.base
        remote.delete("a.txt")
        local.put("a.txt", "правка")
        assertEquals(SyncAction.CONFLICT, actions(sync(remote, local, base).first)["a.txt"])
        assertEquals("правка", local.text("a.txt"))
    }

    @Test
    fun tooLargeAndFailedWrites() {
        val remote = MemoryTree(mapOf("big.bin" to "x".repeat(100), "a.txt" to "A"))
        val local = MemoryTree()
        val plan = FolderSync.plan(remote.scan(), local.scan(), emptyMap(), maxBytes = 50) { _, _ -> null }
        assertEquals(SyncAction.TOO_LARGE, actions(plan)["big.bin"])
        local.failWrites = true
        val report = FolderSync.apply(plan, remote, local, emptyMap())
        assertEquals(listOf(DiagCode.STORAGE_IO_FAILED), report.errors.map { it.code })
        assertTrue(report.base.isEmpty(), "неудачная запись не попадает в базу")
        assertEquals(listOf("big.bin"), report.skipped.map { it.path })
    }

    @Test
    fun scanSkipsBadNamesAndLimitsCount() {
        val t = MemoryTree(mapOf("ok.txt" to "1", "a:b.txt" to "2", "${FileNames.TMP_PREFIX}x" to "3"))
        val s = FileTrees.scan(t).valueOrNull()!!
        assertEquals(setOf("ok.txt"), s.files.keys)
        assertEquals(2, s.skipped.size)
        val many = MemoryTree((1..5).associate { "f$it" to "x" })
        assertEquals(DiagCode.VALUE_INVALID, (FileTrees.scan(many, maxFiles = 3) as Outcome.Failure).errors.single().code)
    }
}
