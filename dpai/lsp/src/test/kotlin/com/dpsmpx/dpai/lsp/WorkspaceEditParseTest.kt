package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceEditParseTest {
    private val uri = "file:///p/a.kt"
    private val edit = """{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"b"}"""
    private fun parse(text: String) = LspParse.workspaceEdit(JsonParser().parse(text).valueOrNull())

    @Test
    fun documentVersionIsKeptAndLegacyRepresentationIsNotAppliedTwice() {
        val result = assertNotNull(parse("""{"changes":{"$uri":[$edit]},"documentChanges":[{"textDocument":{"uri":"$uri","version":7},"edits":[$edit]}]}"""))
        assertEquals(7, result.versions[uri])
        assertEquals(1, result.changes.getValue(uri).size)
    }

    @Test
    fun contradictoryVersionsAndWrongShapesAreRejected() {
        assertNull(parse("""{"documentChanges":[{"textDocument":{"uri":"$uri","version":1},"edits":[$edit]},{"textDocument":{"uri":"$uri","version":2},"edits":[$edit]}]}"""))
        for (bad in listOf("\"7\"", "1.5", "2147483648")) {
            assertNull(parse("""{"documentChanges":[{"textDocument":{"uri":"$uri","version":$bad},"edits":[$edit]}]}"""))
        }
        assertNull(parse("""{"changes":true,"documentChanges":[]}"""))
        assertNull(parse("""{"changes":{},"documentChanges":"wrong"}"""))
    }

    @Test
    fun nullRenameAndOptionalNullFieldsAreHarmless() {
        assertTrue(assertNotNull(parse("null")).changes.isEmpty())
        assertTrue(assertNotNull(parse("""{"changes":null,"documentChanges":null}""")).changes.isEmpty())
        val result = assertNotNull(parse("""{"changes":null,"documentChanges":[{"textDocument":{"uri":"$uri","version":null},"edits":[$edit]}]}"""))
        assertEquals(1, result.changes.getValue(uri).size)
    }

    @Test
    fun excessiveEditsAreRefusedRatherThanTruncated() {
        val edits = List(LspParse.MAX_ITEMS + 1) { edit }.joinToString(",")
        assertTrue(parse("""{"changes":{"$uri":[$edits]}}""") == null)
    }
}
