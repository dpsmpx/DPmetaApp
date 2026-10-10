package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.backup.BackupService
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.Vfs
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * Секреты не утекают через хранилище (D-14, D-25, T8, T9): сообщение
 * отказа ввода-вывода редактируется в диагностике; в копию идут только
 * файлы данных — не временные, не прежние версии, не вытесненные.
 */
class StorageLeakTest {
    private val token = "sk-" + "ant-" + "Zz9".repeat(10)

    @Test
    fun ioErrorMessageIsRedacted() {
        val failing = object : Vfs by SimulatedVfs() {
            override fun write(path: String, bytes: ByteArray) = throw IOException("auth token=$token отвергнут")
        }
        val r = DocumentStore(failing, "data").write("settings", JsonObject.of("x" to json(1)))
        val text = assertIs<Outcome.Failure>(r).errors.single().render()
        assertFalse(text.contains(token), text)
    }

    @Test
    fun backupTakesOnlyDataFiles() {
        val vfs = SimulatedVfs()
        val store = DocumentStore(vfs, "data")
        store.write("settings", JsonObject.of("x" to json(1)))
        store.write("settings", JsonObject.of("x" to json(2)))
        vfs.write("data/settings.tmp", "мусор".toByteArray())
        vfs.write("data/old.replaced", "вытеснено".toByteArray())
        val m = (BackupService(vfs, ManualClock()).create("data", "backups/1", 1) as Outcome.Success).value
        assertEquals(listOf("settings"), m.files.map { it.path })
    }
}
