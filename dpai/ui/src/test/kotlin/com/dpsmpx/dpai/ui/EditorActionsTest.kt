package com.dpsmpx.dpai.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorActionsTest {
    @Test fun tabUsesNextIndentStopAndUndoRestoresSelection() {
        val b = CodeEditorBuffer("x")
        b.select(1)
        assertTrue(EditorActions.indent(b, 4))
        assertEquals("x   ", b.text)
        b.undo(); assertEquals(EditorSelection(1), b.state.selection)
        assertTrue(EditorActions.indent(b, tabs = true)); assertEquals("x\t", b.text)
    }
    @Test fun multilineIndentExcludesNextRowAndKeepsBackwardSelectionAndCrLf() {
        val b = CodeEditorBuffer("a\r\nb\r\nc")
        b.select(6, 0)
        assertTrue(EditorActions.indent(b, 2))
        assertEquals("  a\r\n  b\r\nc", b.text)
        assertTrue(b.state.selection.start > b.state.selection.end)
        b.undo(); assertEquals("a\r\nb\r\nc", b.text); assertEquals(EditorSelection(6, 0), b.state.selection)
    }
    @Test fun outdentRemovesOneTabOrAtMostConfiguredSpaces() {
        val b = CodeEditorBuffer("\ta\n  b\n    c")
        b.select(0, b.text.length)
        assertTrue(EditorActions.indent(b, 2, backwards = true))
        assertEquals("a\nb\n  c", b.text)
        b.undo(); assertEquals(b.original, b.text)
    }
    @Test fun commentToggleKeepsWhitespaceBlankLinesAndLineEndings() {
        val b = CodeEditorBuffer("  val a = 1\r\n\r\n  val b = 2")
        b.select(0, b.text.length)
        EditorActions.comment(b, "test.kt")
        assertEquals("  // val a = 1\r\n\r\n  // val b = 2", b.text)
        EditorActions.comment(b, "test.kt"); assertEquals(b.original, b.text)
        b.undo(); assertTrue(b.text.contains("// val"))
        assertFalse(EditorActions.comment(b, "test.json"))
    }
    @Test fun duplicateLastLineInsertsCrLfAndKeepsCaretColumn() {
        val b = CodeEditorBuffer("a\r\nb")
        b.select(4)
        EditorActions.duplicate(b)
        assertEquals("a\r\nb\r\nb", b.text)
        assertEquals(EditorSelection(7), b.state.selection)
        b.undo(); assertEquals(b.original, b.text)
    }
    @Test fun duplicateSelectionSelectsNewCopy() {
        val b = CodeEditorBuffer("abc")
        b.select(0, 2); EditorActions.duplicate(b)
        assertEquals("ababc", b.text); assertEquals(EditorSelection(2, 4), b.state.selection)
    }
    @Test fun deleteLastLineDoesNotLeaveHalfCrLf() {
        val b = CodeEditorBuffer("a\r\nb")
        b.select(3); EditorActions.deleteLines(b)
        assertEquals("a", b.text)
        b.undo(); assertEquals(b.original, b.text)
        b.select(0, b.text.length); EditorActions.deleteLines(b); assertEquals("", b.text)
    }
    @Test fun moveRowsPreservesMixedSeparatorsAndSelectionBoundary() {
        val b = CodeEditorBuffer("a\r\nbb\nc")
        b.select(3, 6); EditorActions.moveLines(b, true)
        assertEquals("bb\r\na\nc", b.text)
        assertEquals(EditorSelection(0, 4), b.state.selection)
        b.undo(); assertEquals(b.original, b.text)
        assertEquals(EditorSelection(3, 6), b.state.selection)
    }
    @Test fun moveAtEdgesIsNoOp() {
        val b = CodeEditorBuffer("a\nb")
        assertFalse(EditorActions.moveLines(b, true))
        b.select(2); assertFalse(EditorActions.moveLines(b, false))
    }
    @Test fun surroundKeepsUnicodeBackwardSelectionAndSingleUndo() {
        val b = CodeEditorBuffer("😀a")
        b.select(3, 0); EditorActions.surround(b, "(", ")")
        assertEquals("(😀a)", b.text); assertEquals(EditorSelection(4, 1), b.state.selection)
        b.undo(); assertEquals(b.original, b.text)
        b.select(0); EditorActions.surround(b, "[", "]"); assertEquals(EditorSelection(1), b.state.selection)
    }
    @Test fun nativeReplaceBatchIsOneUndoAndRedo() {
        val b = CodeEditorBuffer("before")
        b.select(0, 6); b.edit("", EditorSelection(0)); b.edit("after", EditorSelection(5), coalesce = true)
        b.undo(); assertEquals("before", b.text); assertEquals(EditorSelection(0, 6), b.state.selection)
        b.redo(); assertEquals("after", b.text)
    }
    @Test fun oversizedCommandIsRefusedWithoutPartialEdit() {
        val b = CodeEditorBuffer("a".repeat(CodeEditorBuffer.MAX_CHARS))
        b.select(0, b.text.length)
        assertFalse(EditorActions.surround(b, "{", "}"))
        assertFalse(b.dirty); assertFalse(b.canUndo)
    }
}
