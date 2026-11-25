package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CallHierarchyTest {
    private val place = JsonObject.of("line" to json(2), "character" to json(4))
    private val range = JsonObject.of("start" to place, "end" to place)
    private val item = JsonObject.of("name" to json("example"), "kind" to json(12), "uri" to json("file:///project/src/a.cpp"),
        "range" to range, "selectionRange" to range, "data" to JsonObject.of("generation" to json(223)))
    private val listener = object : LspClient.Listener {
        override fun diagnostics(uri: String, version: Int?, items: List<LspDiagnostic>) = Unit
        override fun stopped(reason: Diagnostic) = Unit
    }

    @Test fun actualProtocolEchoesOpaqueItemAndReturnsIncomingAndOutgoingLocations() {
        val server = FakeServer { method, _ -> when (method) {
            "initialize" -> JsonObject.of("capabilities" to JsonObject.of("callHierarchyProvider" to json(true)))
            "textDocument/prepareCallHierarchy" -> JsonArray(listOf(item))
            "callHierarchy/incomingCalls" -> JsonArray(listOf(JsonObject.of("from" to item)))
            "callHierarchy/outgoingCalls" -> JsonArray(listOf(JsonObject.of("to" to item)))
            else -> JsonNull
        } }
        val client = LspClient(server.clientIn, server.clientOut, "file:///project", listener)
        try {
            assertTrue(client.start().isSuccess)
            for (incoming in listOf(true, false)) {
                val response = client.calls("file:///project/src/a.cpp", Position(2, 4), incoming).valueOrNull()!!
                assertEquals(listOf(Location("file:///project/src/a.cpp", Range(Position(2, 4), Position(2, 4)))), response)
                val method = if (incoming) "callHierarchy/incomingCalls" else "callHierarchy/outgoingCalls"
                assertEquals(item, server.params(method)["item"])
            }
        } finally { client.stop() }
    }

    @Test fun unsupportedCapabilityDoesNotSendRequests() {
        val server = FakeServer { _, _ -> JsonObject.of("capabilities" to JsonObject.of()) }
        val client = LspClient(server.clientIn, server.clientOut, "file:///project", listener)
        try {
            assertTrue(client.start().isSuccess)
            assertEquals(DiagCode.UNSUPPORTED, client.calls("file:///project/a", Position(0, 0), true).errors.single().code)
            assertFalse("textDocument/prepareCallHierarchy" in server.methods())
        } finally { client.stop() }
    }

    @Test fun cancellationBetweenPrepareAndCallsDoesNotIssueAnotherRequest() {
        var cancelled = false
        val server = FakeServer { method, _ -> when (method) {
            "initialize" -> JsonObject.of("capabilities" to JsonObject.of("callHierarchyProvider" to json(true)))
            "textDocument/prepareCallHierarchy" -> JsonArray(listOf(item)).also { cancelled = true }
            else -> JsonNull
        } }
        val client = LspClient(server.clientIn, server.clientOut, "file:///project", listener)
        try {
            client.start()
            assertEquals(DiagCode.CANCELLED_BY_USER, client.calls("file:///project/a", Position(0, 0), true, cancelled = { cancelled }).errors.single().code)
            assertFalse("callHierarchy/incomingCalls" in server.methods())
        } finally { client.stop() }
    }

    @Test fun malformedLocationIsRefusedInsteadOfAnEmptySemanticResult() {
        val server = FakeServer { method, _ -> when (method) {
            "initialize" -> JsonObject.of("capabilities" to JsonObject.of("callHierarchyProvider" to json(true)))
            "textDocument/prepareCallHierarchy" -> JsonArray(listOf(item))
            "callHierarchy/incomingCalls" -> JsonArray(listOf(JsonObject.of("from" to JsonObject.of("uri" to json("file:///project/a")))))
            else -> JsonNull
        } }
        val client = LspClient(server.clientIn, server.clientOut, "file:///project", listener)
        try { client.start(); assertIs<Outcome.Failure>(client.calls("file:///project/a", Position(0, 0), true)) }
        finally { client.stop() }
    }
}
