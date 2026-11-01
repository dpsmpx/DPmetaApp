package com.dpsmpx.dpmeta.net

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Список адресов, условные запросы и бюджет (ТЗ, §6.4, §6.8; D-10). */
class NetTest {
    /** Сервер на одну строку: ETag "v1", на If-None-Match "v1" — 304. */
    private class FakeServer : Transport {
        val seen = mutableListOf<HttpRequest>()

        override fun send(request: HttpRequest): Outcome<HttpResponse> {
            seen += request
            return if (request.headers["If-None-Match"] == "\"v1\"") {
                Outcome.success(HttpResponse(304, mapOf("ETag" to listOf("\"v1\"")), ByteArray(0)))
            } else {
                Outcome.success(HttpResponse(200, mapOf("ETag" to listOf("\"v1\"")), "тело".toByteArray()))
            }
        }
    }

    private val api = URI("https://api.github.com/repos/x/y")

    @Test
    fun refusesHostsNotInListAndPlainHttpWithSecrets() {
        val server = FakeServer()
        val net = Net(server)
        for (url in listOf("https://example.org/", "http://api.github.com/", "https://user@api.github.com/", "ftp://dpsmpx.com/")) {
            val r = net.get(URI(url))
            assertIs<Outcome.Failure>(r, url)
            assertEquals(DiagCode.HOST_NOT_ALLOWED, r.diagnostics.single().code, url)
        }
        assertIs<Outcome.Success<*>>(net.get(URI("http://dpsmpx.com/")), "сайт по http — исключение на время (A-09)")
        val withSecret = net.get(URI("http://dpsmpx.com/"), auth = Secret("t"))
        assertEquals(DiagCode.HOST_NOT_ALLOWED, (withSecret as Outcome.Failure).diagnostics.single().code)
        assertEquals(1, server.seen.size, "отказанные запросы в сеть не ушли")
        assertEquals(5, net.stats.refused)
    }

    @Test
    fun secondGetIsConditionalAndServedFromCache() {
        val server = FakeServer()
        val net = Net(server)
        val first = (net.get(api) as Outcome.Success).value
        assertFalse(first.notModified)
        val second = (net.get(api) as Outcome.Success).value
        assertTrue(second.notModified)
        assertEquals("тело", second.text)
        assertEquals(first.rawSha256, second.rawSha256)
        assertEquals("\"v1\"", server.seen[1].headers["If-None-Match"])
        assertEquals(NetStats(requests = 2, notModified = 1, refused = 0), net.stats)
    }

    @Test
    fun cacheIsPerToken() {
        val server = FakeServer()
        val cache = MemoryEtagCache()
        val net = Net(server, cache = cache)
        net.get(api, auth = Secret("первый"))
        net.get(api, auth = Secret("второй"))
        assertEquals(null, server.seen[1].headers["If-None-Match"], "ответ одного токена другому не отдаётся")
        assertEquals(2, cache.keys.size)
        assertTrue(cache.keys.none { "первый" in it || "второй" in it }, "секрет не в ключе кэша")
    }

    @Test
    fun budgetStopsRequestsBeforeTheNetwork() {
        val server = FakeServer()
        val net = Net(server, budget = 2)
        net.get(api)
        net.get(api)
        val third = net.get(api)
        assertEquals(DiagCode.RUN_BUDGET_EXHAUSTED, (third as Outcome.Failure).diagnostics.single().code)
        assertEquals(2, server.seen.size)
        assertEquals(0, net.remaining)
    }

    @Test
    fun formBodyEncodes() {
        assertEquals("a=%D1%8F+1&s=x%26y", FormBody.of("a" to "я 1", "s" to Secret("x&y")).toString(Charsets.UTF_8))
    }

    @Test
    fun badHeaderIsRefusedWithoutItsValue() {
        val t = JdkTransport()
        val r = t.send(HttpRequest("GET", URI("https://api.github.com/"), auth = Secret("токен-не-ascii")))
        assertIs<Outcome.Failure>(r)
        val d = r.diagnostics.single()
        assertEquals(DiagCode.VALUE_INVALID, d.code)
        assertFalse("токен" in d.render(), "значение секрета не в сообщении")
        val h = t.send(HttpRequest("GET", URI("https://api.github.com/"), mapOf("X" to "a\nb")))
        assertEquals(DiagCode.VALUE_INVALID, (h as Outcome.Failure).diagnostics.single().code)
    }
}
