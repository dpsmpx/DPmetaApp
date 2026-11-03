package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Транспорт на `HttpURLConnection` против сервера на loopback: заголовки и
 * ключ, поток построчно, `\r\n`, без переадресаций, предел строки, отмена.
 */
class UrlTransportTest {
    private lateinit var server: HttpServer
    private val seen = mutableMapOf<String, String?>()
    private val release = CountDownLatch(1)

    private val base: String get() = "http://127.0.0.1:${server.address.port}"

    private fun reply(x: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> x.responseHeaders.add(k, v) }
        val bytes = body.toByteArray(Charsets.UTF_8)
        x.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) x.responseBody.use { it.write(bytes) } else x.close()
    }

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/models") { x ->
            synchronized(seen) {
                seen["auth"] = x.requestHeaders.getFirst("Authorization")
                seen["query"] = x.requestURI.rawQuery
            }
            reply(x, 200, "{\"data\":[{\"id\":\"local-a\"},\r\n{\"id\":\"local-b\"}]}")
        }
        server.createContext("/v1/chat/completions") { x ->
            synchronized(seen) { seen["body"] = x.requestBody.readBytes().toString(Charsets.UTF_8) }
            x.responseHeaders.add("content-type", "text/event-stream")
            x.sendResponseHeaders(200, 0)
            x.responseBody.use { out ->
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"при\"}}]}\r\n\r\n".toByteArray())
                out.flush()
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"вет\"},\"finish_reason\":\"stop\"}]}\r\n\r\ndata: [DONE]\r\n\r\n".toByteArray())
            }
        }
        server.createContext("/slow/v1/chat/completions") { x ->
            x.sendResponseHeaders(200, 0)
            runCatching {
                x.responseBody.use { out ->
                    out.write("data: {\"choices\":[{\"delta\":{\"content\":\"начал\"}}]}\n\n".toByteArray())
                    out.flush()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }
        server.createContext("/moved/v1/models") { x -> reply(x, 302, "", mapOf("Location" to "https://evil.example/v1/models")) }
        server.createContext("/huge/v1/models") { x -> reply(x, 200, "{\"data\":\"" + "x".repeat(5000) + "\"}") }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @AfterTest
    fun stop() {
        release.countDown()
        server.stop(0)
    }

    private fun provider(path: String = "/v1", key: String? = null, transport: Transport = UrlTransport(5_000, 5_000)) =
        Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "$base$path", model = "local-a", custom = true), key?.let(::Secret), transport)

    @Test
    fun modelsWithKeyOnLoopback() {
        val key = sampleKey("local")
        assertEquals(listOf(ModelInfo("local-a"), ModelInfo("local-b")), provider(key = key).models().valueOrNull())
        assertEquals("Bearer $key", seen["auth"])
        assertNull(seen["query"], "ключ — не в адресе")
    }

    @Test
    fun streamOverCrlf() {
        val pieces = mutableListOf<String>()
        val reply = provider().chat(ChatRequest("local-a", listOf(ChatMessage(ChatRole.USER, "привет"))), Cancel()) { pieces += it }.valueOrNull()!!
        assertEquals(listOf("при", "вет"), pieces)
        assertEquals(StopKind.END, reply.stop)
        assertTrue("\"content\":\"привет\"" in seen["body"]!!)
        assertNull(seen["auth"])
    }

    @Test
    fun redirectNotFollowed() {
        val e = assertIs<Outcome.Failure>(provider("/moved/v1").models()).errors.single()
        assertEquals(DiagCode.PROVIDER_REQUEST_REJECTED, e.code)
    }

    @Test
    fun lineLimit() {
        val r = provider("/huge/v1", transport = UrlTransport(5_000, 5_000, maxLineBytes = 1000)).models()
        assertEquals(DiagCode.PROVIDER_REPLY_INVALID, assertIs<Outcome.Failure>(r).errors.single().code)
    }

    @Test
    fun cancelUnblocksRead() {
        val cancel = Cancel()
        val r = provider("/slow/v1").chat(ChatRequest("local-a", listOf(ChatMessage(ChatRole.USER, "x"))), cancel) { cancel.cancel() }
        assertEquals(DiagCode.CANCELLED_BY_USER, assertIs<Outcome.Failure>(r).errors.single().code)
    }

    @Test
    fun nonAsciiHeaderRefused() {
        val r = UrlTransport().open(HttpCall("GET", URI("$base/v1/models"), mapOf("x-note" to "ключ")), Cancel())
        assertEquals(DiagCode.VALUE_INVALID, assertIs<Outcome.Failure>(r).errors.single().code)
    }
}
