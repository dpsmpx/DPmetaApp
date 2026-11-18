package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SigningSlotsTest {
    @TempDir lateinit var dir: Path

    private class Faults(private val real: Vfs) : Vfs by real {
        var failAt: Int? = null
        var operations = 0
        private fun check() { if (++operations == failAt) throw IOException("отказ хранилища") }
        override fun write(path: String, bytes: ByteArray) { check(); real.write(path, bytes) }
        override fun fsync(path: String) { check(); real.fsync(path) }
        override fun rename(from: String, to: String) { check(); real.rename(from, to) }
        override fun fsyncDir(dir: String) { check(); real.fsyncDir(dir) }
    }

    private class Fixture(path: Path) {
        val real = NioVfs(path)
        val faults = Faults(real)
        val documents = DocumentStore(faults, "selectors")
        var number = 0
        val slots = SigningSlots(documents) { (++number).toString(16).padStart(32, '0') }
        val keys = linkedMapOf<String, String>()
        val copies = linkedSetOf<String>()

        fun erase(alias: String): Outcome<Unit> {
            copies.remove(alias)
            keys.remove(alias)
            return Outcome.success(Unit)
        }

        fun cleanup(project: String?, keep: String?): Outcome<Unit> {
            keys.keys.toList().filter { it != keep && SigningSlots.belongs(it, project) }.forEach(::erase)
            return Outcome.success(Unit)
        }

        fun replace(project: String? = null, value: String = "new", failStage: String? = null): Outcome<String> = slots.replace(
            project, keys.keys.toList(), replaceExisting = true,
            stage = { alias ->
                keys[alias] = value
                if (failStage == "create" || failStage == "verify") Outcome.failure(DiagCode.STORAGE_IO_FAILED, failStage)
                else {
                    copies += alias
                    if (failStage == "copy") Outcome.failure(DiagCode.STORAGE_IO_FAILED, "копия") else Outcome.success(value)
                }
            },
            discard = ::erase,
            cleanup = { cleanup(project, it) },
        )

        fun active(project: String? = null): String? = slots.active(project, keys.keys).valueOrNull()
    }

    @Test fun creationVerificationAndCopyFailuresKeepLegacyKeyAndCopy() {
        for (phase in listOf("create", "verify", "copy")) {
            val f = Fixture(dir.resolve(phase))
            val old = SigningSlots.legacyAlias(null)
            f.keys[old] = "old"
            f.copies += old
            assertTrue(f.replace(failStage = phase) is Outcome.Failure)
            assertEquals(old, f.active())
            assertEquals(mapOf(old to "old"), f.keys)
            assertEquals(setOf(old), f.copies)
        }
    }

    @Test fun everyCommitFailureKeepsBothGenerationsAvailableAfterRestart() {
        for (operation in 1..10) {
            val f = Fixture(dir.resolve("step-$operation"))
            assertTrue(f.replace(value = "old") is Outcome.Success)
            val old = assertNotNull(f.active())
            f.faults.operations = 0
            f.faults.failAt = operation
            assertTrue(f.replace() is Outcome.Failure, "операция $operation")
            assertEquals("old", f.keys[old])
            assertTrue(old in f.copies)
            assertEquals(2, f.keys.size, "неопределённый commit не удаляет ни старый, ни новый")
            val restarted = SigningSlots(DocumentStore(f.real, "selectors"))
            val chosen = assertNotNull(restarted.active(null, f.keys.keys).valueOrNull())
            assertTrue(chosen in f.keys && chosen in f.copies)
        }
    }

    @Test fun successfulReplacementAndLkgBothSelectNewKey() {
        val f = Fixture(dir)
        assertTrue(f.replace(value = "old") is Outcome.Success)
        val old = assertNotNull(f.active())
        assertTrue(f.replace() is Outcome.Success)
        val new = assertNotNull(f.active())
        assertFalse(old in f.keys || old in f.copies)
        f.real.write("selectors/active", byteArrayOf(1, 2, 3))
        val restarted = SigningSlots(DocumentStore(f.real, "selectors"))
        assertEquals(new, restarted.active(null, f.keys.keys).valueOrNull(), "LKG не ссылается на удалённое поколение")
    }

    @Test fun cleanupFailureIsWarningAndDeleteRemovesEveryGeneration() {
        val f = Fixture(dir)
        assertTrue(f.replace(value = "old") is Outcome.Success)
        val result = f.slots.replace(null, f.keys.keys.toList(), true,
            stage = { f.keys[it] = "new"; f.copies += it; Outcome.success("new") },
            discard = f::erase,
            cleanup = { Outcome.failure(DiagCode.STORAGE_IO_FAILED, "очистка") },
        )
        assertTrue(result is Outcome.Success && result.warnings.isNotEmpty())
        assertEquals("new", f.keys[f.active()])
        assertEquals(2, f.keys.size)
        assertTrue(f.slots.delete(null) { f.cleanup(null, null) } is Outcome.Success)
        assertTrue(f.keys.isEmpty() && f.copies.isEmpty())
        f.real.write("selectors/active", byteArrayOf(9))
        assertEquals(null, SigningSlots(DocumentStore(f.real, "selectors")).active(null, f.keys.keys).valueOrNull())
    }

    @Test fun scopeEncodingSeparatesGlobalFromProjectNamedGlobal() {
        val f = Fixture(dir)
        assertTrue(f.replace(value = "global") is Outcome.Success)
        assertTrue(f.replace(project = "global", value = "project") is Outcome.Success)
        val global = assertNotNull(f.active())
        val project = assertNotNull(f.active("global"))
        assertTrue(global != project)
        assertTrue(SigningSlots.copyName(global) != SigningSlots.copyName(project))
        assertEquals("global", SigningSlots.scope(project)?.project)
        assertTrue(f.slots.delete("global") { f.cleanup("global", null) } is Outcome.Success)
        assertEquals("global", f.keys[global])
        assertEquals(null, f.active("global"))
    }

    @Test fun missingGenerationAndUnknownSelectorFormatFailClosed() {
        val f = Fixture(dir)
        assertTrue(f.replace() is Outcome.Success)
        val active = assertNotNull(f.active())
        f.keys.remove(active)
        assertTrue(f.slots.active(null, f.keys.keys) is Outcome.Failure)
        val unknown = JsonObject(linkedMapOf(*FormatHeader.write(SigningSlots.FORMAT, 2), "slots" to JsonArray(emptyList())))
        f.documents.write(SigningSlots.KEY, unknown)
        var staged = false
        assertTrue(f.slots.replace(null, emptyList(), true,
            stage = { staged = true; Outcome.success(Unit) }, discard = { Outcome.success(Unit) }, cleanup = { Outcome.success(Unit) },
        ) is Outcome.Failure)
        assertFalse(staged)
    }

    @Test fun generationCollisionNeverOverwritesExistingAlias() {
        val f = Fixture(dir)
        assertTrue(f.replace() is Outcome.Success)
        f.number = 0
        assertTrue(f.replace(value = "other") is Outcome.Failure)
        assertEquals(listOf("new"), f.keys.values.toList())
    }
}
