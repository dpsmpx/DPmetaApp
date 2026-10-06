package com.dpsmpx.dpai.storage

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.migration.Migration
import com.dpsmpx.dpai.storage.migration.Migrator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MigratorTest {
    private fun doc(version: Int, vararg fields: Pair<String, JsonValue>) =
        JsonObject(linkedMapOf(*FormatHeader.write("dpai.test", version), *fields))

    private val renameTitle = Migration(1, "title → name") { d ->
        Outcome.success(JsonObject(d.fields - "title" + ("name" to d.fields.getValue("title")) + ("version" to json(2))))
    }
    private val addPriority = Migration(2, "priority по умолчанию") { d ->
        Outcome.success(JsonObject(d.fields + ("priority" to json("NORMAL")) + ("version" to json(3))))
    }
    private val migrator = Migrator("dpai.test", 3, listOf(renameTitle, addPriority))

    @Test
    fun migratesThroughTheChain() {
        val r = migrator.migrate(doc(1, "title" to json("задача")))
        val m = assertIs<Outcome.Success<Migrator.Migrated>>(r).value
        assertEquals(doc(3, "name" to json("задача"), "priority" to json("NORMAL")), m.document)
        assertEquals(listOf(1, 2), m.applied)
        assertEquals(2, r.diagnostics.count { it.code == DiagCode.MIGRATION_APPLIED })
        assertEquals(emptyList(), (migrator.migrate(m.document) as Outcome.Success).value.applied)
    }

    @Test
    fun failedStepLeavesDocumentUnchangedAndExplainsWhy() {
        val broken = Migrator("dpai.test", 2, listOf(Migration(1, "ломается") { Outcome.success(it) }))
        val input = doc(1, "title" to json("x"))
        val f = assertIs<Outcome.Failure>(broken.migrate(input))
        assertEquals(DiagCode.MIGRATION_FAILED, f.errors.single().code)
        assertTrue(f.errors.single().causes.isNotEmpty())
        val throwing = Migrator("dpai.test", 2, listOf(Migration(1, "бросает") { error("bug") }))
        assertEquals(DiagCode.MIGRATION_FAILED, assertIs<Outcome.Failure>(throwing.migrate(input)).errors.single().code)
    }

    @Test
    fun newerAndTooOldAreRejected() {
        assertEquals(DiagCode.FORMAT_VERSION_NEWER, assertIs<Outcome.Failure>(migrator.migrate(doc(4))).errors.single().code)
        val noOld = Migrator("dpai.test", 3, listOf(addPriority))
        assertEquals(DiagCode.FORMAT_VERSION_UNSUPPORTED, assertIs<Outcome.Failure>(noOld.migrate(doc(1))).errors.single().code)
        assertFailsWith<IllegalArgumentException> { Migrator("dpai.test", 3, listOf(renameTitle)) }
    }

    /** Миграция при чтении: старый документ заменён атомарно, прежний — точка отката; сбой — прежний или новый. */
    @Test
    fun storeMigratesOnReadAtomically() {
        val old = doc(1, "title" to json("задача"))
        val vfs = SimulatedVfs()
        DocumentStore(vfs, "data").write("task", old)
        val r = DocumentStore(vfs, "data").readMigrated("task", migrator)
        assertEquals(3, (r.valueOrNull()!!.content["version"] as com.dpsmpx.dpai.core.serialization.JsonNumber).toLongExact()?.toInt())
        assertEquals(r.valueOrNull()!!.content, DocumentStore(vfs, "data").read("task").valueOrNull()!!.content)
        crashEverywhere(
            setup = { DocumentStore(it, "data").write("task", old) },
            action = { DocumentStore(it, "data").readMigrated("task", migrator) },
        ) { v, k ->
            val content = DocumentStore(v, "data").read("task").valueOrNull()?.content
            assertTrue(content == old || content == r.valueOrNull()!!.content, "сбой на операции $k")
        }
    }
}
