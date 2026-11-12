package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.ui.EditorSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDraftsTest {
    @Test
    fun staleCloseCannotRemoveNewDraftAndSavingKeepsUndo() {
        val key = "file:close-generation-test"
        try {
            val first = Drafts.startFile(key, "a".toByteArray(), "a")!!
            Drafts.dropFile(key)
            val current = Drafts.startFile(key, "b".toByteArray(), "b")!!
            kotlin.test.assertIs<com.dpsmpx.dpai.core.result.Outcome.Failure>(Drafts.dropFile(key, first))
            assertEquals(current, Drafts.file(key))
            current.editor.edit("c", EditorSelection(1))
            kotlin.test.assertIs<com.dpsmpx.dpai.core.result.Outcome.Success<Unit>>(Drafts.savedFile(key, current, "c".toByteArray(), "c"))
            kotlin.test.assertFalse(current.editor.dirty)
            current.editor.undo()
            assertTrue(current.editor.dirty)
            assertEquals("b", current.editor.text)
            kotlin.test.assertContentEquals("c".toByteArray(), current.original)
        } finally { Drafts.dropFile(key) }
    }

    @Test
    fun dirtyHomeAliasBlocksSelectedWorkspaceUntilDiscarded() {
        val ids = com.dpsmpx.dpai.core.ids.IdGenerator(com.dpsmpx.dpai.core.clock.ManualClock(), com.dpsmpx.dpai.core.clock.RandomSource.seeded(133))
        val location = com.dpsmpx.dpai.ui.WorkspaceLocation(ids.next(com.dpsmpx.dpai.core.ids.IdKinds.PROJECT), ids.next(com.dpsmpx.dpai.core.ids.IdKinds.REPOSITORY), ids.next(com.dpsmpx.dpai.core.ids.IdKinds.TARGET), null, null, "/root/repo", "main")
        val key = "file:HOME-test"
        try {
            val d = Drafts.startFile(key, "before".toByteArray(), "before", "/root/repo/file.txt")!!
            kotlin.test.assertFalse(Drafts.dirtyIn(location, true))
            d.editor.edit("changed", EditorSelection(7))
            assertTrue(Drafts.dirtyIn(location, true))
            kotlin.test.assertFalse(Drafts.dirtyIn(location.copy(path = "/root/repo-other"), true))
            kotlin.test.assertFalse(Drafts.dirtyIn(location, false))
            Drafts.dropFile(key)
            kotlin.test.assertFalse(Drafts.dirtyIn(location, true))
        } finally { Drafts.dropFile(key) }
    }

    @Test
    fun emptyUnsavedTextRemainsRecoverableWithoutTreeAndSlotsDoNotEvict() {
        val keys = List(9) { "file:draft-recovery-test\u0001$it.txt" }
        try {
            val first = Drafts.startFile(keys[0], "before".toByteArray(), "before")!!
            first.editor.edit("", EditorSelection(0))
            assertEquals("", Drafts.file(keys[0])!!.editor.text)
            assertTrue(Drafts.fileDrafts().any { it.first == keys[0] }, "пустая правка тоже восстанавливается")
            for (key in keys.drop(1).take(7)) Drafts.startFile(key, byteArrayOf(), "")!!
            assertNull(Drafts.startFile(keys[8], byteArrayOf(), ""))
            assertEquals(first, Drafts.file(keys[0]), "старый черновик не вытесняется")
            Drafts.dropFile(keys[1])
            assertTrue(Drafts.startFile(keys[8], byteArrayOf(), "") != null)
        } finally {
            keys.forEach { Drafts.dropFile(it) }
        }
    }
}
