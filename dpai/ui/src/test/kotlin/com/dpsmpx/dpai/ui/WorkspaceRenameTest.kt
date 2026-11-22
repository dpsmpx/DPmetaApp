package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.Position
import com.dpsmpx.dpai.lsp.Range
import com.dpsmpx.dpai.lsp.TextEdit
import com.dpsmpx.dpai.lsp.WorkspaceEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkspaceRenameTest {
    private val uri = "file:///p/a.kt"
    private val edits = listOf(TextEdit(Range(Position(0, 0), Position(0, 3)), "bar"))
    private class Documents {
        val open = hashMapOf<String, Pair<Int, String>>()
        var closed = 0
        fun borrow(uri: String, language: String, text: String): Outcome<AutoCloseable> {
            assertEquals("kotlin", language)
            open[uri] = 1 to text
            return Outcome.success(AutoCloseable { open.remove(uri); closed++ })
        }
    }

    @Test fun unknownVersionIsObservedAndRenameIsRequestedAgainWithoutInventingAVersion() {
        val docs = Documents(); var calls = 0
        val result = WorkspaceRename.prepare("/p", { Outcome.success("foo") }, docs.open::get, docs::borrow) {
            calls++
            Outcome.success(WorkspaceEdit(mapOf(uri to edits), mapOf(uri to (docs.open[uri]?.first ?: 0))))
        }
        assertTrue(result is Outcome.Success)
        assertEquals("bar", result.value.files.single().after)
        assertEquals(2, calls)
        assertEquals(1, docs.closed)
        assertTrue(docs.open.isEmpty())
    }

    @Test fun changedDiskBetweenDiscoveryAndFinalReplyRefusesTheWholeRename() {
        val docs = Documents(); var text = "foo"; var calls = 0
        val result = WorkspaceRename.prepare("/p", { Outcome.success(text) }, docs.open::get, docs::borrow) {
            calls++
            if (calls == 2) text = "external"
            Outcome.success(WorkspaceEdit(mapOf(uri to edits)))
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(2, calls)
        assertEquals(0, docs.closed)
    }

    @Test fun growingIndexIsBoundedAndNeverProducesAPartialPlan() {
        val docs = Documents(); var calls = 0
        val result = WorkspaceRename.prepare("/p", { Outcome.success("foo") }, docs.open::get, docs::borrow) {
            calls++
            Outcome.success(WorkspaceEdit((1..calls).associate { "file:///p/$it.kt" to edits }))
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(3, calls)
    }

    @Test fun outsideFilesAreRejectedBeforeLoadingOrOpeningAnyDocument() {
        val docs = Documents(); var reads = 0
        val result = WorkspaceRename.prepare("/p", { reads++; Outcome.success("foo") }, docs.open::get, docs::borrow) {
            Outcome.success(WorkspaceEdit(mapOf(uri to edits, "file:///outside.kt" to edits)))
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(0, reads)
        assertTrue(docs.open.isEmpty())
    }

    @Test fun anotherBufferCannotBeReplacedWithDiskText() {
        val docs = Documents(); docs.open[uri] = 3 to "unsaved"
        val result = WorkspaceRename.prepare("/p", { Outcome.success("foo") }, docs.open::get, docs::borrow) {
            Outcome.success(WorkspaceEdit(mapOf(uri to edits), mapOf(uri to 3)))
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(3 to "unsaved", docs.open[uri])
        assertEquals(0, docs.closed)
    }

    @Test fun serverFailureReleasesTemporarilyOpenedDocuments() {
        val docs = Documents(); var calls = 0
        val result = WorkspaceRename.prepare("/p", { Outcome.success("foo") }, docs.open::get, docs::borrow) {
            if (++calls == 1) Outcome.success(WorkspaceEdit(mapOf(uri to edits), mapOf(uri to 0)))
            else Outcome.failure(DiagCode.LSP_REQUEST_FAILED, "служба отказала")
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(1, docs.closed)
        assertTrue(docs.open.isEmpty())
    }

    @Test fun aNewUriCannotAliasTheSnapshotOfTheSamePath() {
        val docs = Documents(); var calls = 0
        val result = WorkspaceRename.prepare("/p", { Outcome.success("foo") }, docs.open::get, docs::borrow) {
            Outcome.success(WorkspaceEdit(mapOf((if (++calls == 1) uri else "file:///p/%61.kt") to edits)))
        }
        assertTrue(result is Outcome.Failure)
        assertEquals(2, calls)
    }
}
