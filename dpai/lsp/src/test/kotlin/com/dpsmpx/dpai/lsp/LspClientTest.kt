package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LspClientTest {
    private val uri = "file:///p/a.cpp"

    private fun parse(text: String): JsonValue = (JsonParser().parse(text) as Outcome.Success).value

    private class Seen : LspClient.Listener {
        val diagnostics = LinkedBlockingQueue<Triple<String, Int?, List<LspDiagnostic>>>()
        val stopped = LinkedBlockingQueue<Diagnostic>()

        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) {
            diagnostics += Triple(uri, version, items)
        }

        override fun stopped(reason: Diagnostic) {
            stopped += reason
        }
    }

    private fun capabilities(sync: Int) = """{"capabilities":{"textDocumentSync":{"openClose":true,"change":$sync},"completionProvider":{"triggerCharacters":[".",">",":"]},"hoverProvider":true,"renameProvider":{"prepareProvider":true},"definitionProvider":false},"serverInfo":{"name":"fake","version":"1"}}"""

    private fun started(sync: Int = 2, answer: (String, JsonValue?) -> JsonValue? = { _, _ -> JsonNull }): Triple<LspClient, FakeServer, Seen> {
        val server = FakeServer { method, params -> if (method == "initialize") parse(capabilities(sync)) else answer(method, params) }
        val seen = Seen()
        val client = LspClient(server.clientIn, server.clientOut, "file:///p", seen)
        val info = (client.start(5.seconds) as Outcome.Success).value
        assertEquals("fake", info.name)
        return Triple(client, server, seen)
    }

    private fun await(server: FakeServer, method: String, count: Int = 1) {
        val latch = CountDownLatch(1)
        repeat(200) {
            if (server.methods().count { it == method } >= count) return
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        error("служба не получила $method: ${server.methods()}")
    }

    @Test
    fun handshakeReadsCapabilitiesAndAnnouncesUtf16() {
        val (client, server, _) = started()
        val caps = assertNotNull(client.info).capabilities
        assertTrue(caps.incrementalSync && caps.completion && caps.hover && caps.rename)
        assertFalse(caps.definition || caps.references || caps.formatting)
        assertEquals(setOf(".", ">", ":"), caps.completionTriggers)
        await(server, "initialized")
        val init = server.params("initialize")
        assertEquals(json("file:///p"), init["rootUri"])
        assertEquals(parse("""["utf-16"]"""), ((init["capabilities"] as JsonObject)["general"] as JsonObject)["positionEncodings"])
        client.stop()
    }

    @Test
    fun callsBeforeStartAreRefused() {
        val server = FakeServer { _, _ -> JsonNull }
        val client = LspClient(server.clientIn, server.clientOut, "file:///p", Seen())
        assertEquals(DiagCode.STATE_CONFLICT, (client.open(uri, "cpp", "") as Outcome.Failure).diagnostics.first().code)
        assertEquals(DiagCode.STATE_CONFLICT, (client.hover(uri, Position(0, 0)) as Outcome.Failure).diagnostics.first().code)
    }

    @Test
    fun changesGoAsOneRangeWithGrowingVersion() {
        val (client, server, _) = started(sync = 2)
        client.open(uri, "cpp", "int a;\n")
        client.update(uri, "int a;\n") // тот же текст — ничего не уходит
        client.update(uri, "int ab;\n")
        client.update(uri, "int ab;\nint c;\n")
        await(server, "textDocument/didChange", 2)
        assertEquals(3, client.version(uri))
        val open = server.params("textDocument/didOpen")["textDocument"] as JsonObject
        assertEquals(json(1), open["version"])
        assertEquals(json("cpp"), open["languageId"])
        val changes = server.received.filter { it.first == "textDocument/didChange" }.map { it.second as JsonObject }
        assertEquals(2, changes.size)
        assertEquals(parse("""[{"range":{"start":{"line":0,"character":5},"end":{"line":0,"character":5}},"text":"b"}]"""), changes[0]["contentChanges"])
        assertEquals(json(2), (changes[0]["textDocument"] as JsonObject)["version"])
        assertEquals(json(3), (changes[1]["textDocument"] as JsonObject)["version"])
        client.close(uri)
        client.close(uri)
        await(server, "textDocument/didClose")
        assertNull(client.version(uri))
        assertEquals(1, server.methods().count { it == "textDocument/didClose" })
        assertEquals(DiagCode.STATE_CONFLICT, (client.update(uri, "x") as Outcome.Failure).diagnostics.first().code)
        client.stop()
    }

    @Test
    fun fullSyncServerGetsWholeText() {
        val (client, server, _) = started(sync = 1)
        client.open(uri, "cpp", "a")
        client.update(uri, "ab")
        await(server, "textDocument/didChange")
        assertEquals(parse("""[{"text":"ab"}]"""), server.params("textDocument/didChange")["contentChanges"])
        client.stop()
    }

    @Test
    fun diagnosticsReachListenerWithVersion() {
        val (client, server, seen) = started()
        server.notify(
            "textDocument/publishDiagnostics",
            parse("""{"uri":"$uri","version":4,"diagnostics":[{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"severity":2,"code":"unused-variable","source":"clang","message":"unused"},{"range":"битый"},{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"без важности"}]}"""),
        )
        val (gotUri, version, items) = assertNotNull(seen.diagnostics.poll(5, TimeUnit.SECONDS))
        assertEquals(uri, gotUri)
        assertEquals(4, version)
        assertEquals(listOf(LspSeverity.WARNING, LspSeverity.ERROR), items.map { it.severity })
        assertEquals("unused-variable", items[0].code)
        assertEquals(Range(Position(1, 2), Position(1, 5)), items[0].range)
        client.stop()
    }

    @Test
    fun completionHoverRenameAndActionsAreParsed() {
        val edit = """{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":2}},"newText":"size"}"""
        val (client, server, _) = started { method, _ ->
            when (method) {
                "textDocument/completion" -> parse("""{"isIncomplete":true,"items":[{"label":" size()","kind":2,"detail":"size_t","sortText":"0","filterText":"size","textEdit":$edit,"additionalTextEdits":[$edit],"documentation":{"kind":"plaintext","value":"док"}},{"label":"plain","insertText":"plain_text"},{"label":"bare"}]}""")
                "textDocument/hover" -> parse("""{"contents":{"kind":"plaintext","value":"int x"},"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}""")
                "textDocument/signatureHelp" -> parse("""{"signatures":[{"label":"f(int a, int b)","parameters":[{"label":[2,7]},{"label":"int b"},{"label":"нет"}]}],"activeSignature":0,"activeParameter":1}""")
                "textDocument/definition" -> parse("""[{"targetUri":"file:///p/a.h","targetRange":{"start":{"line":0,"character":0},"end":{"line":9,"character":0}},"targetSelectionRange":{"start":{"line":3,"character":4},"end":{"line":3,"character":5}}}]""")
                "textDocument/rename" -> parse("""{"changes":{"$uri":[$edit]},"documentChanges":[{"textDocument":{"uri":"file:///p/a.h","version":null},"edits":[$edit]}]}""")
                "textDocument/formatting" -> parse("[$edit]")
                "textDocument/codeAction" -> parse("""[{"title":"fix","kind":"quickfix","isPreferred":true,"edit":{"changes":{"$uri":[$edit]}}},{"title":"cmd","command":"clangd.applyTweak","arguments":[]}]""")
                else -> JsonNull
            }
        }
        val at = Position(0, 2)
        val list = (client.completion(uri, at, trigger = '.') as Outcome.Success).value
        assertTrue(list.incomplete)
        assertEquals(listOf("size", "plain_text", "bare"), list.items.map { it.insertText })
        assertEquals("док", list.items[0].documentation)
        assertEquals(1, list.items[0].additional.size)
        assertEquals(parse("""{"triggerKind":2,"triggerCharacter":"."}"""), server.params("textDocument/completion")["context"])
        assertEquals("int x", (client.hover(uri, at) as Outcome.Success).value?.text)
        val help = assertNotNull((client.signatureHelp(uri, at) as Outcome.Success).value)
        assertEquals(listOf(2 until 7, 9 until 14, null), help.signatures[0].parameters)
        assertEquals(1, help.activeParameter)
        assertEquals(listOf(Location("file:///p/a.h", Range(Position(3, 4), Position(3, 5)))), (client.definition(uri, at) as Outcome.Success).value)
        val rename = (client.rename(uri, at, "size") as Outcome.Success).value
        assertEquals(setOf(uri, "file:///p/a.h"), rename.changes.keys)
        assertEquals(json("size"), server.params("textDocument/rename")["newName"])
        assertEquals(1, (client.format(uri, 4, spaces = true) as Outcome.Success).value.size)
        assertEquals(parse("""{"tabSize":4,"insertSpaces":true}"""), server.params("textDocument/formatting")["options"])
        val actions = (client.codeActions(uri, Range(at, at), emptyList()) as Outcome.Success).value
        assertEquals(listOf(true, false), actions.map { it.edit != null })
        assertTrue(actions[0].preferred)
        client.stop()
    }

    @Test
    fun nullAnswersMeanNothingToShow() {
        val (client, _, _) = started()
        val at = Position(0, 0)
        assertNull((client.hover(uri, at) as Outcome.Success).value)
        assertEquals(emptyList(), (client.definition(uri, at) as Outcome.Success).value)
        assertEquals(emptyList(), (client.completion(uri, at) as Outcome.Success).value.items)
        assertEquals(emptyList(), (client.format(uri, 2, spaces = false) as Outcome.Success).value)
        client.stop()
    }

    @Test
    fun malformedAnswersAndFileOperationsAreRefused() {
        val (client, _, _) = started { method, _ ->
            when (method) {
                "textDocument/hover" -> json("строка вместо объекта")
                "textDocument/completion" -> parse("""{"items":[{"нет":"label"}]}""")
                "textDocument/rename" -> parse("""{"documentChanges":[{"kind":"rename","oldUri":"file:///a","newUri":"file:///b"}]}""")
                "textDocument/definition" -> parse("""[{"uri":"file:///a","range":{"start":{"line":2,"character":0},"end":{"line":1,"character":0}}}]""")
                else -> JsonNull
            }
        }
        val at = Position(0, 0)
        for (r in listOf(client.hover(uri, at), client.completion(uri, at), client.rename(uri, at, "x"), client.definition(uri, at))) {
            assertEquals(DiagCode.LSP_REPLY_INVALID, (r as Outcome.Failure).diagnostics.first().code)
        }
        assertTrue(client.isRunning, "плохой ответ на запрос не останавливает службу")
        client.stop()
    }

    @Test
    fun serverErrorBecomesRequestFailed() {
        val (client, server, _) = started { method, _ -> if (method == "textDocument/rename") null else JsonNull }
        val t = Thread {
            await(server, "textDocument/rename")
            val id = JsonObject.of("jsonrpc" to json("2.0"), "id" to json(2), "error" to JsonObject.of("code" to json(-32602), "message" to json("Cannot rename symbol")))
            server.send(id)
        }.apply { start() }
        val r = client.rename(uri, Position(0, 0), "x", 5.seconds) as Outcome.Failure
        t.join()
        assertEquals(DiagCode.LSP_REQUEST_FAILED, r.diagnostics.first().code)
        assertEquals("Cannot rename symbol", r.diagnostics.first().message)
        client.stop()
    }

    @Test
    fun silentServerTimesOutAndRequestIsCancelled() {
        val (client, server, _) = started { method, _ -> if (method == "textDocument/hover") null else JsonNull }
        val r = client.hover(uri, Position(0, 0), 100.milliseconds) as Outcome.Failure
        assertEquals(DiagCode.LSP_TIMEOUT, r.diagnostics.first().code)
        await(server, "$/cancelRequest")
        assertTrue(client.isRunning)
        assertEquals(emptyList(), (client.definition(uri, Position(0, 0)) as Outcome.Success).value, "служба жива после тайм-аута")
        client.stop()
    }

    @Test
    fun serverRequestsGetAnswers() {
        val (client, server, _) = started()
        server.send(parse("""{"jsonrpc":"2.0","id":"cfg","method":"workspace/configuration","params":{"items":[{},{}]}}"""))
        server.send(parse("""{"jsonrpc":"2.0","id":7,"method":"workspace/applyEdit","params":{"edit":{}}}"""))
        server.send(parse("""{"jsonrpc":"2.0","id":8,"method":"unknown/thing"}"""))
        // Клиент жив и отвечает дальше: значит, все три запроса обработаны без остановки.
        assertNull((client.hover(uri, Position(0, 0)) as Outcome.Success).value)
        assertTrue(client.isRunning)
        client.stop()
    }

    @Test
    fun deadServerFailsWaitingCallsAndTellsListenerOnce() {
        val (client, server, seen) = started { method, _ -> if (method == "textDocument/references") null else JsonNull }
        val result = LinkedBlockingQueue<Outcome<List<Location>>>()
        val t = Thread { result += client.references(uri, Position(0, 0), timeout = 10.seconds) }.apply { start() }
        await(server, "textDocument/references")
        server.die()
        t.join(5000)
        assertEquals(DiagCode.LSP_SERVER_STOPPED, (assertNotNull(result.poll(5, TimeUnit.SECONDS)) as Outcome.Failure).diagnostics.first().code)
        assertEquals(DiagCode.LSP_SERVER_STOPPED, assertNotNull(seen.stopped.poll(5, TimeUnit.SECONDS)).code)
        assertFalse(client.isRunning)
        assertEquals(DiagCode.LSP_SERVER_STOPPED, (client.hover(uri, Position(0, 0)) as Outcome.Failure).diagnostics.first().code)
        client.stop()
        assertNull(seen.stopped.poll(100, TimeUnit.MILLISECONDS), "остановка сообщается один раз")
    }

    @Test
    fun protocolViolationStopsTheClient() {
        val (client, server, seen) = started()
        server.send(JsonArray(listOf(JsonString("не объект"))))
        assertEquals(DiagCode.LSP_REPLY_INVALID, assertNotNull(seen.stopped.poll(5, TimeUnit.SECONDS)).code)
        assertFalse(client.isRunning)
    }

    @Test
    fun stopSendsShutdownThenExit() {
        val (client, server, _) = started()
        client.stop()
        server.join()
        assertEquals(listOf("shutdown", "exit"), server.methods().takeLast(2))
    }
}
