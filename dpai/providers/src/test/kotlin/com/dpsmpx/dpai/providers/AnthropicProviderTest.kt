package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Anthropic Messages API потоком (D-98): запрос, разбор событий, отказы — на синтетических образцах формата. */
class AnthropicProviderTest {
    private val keyText = sampleKey("sk-ant-api03")
    private val key = Secret(keyText)

    private fun provider(t: Transport, max: Int? = null) = Providers.of(profile(ProviderKind.ANTHROPIC, model = "claude-opus-5-5", max = max), key, t)

    private val request = ChatRequest("claude-opus-5-5", listOf(ChatMessage(ChatRole.USER, "Привет"), ChatMessage(ChatRole.ASSISTANT, "Здравствуйте"), ChatMessage(ChatRole.USER, "Как дела?")), system = "Отвечай коротко")

    private val okStream = sse(
        "message_start" to """{"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":10,"cache_read_input_tokens":5,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"скрыто"}}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "ping" to """{"type":"ping"}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Хорошо"}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":", спасибо"}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun chatRequestAndStream() {
        val t = FakeTransport { FakeReply(200, okStream) }
        val pieces = mutableListOf<String>()
        val reply = provider(t).chat(request, Cancel()) { pieces += it }.valueOrNull()!!
        assertEquals(listOf("Хорошо", ", спасибо"), pieces)
        assertEquals("Хорошо, спасибо", reply.text)
        assertEquals(StopKind.END, reply.stop)
        assertEquals(Measure.observed(15L), reply.usage.input)
        assertEquals(Measure.observed(7L), reply.usage.output)

        val call = t.last
        assertEquals("POST", call.method)
        assertEquals("https://api.anthropic.com/v1/messages", call.url.toString())
        assertEquals(AnthropicProvider.VERSION, call.headers["anthropic-version"])
        assertEquals("x-api-key", call.secret!!.name)
        assertEquals(key, call.secret.secret)
        val body = t.bodyOf()
        assertEquals(
            """{"max_tokens":16000,"messages":[{"content":"Привет","role":"user"},{"content":"Здравствуйте","role":"assistant"},{"content":"Как дела?","role":"user"}],"model":"claude-opus-5-5","stream":true,"system":"Отвечай коротко"}""",
            body,
        )
        // Ключ — только в заголовке с секретом: ни в адресе, ни в теле, ни в обычных заголовках.
        assertFalse(keyText in call.url.toString() || keyText in body || call.headers.values.any { keyText in it })
        // Версия 2026-10-21 отвергает выборочные параметры.
        assertFalse("temperature" in body || "top_p" in body || "top_k" in body)
    }

    @Test
    fun maxTokensFromProfile() {
        val t = FakeTransport { FakeReply(200, okStream) }
        provider(t).chat(request.copy(maxOutputTokens = 64000), Cancel()) {}
        assertTrue("\"max_tokens\":64000" in t.bodyOf())
    }

    @Test
    fun refusalAndLength() {
        for ((reason, kind) in listOf("refusal" to StopKind.REFUSAL, "max_tokens" to StopKind.LENGTH, "pause_turn" to StopKind.OTHER)) {
            val s = okStream.replace("\"end_turn\"", "\"$reason\"")
            val reply = provider(FakeTransport { FakeReply(200, s) }).chat(request, Cancel()) {}.valueOrNull()!!
            assertEquals(kind, reply.stop)
            assertEquals(reason, reply.stopReason)
        }
    }

