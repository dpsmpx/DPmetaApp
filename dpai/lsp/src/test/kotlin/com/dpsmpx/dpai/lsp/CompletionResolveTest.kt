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

class CompletionResolveTest {
    private val uri = "file:///p/main.kt"
    private val text = "val x = Wi"
    private val quiet = object : LspClient.Listener {
        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
        override fun stopped(reason: Diagnostic) = Unit
    }
    private fun parse(s: String) = (JsonParser().parse(s) as Outcome.Success).value
    private fun client(resolve: Boolean = true, reply: (String, JsonValue?) -> JsonValue): Pair<LspClient, FakeServer> {
        val server = FakeServer { method, params -> if (method == "initialize") parse("""{"capabilities":{"completionProvider":{"resolveProvider":$resolve}}}""") else reply(method, params) }
        val client = LspClient(server.clientIn, server.clientOut, "file:///p", quiet)
        assertTrue(client.start() is Outcome.Success)
        assertTrue(client.open(uri, "kotlin", text) is Outcome.Success)
        return client to server
    }
    private fun choice(client: LspClient) = (client.completion(uri, Position(0, text.length)) as Outcome.Success).value.items.single()

    @Test fun resolvingSelectedItemAddsImportAndInsertionAsOneCheckedEdit() {
        val (client, _) = client { method, params -> when (method) {
            "textDocument/completion" -> parse("""[{"label":"Widget","data":{"id":4}}]""")
            "completionItem/resolve" -> {
                assertEquals(parse("""{"label":"Widget","data":{"id":4}}"""), params)
                parse("""{"label":"Widget","additionalTextEdits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"import demo.Widget\n"}]}""")
            }
            else -> JsonNull
        } }
        val item = choice(client)
        assertTrue(item.resolvable)
        val resolved = (client.resolveCompletion(item) as Outcome.Success).value
        assertEquals("import demo.Widget\nval x = Widget", (Assist.complete(text, text.length, resolved) as Outcome.Success).value.text)
        client.stop()
    }

    @Test fun outdatedDocumentOrAnotherClientNeverReceivesOpaqueData() {
        var resolves = 0
        val answer: (String, JsonValue?) -> JsonValue = { method, _ -> when (method) {
            "textDocument/completion" -> parse("""[{"label":"Widget","data":9}]""")
            "completionItem/resolve" -> { resolves++; parse("""{"label":"Widget"}""") }
            else -> JsonNull
        } }
        val (first, _) = client(reply = answer)
        val (other, _) = client(reply = answer)
        val item = choice(first)
        assertTrue(other.resolveCompletion(item) is Outcome.Failure)
        assertTrue(first.update(uri, text + "d") is Outcome.Success)
        assertTrue(first.resolveCompletion(item) is Outcome.Failure)
        assertEquals(0, resolves)
        first.stop(); other.stop()
    }

    @Test fun unsupportedSnippetOrCommandNeverInsertsPartialCompletion() {
        for (field in listOf("\"insertTextFormat\":2", "\"command\":{\"title\":\"extra\",\"command\":\"unknown\"}")) {
            val item = assertNotNull(LspParse.completionItem(parse("""{"label":"Widget",$field}""")))
            assertTrue(Assist.complete(text, text.length, item) is Outcome.Failure)
        }
        val (client, server) = client(resolve = false) { method, _ -> if (method == "textDocument/completion") parse("""[{"label":"Widget","data":8}]""") else JsonNull }
        val item = choice(client)
        assertFalse(item.resolvable)
        assertTrue(client.resolveCompletion(item) is Outcome.Success)
        assertTrue("completionItem/resolve" !in server.methods())
        client.stop()
    }
}
