package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.document.DocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStoreTest {
    private val v1 = JsonObject.of("name" to json("первая"), "n" to json(1))
    private val v2 = JsonObject.of("name" to json("вторая"), "n" to json(2))

    @Test
    fun writeReadAndAbsent() {
        val store = DocumentStore(SimulatedVfs(), "data")
        assertNull(store.read("settings").valueOrNull())
        assertTrue(store.write("settings", v1).isSuccess)
        assertEquals(v1, store.read("settings").valueOrNull()?.content)
        store.write("settings", v2)
        assertEquals(v2, store.read("settings").valueOrNull()?.content)
        assertEquals(listOf("settings"), store.keys())
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(store.write("../x", v1)).errors.single().code)
    }

    /** Прерванная в любой точке запись: прежняя или новая версия, не смесь (ТЗ, §103). */
    @Test
    fun crashAtEveryStepLeavesOldOrNew() {
        crashEverywhere(
            setup = { DocumentStore(it, "data").write("settings", v1) },
            action = { DocumentStore(it, "data").write("settings", v2) },
        ) { vfs, k ->
            val read = DocumentStore(vfs, "data").read("settings")
            val content = assertIs<Outcome.Success<*>>(read, "сбой на операции $k").value
            assertTrue(content != null, "сбой на операции $k: документ пропал")
            val doc = (read.valueOrNull())!!.content
            assertTrue(doc == v1 || doc == v2, "сбой на операции $k: $doc")
        }
    }

    /** Удаление убирает все версии; прерванное — документ прежний или его нет, но не старее (D-24). */
    @Test
    fun deleteRemovesAllVersionsAndNeverResurrects() {
        val vfs = SimulatedVfs()
        val store = DocumentStore(vfs, "data")
        store.write("settings", v1)
        store.write("settings", v2)
        assertTrue(store.delete("settings").isSuccess)
        assertNull(store.read("settings").valueOrNull())
        assertEquals(emptyList(), vfs.list("data"))
        crashEverywhere(
            setup = {
                DocumentStore(it, "data").write("settings", v1)
                DocumentStore(it, "data").write("settings", v2)
            },
            action = { DocumentStore(it, "data").delete("settings") },
        ) { after, k ->
            val doc = DocumentStore(after, "data").read("settings").valueOrNull()?.content
            assertTrue(doc == null || doc == v2, "сбой на операции $k: $doc")
        }
    }

    /** Подтверждённая запись переживает сбой сразу после подтверждения (D-24). */
    @Test
    fun acknowledgedWriteSurvivesCrash() {
        for (seed in 1L..50L) {
            val vfs = SimulatedVfs(com.dpsmpx.dpai.core.clock.RandomSource.seeded(seed))
            val store = DocumentStore(vfs, "data")
            store.write("settings", v1)
            assertTrue(store.write("settings", v2).isSuccess)
            vfs.crash()
            assertEquals(v2, DocumentStore(vfs, "data").read("settings").valueOrNull()?.content, "зерно $seed")
        }
    }

    @Test
    fun crashDuringFirstWriteLeavesNothingOrNew() {
        crashEverywhere(setup = {}, action = { DocumentStore(it, "data").write("settings", v1) }) { vfs, k ->
            val doc = DocumentStore(vfs, "data").read("settings").valueOrNull()?.content
            assertTrue(doc == null || doc == v1, "сбой на операции $k: $doc")
        }
    }

    @Test
    fun corruptedMainFallsBackToLastKnownGood() {
        val vfs = SimulatedVfs()
        val store = DocumentStore(vfs, "data")
        store.write("settings", v1)
        store.write("settings", v2)
        vfs.corrupt("data/settings", 20)
        val r = store.read("settings")
        assertEquals(v1, r.valueOrNull()?.content)
        assertEquals(true, r.valueOrNull()?.fromLastKnownGood)
        assertEquals(DiagCode.STORAGE_RECOVERED_LKG, r.warnings.single().code)
        // Новая запись не делает испорченную версию «прежней».
        store.write("settings", v2)
        vfs.corrupt("data/settings", 20)
        assertEquals(v1, store.read("settings").valueOrNull()?.content)
    }

    @Test
    fun nothingIntactIsAnError() {
        val vfs = SimulatedVfs()
        val store = DocumentStore(vfs, "data")
        store.write("settings", v1)
        vfs.corrupt("data/settings", 5)
        assertEquals(DiagCode.STORAGE_CORRUPTED, assertIs<Outcome.Failure>(store.read("settings")).errors.single().code)
    }

    /** Отказ ввода-вывода (диск полон) на любом шаге: отказ записи, прежняя версия цела. */
    @Test
    fun ioFailureAtEveryStepKeepsOldVersion() {
        val probe = SimulatedVfs()
        DocumentStore(probe, "data").write("settings", v1)
        val start = probe.ops
        DocumentStore(probe, "data").write("settings", v2)
        val total = probe.ops - start
        for (k in 0 until total) {
            val vfs = SimulatedVfs()
            val store = DocumentStore(vfs, "data")
            store.write("settings", v1)
            vfs.failAtOp = vfs.ops + k
            val r = store.write("settings", v2)
            assertEquals(DiagCode.STORAGE_IO_FAILED, assertIs<Outcome.Failure>(r, "отказ на операции $k").errors.single().code)
            val doc = store.read("settings").valueOrNull()?.content
            assertTrue(doc == v1 || doc == v2, "отказ на операции $k: $doc")
        }
    }
}
