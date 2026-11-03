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
import kotlin.test.assertTrue

/** Gemini по ключу (D-98): ключ — заголовком, не в адресе; рассуждения — не в тексте ответа. */
class GeminiProviderTest {
    private val keyText = sampleKey("AIza")
    private val key = Secret(keyText)
    private fun provider(t: Transport) = Providers.of(profile(ProviderKind.GEMINI), key, t)

    private val request = ChatRequest(
        "gemini-x-flash",
        listOf(ChatMessage(ChatRole.USER, "Привет"), ChatMessage(ChatRole.ASSISTANT, "Здравствуйте"), ChatMessage(ChatRole.USER, "Ещё")),
        system = "Коротко",
        maxOutputTokens = 256,
    )

    private val stream = sse(
        null to """{"candidates":[{"content":{"parts":[{"text":"думаю","thought":true}],"role":"model"}}]}""",
        null to """{"candidates":[{"content":{"parts":[{"text":"При"}],"role":"model"}}]}""",
        null to """{"candidates":[{"content":{"parts":[{"text":"вет"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":2,"totalTokenCount":11}}""",
    )

    @Test
    fun chat() {
        val t = FakeTransport { FakeReply(200, stream) }
        val reply = provider(t).chat(request, Cancel()) {}.valueOrNull()!!
        assertEquals("Привет", reply.text)
        assertEquals(StopKind.END, reply.stop)
        assertEquals(Measure.observed(9L), reply.usage.input)
        assertEquals(Measure.observed(2L), reply.usage.output)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-x-flash:streamGenerateContent?alt=sse", t.last.url.toString())
        assertEquals("x-goog-api-key", t.last.secret!!.name)
        assertFalse(keyText in t.last.url.toString() || keyText in t.bodyOf())
        assertEquals(
            """{"contents":[{"parts":[{"text":"Привет"}],"role":"user"},{"parts":[{"text":"Здравствуйте"}],"role":"model"},{"parts":[{"text":"Ещё"}],"role":"user"}],""" +
                """"generationConfig":{"maxOutputTokens":256},"systemInstruction":{"parts":[{"text":"Коротко"}]}}""",
            t.bodyOf(),
        )
    }

    @Test
    fun safetyAndBlockedPrompt() {
        val safety = stream.replace("\"STOP\"", "\"SAFETY\"")
        assertEquals(StopKind.REFUSAL, provider(FakeTransport { FakeReply(200, safety) }).chat(request, Cancel()) {}.valueOrNull()!!.stop)
        val blocked = sse(null to """{"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"},"usageMetadata":{"promptTokenCount":4}}""")
        val r = provider(FakeTransport { FakeReply(200, blocked) }).chat(request, Cancel()) {}.valueOrNull()!!
        assertEquals(StopKind.REFUSAL, r.stop)
        assertEquals("", r.text)
    }

    @Test
    fun badModelNameNeverSent() {
        val t = FakeTransport { FakeReply(200, stream) }
        for (m in listOf("a/b", "x:generateContent", "")) {
            val r = provider(t).chat(request.copy(model = m), Cancel()) {}
            assertEquals(DiagCode.PROVIDER_REQUEST_REJECTED, assertIs<Outcome.Failure>(r).errors.single().code)
        }
        assertTrue(t.calls.isEmpty())
        provider(t).chat(request.copy(model = "models/gemini-x-flash"), Cancel()) {}
        assertTrue(t.last.url.toString().contains("/models/gemini-x-flash:streamGenerateContent"))
    }

    @Test
    fun modelsOnlyForGeneration() {
        val t = FakeTransport { call ->
            if ("pageToken" in call.url.toString()) {
                FakeReply(200, """{"models":[{"name":"models/gemini-b","supportedGenerationMethods":["generateContent","countTokens"]}]}""")
            } else {
                FakeReply(
                    200,
                    """
                    {
                      "models": [
                        {"name": "models/gemini-a", "displayName": "Gemini A", "outputTokenLimit": 65536, "supportedGenerationMethods": ["generateContent"]},
                        {"name": "models/embedding-x", "supportedGenerationMethods": ["embedContent"]}
                      ],
                      "nextPageToken": "p2"
                    }
                    """.trimIndent(),
                )
            }
        }
        val models = provider(t).models().valueOrNull()!!
        assertEquals(listOf(ModelInfo("gemini-a", "Gemini A", 65536), ModelInfo("gemini-b")), models)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", t.calls[0].url.toString())
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&pageToken=p2", t.calls[1].url.toString())
    }

    @Test
    fun errorStatus() {
        val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}"""
        val e = assertIs<Outcome.Failure>(provider(FakeTransport { FakeReply(400, body) }).models()).errors.single()
        assertEquals(DiagCode.PROVIDER_REQUEST_REJECTED, e.code)
        assertTrue("API key not valid" in e.message)
        val inStream = sse(null to """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}""")
        assertEquals(DiagCode.PROVIDER_RATE_LIMITED, assertIs<Outcome.Failure>(provider(FakeTransport { FakeReply(200, inStream) }).chat(request, Cancel()) {}).errors.single().code)
    }
}
