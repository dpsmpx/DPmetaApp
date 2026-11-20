package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.lsp.DocumentUri
import com.dpsmpx.dpai.lsp.Position
import com.dpsmpx.dpai.lsp.Range
import com.dpsmpx.dpai.lsp.TextEdit
import com.dpsmpx.dpai.lsp.WorkspaceEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceEditsTest {
    private val a = "file:///p/a.cpp"
    private val b = "file:///p/a.h"
    private val rename = TextEdit(Range(Position(0, 0), Position(0, 3)), "bar")

    @Test
    fun preparesTheWholeProjectChangeWithVersionsAndRelativePaths() {
        val edit = WorkspaceEdit(mapOf(a to listOf(rename), b to listOf(rename)), mapOf(a to 4))
        val result = assertIs<Outcome.Success<WorkspaceEditPlan>>(WorkspaceEdits.prepare(edit, "/p/") { path ->
            Outcome.success(WorkspaceSource("foo();", if (path == "a.cpp") 4 else null))
        }).value
        assertEquals(listOf("a.cpp", "a.h"), result.files.map { it.path })
        assertTrue(result.files.all { it.before == "foo();" && it.after == "bar();" })
        assertEquals(2, result.editCount)
    }

    @Test
    fun validatesAllPathsBeforeLoadingAnything() {
        for (outside in listOf("file:///p-other/a.cpp", "file:///p/../a.cpp",
            "file:///p/%2e%2e/a.cpp", "file:///p/.git/config", "file:///p/.GIT/config",
            "file:///p/x%2F..%2Fa.cpp", "file://host/p/a.cpp",
            "file:///p/a.cpp?query", "file:///p/a.cpp#fragment")) {
            var loads = 0
            val result = WorkspaceEdits.prepare(WorkspaceEdit(mapOf(a to listOf(rename), outside to listOf(rename))), "/p") {
                loads++
                Outcome.success(WorkspaceSource("foo"))
            }
            assertIs<Outcome.Failure>(result, outside)
            assertEquals(0, loads, outside)
        }
    }

    @Test
    fun differentUrisCannotAliasTheSameFile() {
        val result = WorkspaceEdits.paths(WorkspaceEdit(mapOf(a to listOf(rename), "file:///p/%61.cpp" to listOf(rename))), "/p")
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(result).errors.first().code)
    }

    @Test
    fun staleOrUnverifiableDocumentVersionRejectsEverything() {
        for (version in listOf(null, 3, 5)) {
            val result = WorkspaceEdits.prepare(WorkspaceEdit(mapOf(a to listOf(rename)), mapOf(a to 4)), "/p") {
                Outcome.success(WorkspaceSource("foo", version))
            }
            assertEquals(DiagCode.STATE_CONFLICT, assertIs<Outcome.Failure>(result).errors.first().code)
        }
    }

    @Test
    fun overlappingOrInvalidEditsNeverProduceAPartialPlan() {
        val broken = listOf(rename, TextEdit(Range(Position(0, 1), Position(0, 3)), "x"))
        val result = WorkspaceEdits.prepare(WorkspaceEdit(mapOf(a to listOf(rename), b to broken)), "/p") {
            Outcome.success(WorkspaceSource("foo"))
        }
        assertIs<Outcome.Failure>(result)
    }

    @Test
    fun unreadableFileRefusesTheWholeSet() {
        val result = WorkspaceEdits.prepare(WorkspaceEdit(mapOf(a to listOf(rename), b to listOf(rename))), "/p") {
            if (it == "a.h") Outcome.failure(DiagCode.STORAGE_IO_FAILED, "не прочитан")
            else Outcome.success(WorkspaceSource("foo"))
        }
        assertEquals(DiagCode.STORAGE_IO_FAILED, assertIs<Outcome.Failure>(result).errors.first().code)
    }

    @Test
    fun utf8PathsAndRootDirectoryAreMappedExactly() {
        val uri = DocumentUri.ofPath("/p/файл с пробелом.cpp")
        assertEquals(mapOf(uri to "файл с пробелом.cpp"),
            assertIs<Outcome.Success<Map<String, String>>>(WorkspaceEdits.paths(WorkspaceEdit(mapOf(uri to listOf(rename))), "/p")).value)
        assertEquals(mapOf(a to "p/a.cpp"),
            assertIs<Outcome.Success<Map<String, String>>>(WorkspaceEdits.paths(WorkspaceEdit(mapOf(a to listOf(rename))), "/")).value)
    }

    @Test
    fun tooManyFilesAreRefusedBeforeIo() {
        val files = (0..WorkspaceEdits.MAX_FILES).associate { "file:///p/$it.cpp" to listOf(rename) }
        var loads = 0
        assertIs<Outcome.Failure>(WorkspaceEdits.prepare(WorkspaceEdit(files), "/p") {
            loads++
            Outcome.success(WorkspaceSource("foo"))
        })
        assertEquals(0, loads)
    }

    @Test
    fun splitSurrogatesAreNotAcceptedAsNewFileContents() {
        val edit = TextEdit(Range(Position(0, 1), Position(0, 2)), "")
        assertIs<Outcome.Failure>(WorkspaceEdits.prepare(WorkspaceEdit(mapOf(a to listOf(edit))), "/p") {
            Outcome.success(WorkspaceSource("😀foo"))
        })
    }

    @Test
    fun emptyChangeDoesNotOpenFiles() {
        val result = WorkspaceEdits.prepare(WorkspaceEdit(emptyMap()), "/p") { error("не должно читаться") }
        assertTrue(assertIs<Outcome.Success<WorkspaceEditPlan>>(result).value.files.isEmpty())
    }
}
