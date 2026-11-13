package com.dpsmpx.dpai.lsp

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.json
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Служба-двойник в потоке теста: отвечает на запросы тем, что вернёт
 * [answer] (`null` — не отвечать вовсе), и записывает всё полученное.
 */
internal class FakeServer(private val answer: (method: String, params: JsonValue?) -> JsonValue?) {
    private val toServer = PipedOutputStream()
    private val serverIn = PipedInputStream(toServer, 1 shl 20)
    private val fromServer = PipedOutputStream()

    /** Потоки клиента. */
    val clientIn = PipedInputStream(fromServer, 1 shl 20)
    val clientOut: java.io.OutputStream = toServer

    val received = CopyOnWriteArrayList<Pair<String, JsonValue?>>()

    /** Ответы клиента на запросы службы (`id` без `method`). */
    val responses = CopyOnWriteArrayList<JsonObject>()
    private val writer = LspWriter(fromServer)

    private val thread = Thread({
        val reader = LspReader(serverIn)
        while (true) {
            val m = (reader.read() as? Outcome.Success)?.value as? JsonObject ?: break
            val method = (m["method"] as? JsonString)?.value ?: run {
                responses += m
                null
            } ?: continue
            received += method to m["params"]
            val id = m["id"] ?: continue
            val result = answer(method, m["params"]) ?: continue
            writer.write(JsonObject.of("jsonrpc" to json("2.0"), "id" to id, "result" to result))
        }
    }, "fake-lsp").apply { isDaemon = true; start() }

    fun send(message: JsonValue) {
        writer.write(message)
    }

    fun notify(method: String, params: JsonValue) = send(JsonObject.of("jsonrpc" to json("2.0"), "method" to json(method), "params" to params))

    /** Служба «упала»: её выход закрыт. */
    fun die() {
        fromServer.close()
    }

    fun methods(): List<String> = received.map { it.first }

    fun params(method: String): JsonObject = received.last { it.first == method }.second as JsonObject

    fun join() = thread.join(2000)

    companion object {
        val NULL: JsonValue = JsonNull
    }
}
