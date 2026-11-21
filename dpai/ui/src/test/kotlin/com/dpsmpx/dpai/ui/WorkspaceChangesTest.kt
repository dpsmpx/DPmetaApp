package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.files.FileEntry
import com.dpsmpx.dpai.files.FileMeta
import com.dpsmpx.dpai.files.FileTree
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.nio.file.Path
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class WorkspaceChangesTest {
    @TempDir lateinit var dir: Path
    private val cipher = object : DraftCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(record: String, bytes: ByteArray): Outcome<ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(record.toByteArray()) }
            return Outcome.success(c.iv + c.doFinal(bytes))
        }
        override fun open(record: String, bytes: ByteArray): Outcome<ByteArray> = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)); updateAAD(record.toByteArray()) }
            Outcome.success(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (_: GeneralSecurityException) { Outcome.failure(DiagCode.STORAGE_CORRUPTED, "копия недоступна") }
    }
    private fun store(vfs: Vfs = NioVfs(dir)) = WorkspaceChangeStore(vfs, cipher)
    private fun files() = listOf(WorkspaceBytes("a.kt", "foo".toByteArray(), "bar".toByteArray()), WorkspaceBytes("b.kt", "foo".toByteArray(), "bar".toByteArray()))
    private fun plan() = WorkspaceEditPlan(files().map { WorkspaceTextChange(it.path, "file:///p/${it.path}", "foo", "bar", emptyList()) })
    @Test fun revokedIndexCannotWriteSourceOrBeginADurableCopy() {
        val index = com.dpsmpx.dpai.lsp.ProjectIndex()
        index.requested(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID)
        index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "end")
        val lease = (index.lease() as Outcome.Success).value
        val tree = Tree()
        val copies = store()
        index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "begin")
        assertIs<Outcome.Failure>(WorkspaceChanges(copies).apply("p", "root", tree, plan().copy(index = lease)))
        assertEquals(0, tree.writes)
        assertEquals(null, copies.load("p").valueOrNull())
    }

    @Test fun indexRestartingDuringPreflightDoesNotStartSourceWrites() {
        val index = com.dpsmpx.dpai.lsp.ProjectIndex()
        index.requested(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID)
        index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "end")
        val lease = (index.lease() as Outcome.Success).value
        val source = Tree()
        val tree = object : FileTree by source {
            override fun read(path: String, maxBytes: Long): Outcome<ByteArray> {
                index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "begin")
                return source.read(path, maxBytes)
            }
        }
        val copies = store()
        assertIs<Outcome.Failure>(WorkspaceChanges(copies).apply("p", "root", tree, plan().copy(index = lease)))
        assertEquals(0, source.writes)
        assertEquals(null, copies.load("p").valueOrNull())
    }

    @Test fun indexRevokedWhileEncryptingTheCopyCannotWriteSourceOrLeaveAnUnneededPause() {
        val index = com.dpsmpx.dpai.lsp.ProjectIndex()
        index.requested(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID)
        index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "end")
        val lease = (index.lease() as Outcome.Success).value
        val stoppingCipher = object : DraftCipher by cipher {
            override fun seal(record: String, bytes: ByteArray): Outcome<ByteArray> {
                index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "begin")
                return cipher.seal(record, bytes)
            }
        }
        val copies = WorkspaceChangeStore(NioVfs(dir), stoppingCipher)
        val tree = Tree()
        assertIs<Outcome.Failure>(WorkspaceChanges(copies).apply("p", "root", tree, plan().copy(index = lease)))
        assertEquals(0, tree.writes)
        assertFalse(copies.blocked("p"))
    }

    @Test fun aCurrentIndexLeaseAllowsTheWholeConfirmedChange() {
        val index = com.dpsmpx.dpai.lsp.ProjectIndex()
        index.requested(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID)
        index.progress(com.dpsmpx.dpai.lsp.ProjectIndex.PROGRESS_ID, "end")
        val lease = (index.lease() as Outcome.Success).value
        val tree = Tree()
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).apply("p", "root", tree, plan().copy(index = lease)))
        assertEquals(2, tree.writes)
        assertEquals("bar", tree.files.getValue("a.kt").toString(Charsets.UTF_8))
    }

    private class Tree : FileTree {
        val files = linkedMapOf("a.kt" to "foo".toByteArray(), "b.kt" to "foo".toByteArray())
        var writes = 0
        var fail: String? = null
        var tear = false
        override fun list(dir: String): Outcome<List<FileEntry>> = Outcome.success(emptyList())
        override fun read(path: String, maxBytes: Long): Outcome<ByteArray> = files[path]?.let { Outcome.success(it.copyOf()) } ?: Outcome.failure(DiagCode.STORAGE_IO_FAILED, "нет файла")
        override fun write(path: String, bytes: ByteArray): Outcome<FileMeta> {
            writes++
            if (fail == path) {
                fail = null
                if (tear) files[path] = "partial".toByteArray()
                return Outcome.failure(DiagCode.STORAGE_IO_FAILED, "отказ записи")
            }
            files[path] = bytes.copyOf()
            return Outcome.success(FileMeta(bytes.size.toLong(), 0))
        }
        override fun delete(path: String) = Outcome.success(Unit)
    }

    @Test fun savesAllFilesPreservesBomAndUndoSurvivesRestart() {
        val tree = Tree()
        tree.files["a.kt"] = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "foo".toByteArray()
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).apply("p", "tree", tree, plan()))
        assertEquals("bar", tree.files.getValue("a.kt").toString(Charsets.UTF_8).removePrefix("\uFEFF"))
        assertFalse(store().blocked("p"))
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).undo("p", "tree", tree))
        assertEquals("\uFEFFfoo", tree.files.getValue("a.kt").toString(Charsets.UTF_8))
        assertEquals("foo", tree.files.getValue("b.kt").toString(Charsets.UTF_8))
    }

    @Test fun stalePreviewRefusesTheWholeChangeBeforeBackupOrWrites() {
        val tree = Tree(); tree.files["b.kt"] = "external".toByteArray()
        assertIs<Outcome.Failure>(WorkspaceChanges(store()).apply("p", "tree", tree, plan()))
        assertEquals(0, tree.writes)
        assertEquals(null, store().load("p").valueOrNull())
    }

    @Test fun failedWriteRollsBackKnownFilesAndUnpauses() {
        val tree = Tree(); tree.fail = "b.kt"
        assertIs<Outcome.Failure>(WorkspaceChanges(store()).apply("p", "tree", tree, plan()))
        assertTrue(tree.files.values.all { it.toString(Charsets.UTF_8) == "foo" })
        assertFalse(store().blocked("p"))
    }

    @Test fun tornWriteLeavesDurablePauseAndNeverOverwritesUnknownText() {
        val tree = Tree(); tree.fail = "b.kt"; tree.tear = true
        assertIs<Outcome.Failure>(WorkspaceChanges(store()).apply("p", "tree", tree, plan()))
        assertTrue(store().blocked("p"))
        val writes = tree.writes
        assertIs<Outcome.Failure>(WorkspaceChanges(store()).recover("p", "tree", tree, false))
        assertEquals(writes, tree.writes)
        assertEquals("partial", tree.files.getValue("b.kt").toString(Charsets.UTF_8))
    }

    @Test fun restartCanRestoreOrCompleteKnownPartialChangeButNotDifferentTree() {
        val tree = Tree()
        assertIs<Outcome.Success<Unit>>(store().begin("p", "tree", files()))
        tree.files["a.kt"] = "bar".toByteArray()
        assertTrue(store().blocked("p"))
        assertIs<Outcome.Failure>(WorkspaceChanges(store()).recover("p", "other tree", tree, true))
        assertEquals(0, tree.writes)
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).recover("p", "tree", tree, true))
        assertTrue(tree.files.values.all { it.toString(Charsets.UTF_8) == "bar" })
        assertFalse(store().blocked("p"))
    }

    @Test fun backupFailureNeverWritesSources() {
        val io = object : Vfs by NioVfs(dir) { override fun fsync(path: String) { throw IOException("диск занят") } }
        val tree = Tree()
        assertIs<Outcome.Failure>(WorkspaceChanges(store(io)).apply("p", "tree", tree, plan()))
        assertEquals(0, tree.writes)
    }

    @Test fun pendingGuardFailureNeverWritesSourcesAndRemainsRecoverable() {
        val io = object : Vfs by NioVfs(dir) {
            override fun fsync(path: String) { if (path.startsWith("workspace-guards/")) throw IOException("отказ guard") else NioVfs(dir).fsync(path) }
        }
        val tree = Tree()
        assertIs<Outcome.Failure>(WorkspaceChanges(store(io)).apply("p", "tree", tree, plan()))
        assertEquals(0, tree.writes)
        assertTrue(store().blocked("p"))
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).recover("p", "tree", tree, false))
        assertFalse(store().blocked("p"))
    }

    @Test fun lostCurrentGuardUsesPreviousOnlyAsPendingAndCanBeRecovered() {
        val tree = Tree()
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).apply("p", "tree", tree, plan()))
        val guard = java.nio.file.Files.list(dir.resolve("workspace-guards")).use { stream -> stream.filter { !it.fileName.toString().contains('.') }.findFirst().orElseThrow() }
        java.nio.file.Files.write(guard, "corrupt".toByteArray())
        assertTrue(store().blocked("p"))
        assertTrue(store().load("p").valueOrNull()!!.pending)
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).recover("p", "tree", tree, false))
        assertTrue(tree.files.values.all { it.toString(Charsets.UTF_8) == "foo" })
        assertFalse(store().blocked("p"))
    }

    @Test fun completionFailureKeepsPauseEvenAfterAllSourcesWereWritten() {
        var guardWrites = 0
        val base = NioVfs(dir)
        val io = object : Vfs by base {
            override fun fsync(path: String) {
                if (path.startsWith("workspace-guards/") && ++guardWrites > 2) throw IOException("отказ завершения")
                base.fsync(path)
            }
        }
        val tree = Tree()
        assertIs<Outcome.Failure>(WorkspaceChanges(store(io)).apply("p", "tree", tree, plan()))
        assertTrue(tree.files.values.all { it.toString(Charsets.UTF_8) == "bar" })
        assertTrue(store().blocked("p"))
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).recover("p", "tree", tree, true))
        assertFalse(store().blocked("p"))
    }

    @Test fun pendingBlocksAnotherTransactionAndUndoRefusesExternalEdits() {
        val tree = Tree()
        assertIs<Outcome.Success<Unit>>(store().begin("p", "tree", files()))
        assertIs<Outcome.Failure>(store().begin("p", "tree", files()))
        assertIs<Outcome.Success<Unit>>(WorkspaceChanges(store()).recover("p", "tree", tree, true))
        tree.files["b.kt"] = "external".toByteArray()
        val writes = tree.writes
        assertIs<Outcome.Failure>(WorkspaceChanges(store()).undo("p", "tree", tree))
        assertEquals(writes, tree.writes)
    }

    @Test fun copiesAreEncryptedIsolatedByProjectAndOnlyTwoGenerationsRemain() {
        val tree = Tree(); val changes = WorkspaceChanges(store())
        assertIs<Outcome.Success<Unit>>(changes.apply("p", "private tree marker", tree, plan()))
        repeat(3) { assertIs<Outcome.Success<Unit>>(changes.undo("p", "private tree marker", tree)) }
        assertEquals(null, store().load("other project").valueOrNull())
        val paths = java.nio.file.Files.walk(dir).use { it.filter(java.nio.file.Files::isRegularFile).toList() }
        assertTrue(paths.all { !it.toFile().readText().contains("private tree marker") })
        val snapshots = paths.filter { it.toString().contains("workspace-copies") && !it.fileName.toString().contains('.') }
        assertEquals(2, snapshots.size)
    }
}
