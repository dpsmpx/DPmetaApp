package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.nio.file.Path
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class EditorDraftStoreTest {
    @TempDir lateinit var dir: Path
    private val cipher = TestCipher()
    private fun store(vfs: Vfs = NioVfs(dir), cipher: DraftCipher = this.cipher) = EditorDraftStore(vfs, cipher)
    private fun draft(key: String = "file:workspace:project-a\u0001src/main.kt", text: String = "private source marker for editor copy") =
        SavedFileDraft(key, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "original\r\n".toByteArray(), text, EditorSelection(text.length, 0), "/root/project-a/src/main.kt")

    private class TestCipher : DraftCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun seal(record: String, bytes: ByteArray): Outcome<ByteArray> {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(record.toByteArray()) }
            return Outcome.success(c.iv + c.doFinal(bytes))
        }
        override fun open(record: String, bytes: ByteArray): Outcome<ByteArray> = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)); updateAAD(record.toByteArray()) }
            Outcome.success(c.doFinal(bytes, 12, bytes.size - 12))
        } catch (_: GeneralSecurityException) { Outcome.failure(DiagCode.STORAGE_CORRUPTED, "копия не открыта") }
    }

    @Test fun restartRestoresTextOriginalBytesSelectionAndIdentityWithoutPlaintextOnDisk() {
        val before = draft()
        assertIs<Outcome.Success<Unit>>(store().save(before))
        val read = store().restore().valueOrNull()!!
        val after = read.drafts.single()
        assertEquals(before.key, after.key); assertEquals(before.text, after.text)
        assertEquals(before.selection, after.selection); assertEquals(before.guestPath, after.guestPath)
        assertContentEquals(before.original, after.original)
        val disk = dir.resolve("drafts/${EditorDraftStore.recordId(before.key)}").toFile().readText()
        assertFalse(disk.contains(before.text)); assertFalse(disk.contains(before.key)); assertFalse(disk.contains("original"))
        assertTrue(read.unreadable.isEmpty())
    }

    @Test fun emptyDraftAndTwoDifferentWorktreesAreRestoredSeparately() {
        store().save(draft(text = ""))
        store().save(draft(key = "file:workspace:project-b\u0001src/main.kt", text = "Бета 😀"))
        val restored = store().restore().valueOrNull()!!.drafts.associateBy { it.key }
        assertEquals("", restored[draft().key]!!.text)
        assertEquals("Бета 😀", restored["file:workspace:project-b\u0001src/main.kt"]!!.text)
    }

    @Test fun durableDiscardDoesNotResurrectAnyOlderVersion() {
        val d = draft()
        store().save(d); store().save(d.copy(text = "next", selection = EditorSelection(4)))
        assertIs<Outcome.Success<Unit>>(store().remove(d.key))
        assertTrue(store().restore().valueOrNull()!!.drafts.isEmpty())
        assertTrue(dir.resolve("drafts").toFile().listFiles().orEmpty().isEmpty())
    }

    @Test fun damagedPrimaryRecoversPreviousVersionWithWarning() {
        val d = draft()
        store().save(d); store().save(d.copy(text = "next", selection = EditorSelection(4)))
        val id = EditorDraftStore.recordId(d.key)
        dir.resolve("drafts/$id").toFile().writeText("damaged")
        val restored = store().restore().valueOrNull()!!
        assertEquals(d.text, restored.drafts.single().text)
        assertTrue(id in restored.previous)
    }

    @Test fun failedRenameBeforeCommitRecoversWholeNewCopyWithoutDeletingIt() {
        val base = NioVfs(dir)
        val d = draft()
        store().save(d)
        val broken = object : Vfs by base {
            override fun rename(from: String, to: String) {
                if (from.endsWith(".tmp")) throw IOException("сбой переименования")
                base.rename(from, to)
            }
        }
        val updated = d.copy(text = "updated", selection = EditorSelection(7))
        assertIs<Outcome.Failure>(store(broken).save(updated))
        val restored = store().restore().valueOrNull()!!
        assertEquals(updated.text, restored.drafts.single().text)
        assertTrue(restored.previous.isNotEmpty())
    }

    @Test fun wrongKeyAndTamperedCiphertextStayOnDiskAndAreReported() {
        val d = draft(); store().save(d)
        val id = EditorDraftStore.recordId(d.key)
        val path = dir.resolve("drafts/$id").toFile()
        val before = path.readBytes()
        val wrong = store(cipher = TestCipher()).restore().valueOrNull()!!
        assertTrue(wrong.drafts.isEmpty()); assertTrue(id in wrong.unreadable)
        assertContentEquals(before, path.readBytes())
        val docs = DocumentStore(NioVfs(dir), "drafts")
        val original = docs.read(id).valueOrNull()!!.content
        val sealed = (original["sealed"] as JsonString).value
        docs.write(id, envelope(sealed.replaceRange(4, 5, if (sealed[4] == 'A') "B" else "A")))
        assertTrue(id in store().restore().valueOrNull()!!.unreadable)
    }

    @Test fun aadPreventsMovingCopyToAnotherTree() {
        val a = draft(); val b = draft(key = "file:workspace:another-tree\u0001src/main.kt")
        store().save(a)
        val docs = DocumentStore(NioVfs(dir), "drafts")
        val sealed = docs.read(EditorDraftStore.recordId(a.key)).valueOrNull()!!.content
        docs.write(EditorDraftStore.recordId(b.key), sealed)
        val restored = store().restore().valueOrNull()!!
        assertEquals(a.key, restored.drafts.single().key)
        assertTrue(EditorDraftStore.recordId(b.key) in restored.unreadable)
    }

    @Test fun failedWritePreservesLastCopyAndFailedDeleteDoesNotClaimSuccess() {
        val base = NioVfs(dir); val d = draft(); store().save(d)
        val broken = object : Vfs by base {
            override fun write(path: String, bytes: ByteArray) { throw IOException("диск недоступен") }
            override fun delete(path: String) { throw IOException("удаление запрещено") }
        }
        assertIs<Outcome.Failure>(store(broken).save(d.copy(text = "next", selection = EditorSelection(4))))
        assertIs<Outcome.Failure>(store(broken).remove(d.key))
        assertEquals(d.text, store().restore().valueOrNull()!!.drafts.single().text)
    }

    @Test fun invalidUnicodeAndSelectionAreRefusedWithoutDestroyingLastCopy() {
        val d = draft(); store().save(d)
        assertIs<Outcome.Failure>(store().save(d.copy(text = "\uD800", selection = EditorSelection(0))))
        assertIs<Outcome.Failure>(store().save(d.copy(selection = EditorSelection(-1))))
        assertEquals(d.text, store().restore().valueOrNull()!!.drafts.single().text)
    }

    @Test fun unknownEnvelopeVersionIsRetainedAndNotOpened() {
        val d = draft(); val id = EditorDraftStore.recordId(d.key)
        val docs = DocumentStore(NioVfs(dir), "drafts")
        docs.write(id, JsonObject.of("format" to JsonString("dpai.editor-draft"), "version" to JsonNumber.of(2), "sealed" to JsonString("")))
        assertTrue(id in store().restore().valueOrNull()!!.unreadable)
        assertTrue(dir.resolve("drafts/$id").toFile().exists())
    }

    private fun envelope(sealed: String) = JsonObject.of("format" to JsonString("dpai.editor-draft"), "version" to JsonNumber.of(1), "sealed" to JsonString(sealed))
}
