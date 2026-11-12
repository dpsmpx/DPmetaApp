package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Настройки служб, диагностика по запросу и распределение запросов между двумя службами (D-142). */
class LanguageSessionTest {
    private val uri = "file:///p/main.py"

    private fun parse(text: String): JsonValue = (JsonParser().parse(text) as Outcome.Success).value

    private object Quiet : LspClient.Listener {
        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
        override fun stopped(reason: Diagnostic) = Unit
    }

    private fun client(caps: String, settings: JsonObject = JsonObject(emptyMap()), answer: (String, JsonValue?) -> JsonValue? = { _, _ -> JsonNull }): Pair<LspClient, FakeServer> {
        val server = FakeServer { method, params -> if (method == "initialize") parse("""{"capabilities":$caps}""") else answer(method, params) }
        val client = LspClient(server.clientIn, server.clientOut, "file:///p", Quiet, settings = settings)
        assertTrue(client.start(5.seconds) is Outcome.Success)
        return client to server
    }

    private fun await(what: String, done: () -> Boolean) {
        val latch = CountDownLatch(1)
        repeat(300) {
            if (done()) return
            latch.await(10, TimeUnit.MILLISECONDS)
        }
        error("не дождались: $what")
    }

    @Test
    fun settingsAnswerConfigurationBySectionAndAreAnnounced() {
        val settings = PythonServers.pylsp("/root/.local/share/dpai/projects/k/python/bin/python").settings
        val (client, server) = client("{}", settings)
        await("didChangeConfiguration") { "workspace/didChangeConfiguration" in server.methods() }
        assertEquals(settings, server.params("workspace/didChangeConfiguration")["settings"])
        val init = server.params("initialize")
        assertEquals(json(true), ((init["capabilities"] as JsonObject)["workspace"] as JsonObject)["configuration"])
        server.send(parse("""{"jsonrpc":"2.0","id":"c1","method":"workspace/configuration","params":{"items":[{"section":"pylsp.plugins.jedi"},{"section":"нет.такого"},{}]}}"""))
        await("ответ на workspace/configuration") { server.responses.any { it["id"] == json("c1") } }
        val result = server.responses.first { it["id"] == json("c1") }["result"] as JsonArray
        assertEquals(json("/root/.local/share/dpai/projects/k/python/bin/python"), (result.items[0] as JsonObject)["environment"])
        assertEquals(JsonNull, result.items[1])
        assertEquals(settings, result.items[2])
        client.stop()
    }

    @Test
    fun withoutSettingsNothingIsAnnouncedAndAnswersAreNull() {
        val (client, server) = client("{}")
        server.send(parse("""{"jsonrpc":"2.0","id":"c2","method":"workspace/configuration","params":{"items":[{"section":"python"}]}}"""))
        await("ответ") { server.responses.any { it["id"] == json("c2") } }
        assertEquals(JsonArray(listOf(JsonNull)), server.responses.first { it["id"] == json("c2") }["result"])
        assertTrue("workspace/didChangeConfiguration" !in server.methods())
        client.stop()
    }

    @Test
    fun pulledDiagnosticsFullAndUnchanged() {
        var unchanged = false
        val (client, _) = client("""{"diagnosticProvider":{"interFileDependencies":true,"workspaceDiagnostics":false}}""") { method, _ ->
            if (method != "textDocument/diagnostic") {
                JsonNull
            } else if (unchanged) {
                parse("""{"kind":"unchanged","resultId":"1"}""")
            } else {
                parse("""{"kind":"full","items":[{"range":{"start":{"line":11,"character":18},"end":{"line":11,"character":22}},"severity":1,"message":"Unresolved reference 'totl'."}]}""")
            }
        }
        assertTrue(client.info!!.capabilities.pullDiagnostics)
        val full = (client.pullDiagnostics(uri) as Outcome.Success).value!!
        assertEquals("Unresolved reference 'totl'.", full.single().message)
        unchanged = true
        assertNull((client.pullDiagnostics(uri) as Outcome.Success).value)
        client.stop()
    }

    @Test
    fun meaningGoesToMainFixesToBothFormatToHelper() {
        val edit = """{"changes":{"$uri":[{"range":{"start":{"line":0,"character":0},"end":{"line":1,"character":0}},"newText":""}]}}"""
        val (main, mainServer) = client("""{"textDocumentSync":2,"completionProvider":{},"codeActionProvider":true,"documentFormattingProvider":true}""") { method, _ ->
            when (method) {
                "textDocument/completion" -> parse("""[{"label":"twice"}]""")
                "textDocument/codeAction" -> parse("""[{"title":"Добавить импорт","kind":"quickfix","edit":$edit}]""")
                else -> JsonNull
            }
        }
        val (helper, helperServer) = client("""{"textDocumentSync":2,"codeActionProvider":true,"documentFormattingProvider":true}""") { method, _ ->
            when (method) {
                "textDocument/codeAction" -> parse("""[{"title":"Ruff (F401): Remove unused import","kind":"quickfix","edit":$edit,"isPreferred":true}]""")
                "textDocument/formatting" -> parse("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"# формат\n"}]""")
                else -> JsonNull
            }
        }
        val session = LanguageSession(main, helper)
        assertTrue(session.open(uri, "python", "import os\n") is Outcome.Success)
        assertEquals(listOf("twice"), (session.completion(uri, Position(0, 0)) as Outcome.Success).value.items.map { it.label })
        assertTrue("textDocument/completion" !in helperServer.methods())
        assertEquals(helper, session.formatter)
        assertEquals("# формат\n", (session.format(uri, 4, true) as Outcome.Success).value.single().newText)
        assertTrue("textDocument/formatting" !in mainServer.methods())
        val fixes = (session.codeActions(uri, Range(Position(0, 0), Position(0, 9)), emptyList()) as Outcome.Success).value
        assertEquals(listOf("Добавить импорт", "Ruff (F401): Remove unused import"), fixes.map { it.title })
        assertEquals("Ruff (F401): Remove unused import", Assist.fixes(fixes).applicable.first().title)
        // Объявления нет у службы — пустой список, а не отказ.
        assertEquals(emptyList(), (session.declaration(uri, Position(0, 0)) as Outcome.Success).value)
        assertTrue(listOf(mainServer, helperServer).all { "textDocument/didOpen" in it.methods() })
        session.stop()
        assertTrue(!session.isRunning)
    }

    @Test
    fun boardKeepsEachServiceListAndShowsTheUnion() {
        val board = DiagnosticsBoard()
        fun d(message: String) = LspDiagnostic(Range(Position(0, 0), Position(0, 1)), LspSeverity.ERROR, null, null, message)
        assertEquals(listOf("типы"), board.put(0, uri, listOf(d("типы"))).map { it.message })
        assertEquals(listOf("типы", "F401"), board.put(1, uri, listOf(d("F401"))).map { it.message })
        // Новый список службы заменяет только её прежний.
        assertEquals(listOf("F401"), board.put(0, uri, emptyList()).map { it.message })
        assertEquals(emptyList(), board.merged("file:///p/other.py"))
        board.clear(uri)
        assertEquals(emptyList(), board.merged(uri))
    }
}
