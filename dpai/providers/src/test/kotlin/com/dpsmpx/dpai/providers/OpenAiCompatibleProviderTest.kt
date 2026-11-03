package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** OpenAI-совместимый провайдер (D-98): `models` и `chat/completions` потоком; свой сервер — без ключа. */
class OpenAiCompatibleProviderTest {
    private val keyText = sampleKey("sk-proj")
    private val request = ChatRequest("gpt-x", listOf(ChatMessage(ChatRole.USER, "Привет")), system = "Коротко")

    private val stream = sse(
        null to """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
        null to """{"id":"c1","choices":[{"index":0,"delta":{"content":"При"}}]}""",
        null to """{"id":"c1","choices":[{"index":0,"delta":{"content":"вет"},"finish_reason":null}]}""",
        null to """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        null to """{"id":"c1","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}""",
        null to "[DONE]",
    )

    @Test
    fun chatOpenAi() {
        val t = FakeTransport { FakeReply(200, stream) }
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1/"), Secret(keyText), t)
        val pieces = mutableListOf<String>()
        val reply = p.chat(request.copy(maxOutputTokens = 1000), Cancel()) { pieces += it }.valueOrNull()!!
        assertEquals(listOf("При", "вет"), pieces)
        assertEquals("Привет", reply.text)
        assertEquals(StopKind.END, reply.stop)
        assertEquals(Measure.observed(12L), reply.usage.input)
        assertEquals(Measure.observed(3L), reply.usage.output)
        assertEquals("https://api.openai.com/v1/chat/completions", t.last.url.toString())
        assertEquals("Authorization", t.last.secret!!.name)
        assertEquals("Bearer ", t.last.secret!!.prefix)
        assertEquals(
            """{"max_completion_tokens":1000,"messages":[{"content":"Коротко","role":"system"},{"content":"Привет","role":"user"}],"model":"gpt-x","stream":true}""",
            t.bodyOf(),
        )
        assertFalse(keyText in t.bodyOf() || keyText in t.last.url.toString())
    }

    @Test
    fun otherProvidersGetMaxTokens() {
        val t = FakeTransport { FakeReply(200, stream) }
        Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1"), Secret(keyText), t).chat(request.copy(maxOutputTokens = 500), Cancel()) {}
        assertTrue("\"max_tokens\":500" in t.bodyOf())
        assertEquals("https://openrouter.ai/api/v1/chat/completions", t.last.url.toString())
        // Без предела в профиле — поле не уходит: сервер решает сам.
        Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1"), Secret(keyText), t).chat(request, Cancel()) {}
        assertFalse("max_tokens" in t.bodyOf())
    }

    @Test
    fun ownServerWithoutKey() {
        val t = FakeTransport { FakeReply(200, stream.replace("data: [DONE]\n\n", "")) }
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.5:11434/v1", custom = true), null, t)
        val reply = p.chat(request, Cancel()) {}.valueOrNull()!!
        assertEquals("Привет", reply.text)
        assertNull(t.last.secret)
    }

    @Test
    fun streamWithoutEndIsInvalid() {
        val cut = sse(null to """{"choices":[{"index":0,"delta":{"content":"При"}}]}""")
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1"), Secret(keyText), FakeTransport { FakeReply(200, cut) })
        assertEquals(DiagCode.PROVIDER_REPLY_INVALID, assertIs<Outcome.Failure>(p.chat(request, Cancel()) {}).errors.single().code)
    }

    @Test
    fun errorInStreamAndFilter() {
        val err = sse(null to """{"error":{"message":"Rate limit reached","type":"rate_limit_exceeded","code":"rate_limit_exceeded"}}""")
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1"), Secret(keyText), FakeTransport { FakeReply(200, err) })
        assertEquals(DiagCode.PROVIDER_RATE_LIMITED, assertIs<Outcome.Failure>(p.chat(request, Cancel()) {}).errors.single().code)
        val filtered = stream.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"content_filter\"")
        val q = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1"), Secret(keyText), FakeTransport { FakeReply(200, filtered) })
        assertEquals(StopKind.REFUSAL, q.chat(request, Cancel()) {}.valueOrNull()!!.stop)
    }

    @Test
    fun models() {
        val t = FakeTransport { FakeReply(200, """{"object":"list","data":[{"id":"b-model","object":"model","owned_by":"x"},{"id":"a-model","name":"A"},{"object":"model"}]}""") }
        val models = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), Secret(keyText), t).models().valueOrNull()!!
        assertEquals(listOf(ModelInfo("a-model", "A"), ModelInfo("b-model")), models)
        assertEquals("https://api.openai.com/v1/models", t.last.url.toString())
        assertEquals("GET", t.last.method)
    }

    @Test
    fun wrongKey() {
        val t = FakeTransport { FakeReply(401, """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}""") }
        val e = assertIs<Outcome.Failure>(Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), Secret(keyText), t).models()).errors.single()
        assertEquals(DiagCode.PROVIDER_AUTH_FAILED, e.code)
        assertTrue("Incorrect API key provided" in e.message)
    }
}
