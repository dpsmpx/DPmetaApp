package com.dpsmpx.dppass.storage

import com.dpsmpx.dppass.core.ManualClock
import com.dpsmpx.dppass.diagnostics.DiagCode
import com.dpsmpx.dppass.diagnostics.Diagnostic
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
    private val key = KeyProvider { ByteArray(32) { it.toByte() } }

    @Test
    fun fileStoreWritesAtomicallyAndReadsBack() {
        val dir = Files.createTempDirectory("dppass-store").toFile()
        val s = FileBlobStore(dir)
        assertTrue(s.write("activation/state", "v1".toByteArray()).isSuccess)
        assertTrue(s.write("activation/state", "v2".toByteArray()).isSuccess)
        assertContentEquals("v2".toByteArray(), s.read("activation/state").valueOrNull())
        assertEquals(listOf("activation/state"), s.keys("activation/").valueOrNull())
        assertFalse(dir.walkTopDown().any { it.name.endsWith(".tmp") }, "временный файл не должен оставаться")
        assertNull(s.read("missing/key").valueOrNull())
        dir.deleteRecursively()
    }

    @Test
    fun badKeysAreRefused() {
        val s = InMemoryBlobStore()
        for (k in listOf("../etc/passwd", "/abs", "UPPER", "a//b", "")) {
            assertEquals(DiagCode.STORAGE_KEY_INVALID, s.write(k, ByteArray(1)).errors.single().code, k)
        }
    }

    @Test
    fun encryptedStoreHasNoPlaintextAndDetectsTampering() {
        val raw = InMemoryBlobStore()
        val enc = EncryptingBlobStore(raw, key)
        val secret = "vless-uuid 00000000-0000-4000-8000-000000000001"
        enc.write("activation/state", secret.toByteArray())
        val onDisk = raw.rawSnapshot().getValue("activation/state")
        assertFalse(String(onDisk, Charsets.ISO_8859_1).contains("00000000-0000"))
        assertEquals(secret, String(enc.read("activation/state").valueOrNull()!!))

        onDisk[onDisk.size - 1] = (onDisk[onDisk.size - 1].toInt() xor 1).toByte()
        raw.write("activation/state", onDisk)
        assertEquals(DiagCode.STORAGE_CORRUPTED, enc.read("activation/state").errors.single().code)
    }

    @Test
    fun recordCannotBeMovedUnderAnotherKey() {
        val raw = InMemoryBlobStore()
        val enc = EncryptingBlobStore(raw, key)
        enc.write("a/one", "x".toByteArray())
        raw.write("a/two", raw.rawSnapshot().getValue("a/one"))
        assertEquals(DiagCode.STORAGE_CORRUPTED, enc.read("a/two").errors.single().code)
    }

    @Test
    fun quarantineKeepsOriginalAndReason() {
        val store = InMemoryBlobStore()
        val q = Quarantine(store, ManualClock(5))
        val k = q.keep("activation", "broken".toByteArray(), Diagnostic.error(DiagCode.STORAGE_CORRUPTED, "bad"))
        assertEquals<List<String>?>(listOf(k.valueOrNull()!!), q.list().valueOrNull())
        assertContentEquals("broken".toByteArray(), store.read("${k.valueOrNull()}.data").valueOrNull())
    }
}
