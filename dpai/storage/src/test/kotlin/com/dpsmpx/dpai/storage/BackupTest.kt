package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.backup.BackupService
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.migration.Migration
import com.dpsmpx.dpai.storage.migration.Migrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Набор ТЗ, §61: годная, старая схема, частичная, повреждённая, после прерванного восстановления. */
class BackupTest {
    private val clock = ManualClock()
    private val a = JsonObject.of("v" to json("A"))
    private val b = JsonObject.of("v" to json("B"))

    private fun populate(vfs: SimulatedVfs, value: JsonObject, events: Int, seed: Long = 1) {
        DocumentStore(vfs, "data").write("settings", value)
        val j = (EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value
        val f = EventFactory(seed)
        repeat(events) { j.append(f.next()) }
    }

    private fun state(vfs: SimulatedVfs): Pair<JsonObject?, Long> =
        DocumentStore(vfs, "data").read("settings").valueOrNull()?.content to
            ((EventJournal.open(vfs, "data/journal.log") as Outcome.Success).value.position)

    @Test
    fun validBackupRestoresExactState() {
        val vfs = SimulatedVfs()
        populate(vfs, a, 3)
        val service = BackupService(vfs, clock)
        val manifest = assertIs<Outcome.Success<*>>(service.create("data", "backups/1", schemaVersion = 1))
        assertTrue(manifest.isSuccess)
        DocumentStore(vfs, "data").write("settings", b)
        DocumentStore(vfs, "data").write("extra", b)
        assertTrue(service.restore("backups/1", "data").isSuccess)
        assertEquals(a to 3L, state(vfs))
        assertFalse(vfs.exists("data/extra"), "файла нет в копии — вытеснен")
        assertTrue(vfs.exists("data/extra.replaced"), "вытесненное сохранено, а не удалено")
        assertFalse(vfs.exists("data/${BackupService.MARKER}"))
    }

    @Test
    fun refusesToOverwriteExistingBackup() {
        val vfs = SimulatedVfs()
        populate(vfs, a, 1)
        val s = BackupService(vfs, clock)
        s.create("data", "backups/1", 1)
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(s.create("data", "backups/1", 1)).errors.single().code)
    }

    @Test
    fun partialAndCorruptedBackupsAreNotApplied() {
        val vfs = SimulatedVfs()
        populate(vfs, a, 2)
        val s = BackupService(vfs, clock)
        s.create("data", "backups/1", 1)
        populate(vfs, b, 0)
        val before = state(vfs)
        vfs.corrupt("backups/1/journal.log", 10)
        assertEquals(DiagCode.BACKUP_INVALID, assertIs<Outcome.Failure>(s.restore("backups/1", "data")).errors.single().code)
        assertEquals(before, state(vfs), "данные не тронуты")
        s.create("data", "backups/2", 1)
        vfs.delete("backups/2/settings")
        assertEquals(DiagCode.BACKUP_INVALID, assertIs<Outcome.Failure>(s.restore("backups/2", "data")).errors.single().code)
        vfs.delete("backups/2/${BackupService.MANIFEST}")
        assertEquals(DiagCode.BACKUP_INVALID, assertIs<Outcome.Failure>(s.verify("backups/2")).errors.single().code)
        assertEquals(before, state(vfs))
    }

    @Test
    fun oldSchemaBackupRestoresAndMigratesOnRead() {
        val vfs = SimulatedVfs()
        val old = JsonObject(linkedMapOf(*FormatHeader.write("dpai.test", 1), "title" to json("x")))
        DocumentStore(vfs, "data").write("task", old)
        val s = BackupService(vfs, clock)
        s.create("data", "backups/old", schemaVersion = 1)
        DocumentStore(vfs, "data").write("task", JsonObject(linkedMapOf(*FormatHeader.write("dpai.test", 2), "name" to json("y"))))
        assertTrue(s.restore("backups/old", "data").isSuccess)
        val migrator = Migrator("dpai.test", 2, listOf(Migration(1, "title → name") { d ->
            Outcome.success(JsonObject(d.fields - "title" + ("name" to d.fields.getValue("title")) + ("version" to json(2))))
        }))
        val r = DocumentStore(vfs, "data").readMigrated("task", migrator)
        assertEquals(json("x"), r.valueOrNull()!!.content["name"])
    }

    /** Восстановление, прерванное на любой операции, довершается при запуске; итог — состояние копии. */
    @Test
    fun interruptedRestoreIsResumed() {
        crashEverywhere(
            setup = { vfs ->
                populate(vfs, a, 2)
                BackupService(vfs, clock).create("data", "backups/1", 1)
                populate(vfs, b, 3, seed = 2)
            },
            action = { vfs -> BackupService(vfs, clock).restore("backups/1", "data") },
        ) { vfs, k ->
            val resumed = BackupService(vfs, clock).resumeIfInterrupted("data")
            assertTrue(resumed.isSuccess, "сбой на операции $k: $resumed")
            val st = state(vfs)
            // Маркер записан — довершено до копии; не записан — прежнее состояние.
            assertTrue(st == (a to 2L) || st == (b to 5L), "сбой на операции $k: $st")
            assertFalse(vfs.exists("data/${BackupService.MARKER}"), "сбой на операции $k: маркер остался")
        }
    }
}
