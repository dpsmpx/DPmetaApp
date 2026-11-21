package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class KotlinCompletionCommandTest {
    private val uri = "file:///p/main.kt"
    private val text = "val x = Wi"
    private val command = "jetbrains.kotlin.completion.apply"
    private fun parse(s: String) = (JsonParser().parse(s) as Outcome.Success).value
    private val quiet = object : LspClient.Listener {
        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
        override fun stopped(reason: Diagnostic) = Unit
    }
    private fun edits(address: String = uri) = """{"changes":{"$address":[{"range":{"start":{"line":0,"character":8},"end":{"line":0,"character":10}},"newText":"Widget"}]}}"""
    private fun sendEdit(server: FakeServer, edit: String, id: String = "apply") = server.send(parse("""{"jsonrpc":"2.0","id":"$id","method":"workspace/applyEdit","params":{"edit":$edit}}"""))
    private fun server(action: (FakeServer) -> JsonValue?): Pair<LspClient, FakeServer> {
        lateinit var fake: FakeServer
        fake = FakeServer { method, _ -> when (method) {
            "initialize" -> parse("""{"capabilities":{"completionProvider":{},"executeCommandProvider":{"commands":["$command"]}}}""")
            "textDocument/completion" -> parse("""[{"label":"Widget","textEdit":{"range":{"start":{"line":0,"character":10},"end":{"line":0,"character":10}},"newText":""},"command":{"title":"Apply","command":"$command","arguments":[1]},"data":{"KotlinCompletionItemKey":1,"configurationEntryId":"KotlinCompletionProvider"}}]""")
            "workspace/executeCommand" -> action(fake)
            else -> JsonNull
        } }
        val client = LspClient(fake.clientIn, fake.clientOut, "file:///p", quiet)
        assertTrue(client.start() is Outcome.Success)
        assertTrue(client.open(uri, "kotlin", text) is Outcome.Success)
        return client to fake
    }
    private fun choice(client: LspClient) = (client.completion(uri, Position(0, 10)) as Outcome.Success).value.items.single()
    private fun responses(server: FakeServer, count: Int) {
        repeat(100) { if (server.responses.size < count) CountDownLatch(1).await(10, TimeUnit.MILLISECONDS) }
        assertTrue(server.responses.size >= count)
    }

    @Test fun preparesOnlySelectedBufferAndReportsThatNeitherEditNorNavigationWasApplied() {
        val (client, fake) = server { s ->
            sendEdit(s, edits())
            s.send(parse("""{"jsonrpc":"2.0","id":"caret","method":"window/showDocument","params":{"uri":"$uri","external":false,"selection":{"start":{"line":0,"character":12},"end":{"line":0,"character":12}}}}"""))
            parse("true")
        }
        val item = choice(client)
        val prepared = (client.resolveCompletion(item) as Outcome.Success).value
        assertFalse(prepared.unsupported)
        assertEquals(1 to text, client.document(uri), "подготовка не меняет даже снимок документа")
        val applied = (Assist.complete(text, 10, prepared) as Outcome.Success).value
        assertEquals("val x = Widget", applied.text)
        assertEquals(12, applied.cursor)
        responses(fake, 2)
        assertEquals(parse("false"), (fake.responses.first()["result"] as JsonObject)["applied"])
        assertEquals(parse("false"), (fake.responses[1]["result"] as JsonObject)["success"])
        assertTrue(Assist.complete(text + "external", 10, prepared) is Outcome.Failure)
        client.stop()
    }

    @Test fun otherFileUnknownVersionAndMultipleBatchesNeverProduceAPartialCompletion() {
        val wrongVersion = """{"documentChanges":[{"textDocument":{"uri":"$uri","version":99},"edits":[{"range":{"start":{"line":0,"character":8},"end":{"line":0,"character":10}},"newText":"Widget"}]}]}"""
        for (scenario in 0..2) {
            val (client, _) = server { s ->
                when (scenario) {
                    0 -> sendEdit(s, edits("file:///p/other.kt"))
                    1 -> sendEdit(s, wrongVersion)
                    else -> { sendEdit(s, edits(), "first"); sendEdit(s, edits(), "second") }
                }
                parse("true")
            }
            assertTrue(client.resolveCompletion(choice(client)) is Outcome.Failure)
            assertEquals(1 to text, client.document(uri))
            client.stop()
        }
    }

    @Test fun aNewListInvalidatesCachedCommandIdsEvenWhenTextAndVersionAreUnchanged() {
        val (client, fake) = server { s -> sendEdit(s, edits()); parse("true") }
        val previous = choice(client)
        choice(client)
        assertTrue(client.resolveCompletion(previous) is Outcome.Failure)
        assertTrue("workspace/executeCommand" !in fake.methods())
        client.stop()
    }

    @Test fun unsolicitedEditIsRefusedAndTimeoutAfterCaptureDoesNotBecomeSuccess() {
        val (client, fake) = server { s -> sendEdit(s, edits(), "selected"); null }
        sendEdit(fake, edits(), "unsolicited")
        responses(fake, 1)
        assertEquals(parse("false"), (fake.responses.first()["result"] as JsonObject)["applied"])
        assertTrue(client.resolveCompletion(choice(client), 200.milliseconds) is Outcome.Failure)
        assertEquals(1 to text, client.document(uri))
        client.stop()
    }
}
