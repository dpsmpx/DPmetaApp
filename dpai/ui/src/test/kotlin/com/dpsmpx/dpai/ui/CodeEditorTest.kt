package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CodeEditorTest {
    @Test
    fun typingGroupsUndoAndNewEditDropsRedo() {
        val clock = ManualClock()
        val b = CodeEditorBuffer("", clock)
        b.edit("a", EditorSelection(1), typing = true)
        b.edit("ab", EditorSelection(2), typing = true)
        clock.advance(1.seconds)
        b.edit("abc", EditorSelection(3), typing = true)
        assertTrue(b.undo())
        assertEquals("ab", b.text)
        assertTrue(b.undo())
        assertEquals("", b.text)
        assertTrue(b.redo())
        assertEquals("ab", b.text)
        b.edit("other", EditorSelection(5))
        assertFalse(b.canRedo)
        assertTrue(b.dirty)
    }

    @Test
    fun selectionMoveSeparatesTypingAndUndoRestoresCaret() {
        val b = CodeEditorBuffer("ab")
        b.select(2)
        b.edit("abc", EditorSelection(3), typing = true)
        b.select(0)
        b.edit("Xabc", EditorSelection(1), typing = true)
        b.undo()
        assertEquals("abc", b.text)
        assertEquals(EditorSelection(0), b.state.selection)
    }

    @Test
    fun literalSearchWrapsInBothDirectionsAndHonoursCase() {
        val b = CodeEditorBuffer("A.b a.b A.b")
        assertEquals(listOf(0..2, 4..6, 8..10), b.matches("a.b").ranges)
        assertEquals(listOf(4..6), b.matches("a.b", ignoreCase = false).ranges)
        assertTrue(b.findNext("a.b"))
        assertEquals(EditorSelection(0, 3), b.state.selection)
        b.findNext("a.b", backwards = true)
        assertEquals(EditorSelection(8, 11), b.state.selection)
        b.findNext("a.b")
        assertEquals(EditorSelection(0, 3), b.state.selection)
        assertFalse(b.findNext(""))
        assertTrue(b.matches("").ranges.isEmpty())
    }

    @Test
    fun replacementsAreLiteralAndOneUndoRestoresAll() {
        val b = CodeEditorBuffer("cat cat CAT")
        assertEquals(3, b.replaceAll("cat", "cat$"))
        assertEquals("cat$ cat$ cat$", b.text)
        b.undo()
        assertEquals("cat cat CAT", b.text)
        b.select(4, 7)
        assertTrue(b.replaceNext("cat", ""))
        assertEquals("cat  CAT", b.text)
        b.undo()
        assertEquals(EditorSelection(4, 7), b.state.selection)
        b.select(7, 4)
        b.replaceSelection("dog")
        assertEquals("cat dog CAT", b.text)
    }

    @Test
    fun emptyDraftAndUnicodeSurviveHistoryAndLineNavigation() {
        val b = CodeEditorBuffer("😀\r\nsecond\n")
        assertTrue(b.goToLine(2))
        assertEquals(EditorSelection(4), b.state.selection)
        assertEquals(2, b.line)
        assertEquals(1, b.column)
        assertFalse(b.goToLine(4))
        assertFalse(b.goToLine(0))
        b.select(0, b.text.length)
        b.replaceSelection("")
        assertEquals("", b.text)
        assertTrue(b.dirty)
        b.undo()
        assertEquals("😀\r\nsecond\n", b.text)
        assertFalse(b.dirty)
    }

    @Test
    fun boundedHistoryAndOversizedReplacementLeaveDocumentUsable() {
        val b = CodeEditorBuffer("start")
        repeat(CodeEditorBuffer.MAX_HISTORY + 10) { b.edit("value $it", EditorSelection(0)) }
        var undos = 0
        while (b.undo()) undos++
        assertEquals(CodeEditorBuffer.MAX_HISTORY, undos)
        val text = b.text
        assertFalse(b.edit("x".repeat(CodeEditorBuffer.MAX_CHARS + 1), EditorSelection(0)))
        assertEquals(text, b.text)
        val many = CodeEditorBuffer("x".repeat(CodeEditorBuffer.MAX_MATCHES + 1))
        assertTrue(many.matches("x").limited)
        assertEquals(0, many.replaceAll("x", "y"), "ограниченная выборка не заменяет только часть файла")
    }

    @Test
    fun previewKeepsChangedRangeAndBoundsOutput() {
        val preview = EditorChanges.preview("same\nold\nend", "same\nnew\nend")
        assertEquals(listOf(DiffKind.HUNK, DiffKind.REMOVED, DiffKind.ADDED), preview.map { it.kind })
        assertEquals("− old", preview[1].text)
        assertEquals("+ new", preview[2].text)
        assertEquals(DiffKind.NOTE, EditorChanges.preview("same", "same").single().kind)
        assertTrue(EditorChanges.preview("a\n".repeat(1000), "b\n".repeat(1000), 20).size <= 22)
    }
}
