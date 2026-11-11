package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.ui.EditorSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDraftsTest {
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
            keys.forEach(Drafts::dropFile)
        }
    }
}
