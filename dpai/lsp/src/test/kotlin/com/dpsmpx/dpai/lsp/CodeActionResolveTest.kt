package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeActionResolveTest {
    private fun parse(s: String) = (JsonParser().parse(s) as Outcome.Success).value
    private val uri = "file:///p/a.py"
    private val edit = """{"changes":{"file:///p/a.py":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"b"}]}}"""
    private val quiet = object : LspClient.Listener {
        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
        override fun stopped(reason: Diagnostic) = Unit
    }
    private fun client(reply: (String, JsonValue?) -> JsonValue): Pair<LspClient, FakeServer> {
        val server = FakeServer { method, params ->
            if (method == "initialize") parse("""{"capabilities":{"codeActionProvider":{"resolveProvider":true}}}""") else reply(method, params)
        }
        val client = LspClient(server.clientIn, server.clientOut, "file:///p", quiet)
        assertTrue(client.start() is Outcome.Success)
        return client to server
    }

    @Test fun helperDataIsResolvedOnlyByItsOriginalClientAndSurvivesUnchanged() {
        val original = parse("""{"title":"fix","data":{"opaque":[7,"id"]},"isPreferred":true}""")
        var asked = 0
        val (main, _) = client { method, _ -> if (method == "textDocument/codeAction") parse("[]") else JsonNull }
        val (helper, server) = client { method, params -> when (method) {
            "textDocument/codeAction" -> parse("[${com.dpsmpx.dpai.core.serialization.CanonicalJson.write(original)}]")
            "codeAction/resolve" -> { asked++; assertEquals(original, params); parse("""{"title":"fix","edit":$edit}""") }
            else -> JsonNull
        } }
        val session = LanguageSession(main, helper)
        val action = (session.codeActions(uri, Range(Position(0, 0), Position(0, 1)), emptyList()) as Outcome.Success).value.single()
        assertTrue(action.resolvable)
        assertTrue(Assist.fixes(listOf(action)).applicable.isNotEmpty())
        assertNotNull((session.resolveCodeAction(action) as Outcome.Success).value.edit)
        assertEquals(1, asked)
        assertTrue(main.resolveCodeAction(action) is Outcome.Failure)
        assertEquals(1, asked)
        assertTrue("codeAction/resolve" in server.methods())
        session.stop()
        assertTrue(session.resolveCodeAction(action) is Outcome.Failure)
    }

    @Test fun disabledAndUnknownCommandWithAnEditAreNeverAppliedPartially() {
        val actions = assertNotNull(LspParse.codeActions(parse("""[
            {"title":"disabled","isPreferred":true,"disabled":{"reason":"no index"},"edit":$edit},
            {"title":"needs command","isPreferred":true,"command":{"title":"later","command":"unknown"},"edit":$edit}
        ]""")))
        assertTrue(Assist.fixes(actions).applicable.isEmpty())
        assertEquals(0, Assist.fixAll(uri, listOf(actions)).fixed)
        assertEquals("no index", actions.first().disabledReason)
    }

    @Test fun legacyClangdApplyFixIsDecodedAsTextAndNeverExecuted() {
        val action = assertNotNull(LspParse.codeAction(parse("""{"title":"insert","command":"clangd.applyFix","arguments":[$edit]}""")))
        assertNotNull(action.edit)
        assertFalse(action.requiresCommand)
        assertEquals(listOf(action), Assist.fixes(listOf(action)).applicable)
        assertEquals(null, LspParse.codeAction(parse("""{"title":"insert","command":"clangd.applyFix","arguments":[$edit,$edit]}""")))
    }
}