    @Test
    fun usageUnknownIsNotZero() {
        val s = sse(
            "message_start" to """{"type":"message_start","message":{"id":"m"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"x"}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        val reply = provider(FakeTransport { FakeReply(200, s) }).chat(request, Cancel()) {}.valueOrNull()!!
        assertIs<Measure.Unknown>(reply.usage.input)
        assertIs<Measure.Unknown>(reply.usage.output)
    }

    @Test
    fun httpFailuresByStatus() {
        val leaked = sampleKey("sk-ant-api03")
        val cases = listOf(
            401 to DiagCode.PROVIDER_AUTH_FAILED,
            403 to DiagCode.PROVIDER_AUTH_FAILED,
            429 to DiagCode.PROVIDER_RATE_LIMITED,
            529 to DiagCode.PROVIDER_UNAVAILABLE,
            500 to DiagCode.PROVIDER_UNAVAILABLE,
            400 to DiagCode.PROVIDER_REQUEST_REJECTED,
            404 to DiagCode.PROVIDER_REQUEST_REJECTED,
            302 to DiagCode.PROVIDER_REQUEST_REJECTED,
        )
        for ((status, code) in cases) {
            val body = """{"type":"error","error":{"type":"x","message":"плохой ключ $leaked"}}"""
            val r = provider(FakeTransport { FakeReply(status, body) }).chat(request, Cancel()) {}
            val e = assertIs<Outcome.Failure>(r).errors.single()
            assertEquals(code, e.code, "код $status")
            assertFalse(leaked in e.message, "ключ из ответа провайдера не попадает в диагностику")
        }
    }

    @Test
    fun errorEventInStream() {
        val s = sse(
            "message_start" to """{"type":"message_start","message":{"id":"m"}}""",
            "error" to """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )
        val e = assertIs<Outcome.Failure>(provider(FakeTransport { FakeReply(200, s) }).chat(request, Cancel()) {}).errors.single()
        assertEquals(DiagCode.PROVIDER_UNAVAILABLE, e.code)
        assertTrue("Overloaded" in e.message)
    }

    @Test
    fun brokenStreamIsNotSuccess() {
        val cut = okStream.substringBefore("event: message_delta")
        val r = provider(FakeTransport { FakeReply(200, cut) }).chat(request, Cancel()) {}
        assertEquals(DiagCode.PROVIDER_REPLY_INVALID, assertIs<Outcome.Failure>(r).errors.single().code)
        val notJson = "event: content_block_delta\ndata: {не json\n\n"
        assertEquals(DiagCode.PROVIDER_REPLY_INVALID, assertIs<Outcome.Failure>(provider(FakeTransport { FakeReply(200, notJson) }).chat(request, Cancel()) {}).errors.single().code)
        val dropped = FakeTransport { FakeReply(200, okStream) { i, _ -> if (i == 4) throw IOException("обрыв") } }
        assertEquals(DiagCode.NETWORK_UNREACHABLE, assertIs<Outcome.Failure>(provider(dropped).chat(request, Cancel()) {}).errors.single().code)
    }

    @Test
    fun cancelStopsReading() {
        val pieces = mutableListOf<String>()
        val t = FakeTransport { FakeReply(200, okStream) { _, c -> if (pieces.isNotEmpty()) c.cancel() } }
        val r = provider(t).chat(request, Cancel()) { pieces += it }
        assertEquals(DiagCode.CANCELLED_BY_USER, assertIs<Outcome.Failure>(r).errors.single().code)
        assertEquals(listOf("Хорошо"), pieces)
    }

    @Test
    fun keyMissing() {
        val t = FakeTransport { FakeReply(200, okStream) }
        val p = Providers.of(profile(ProviderKind.ANTHROPIC), null, t)
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(p.models()).errors.single().code)
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(p.chat(request, Cancel()) {}).errors.single().code)
        assertTrue(t.calls.isEmpty())
    }

    @Test
    fun modelsWithPages() {
        val t = FakeTransport { call ->
            if ("after_id" in call.url.toString()) {
                FakeReply(200, """{"data":[{"type":"model","id":"claude-haiku-5-5","display_name":"Claude Haiku 5.5"}],"has_more":false,"last_id":"claude-haiku-5-5"}""")
            } else {
                FakeReply(200, """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","max_tokens":128000,"created_at":"2026-01-01T00:00:00Z"}],"has_more":true,"first_id":"a","last_id":"claude-opus-5-5"}""")
            }
        }
        val models = provider(t).models().valueOrNull()!!
        assertEquals(listOf(ModelInfo("claude-opus-5-5", "Claude Opus 5.5", 128000), ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5", null)), models)
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", t.calls[0].url.toString())
        assertEquals("https://api.anthropic.com/v1/models?limit=1000&after_id=claude-opus-5-5", t.calls[1].url.toString())
        assertNull(t.calls[0].body)
    }

    @Test
    fun modelsReplyWithoutData() {
        val r = provider(FakeTransport { FakeReply(200, """{"models":[]}""") }).models()
        assertEquals(DiagCode.PROVIDER_REPLY_INVALID, assertIs<Outcome.Failure>(r).errors.single().code)
    }
}
