package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Контракты API-инструментов, а не только текст чата: реальный цикл чтения и правки (D-214). */
class ProjectToolsProviderTest {
    private val request = ChatRequest("model", listOf(ChatMessage(ChatRole.USER, "Исправь проект")))
    private val tools = listOf(ToolSpec("read_file", "Прочитать файл", JsonObject.of()), ToolSpec("write_file", "Записать файл", JsonObject.of()))
    private fun openAi(message: String, reason: String, usage: String = "") = """{"choices":[{"message":$message,"finish_reason":"$reason"}]$usage}"""
    private fun call(id: String, name: String, args: String = "{}") = """{"id":"$id","type":"function","function":{"name":"$name","arguments":"$args"}}"""

    @Test fun openAiReadsThenWritesAndEchoesOpaqueAssistantFields() {
        val responses = ArrayDeque(listOf(
            openAi("""{"role":"assistant","reasoning_content":"trace","tool_calls":[${call("a", "read_file") }]}""", "tool_calls", """, "usage":{"prompt_tokens":4,"completion_tokens":2}"""),
            openAi("""{"role":"assistant","tool_calls":[${call("b", "write_file") }]}""", "tool_calls"),
            openAi("""{"role":"assistant","content":"Готово"}""", "stop", """, "usage":{"prompt_tokens":7,"completion_tokens":3}"""),
        ))
        val transport = FakeTransport { FakeReply(200, responses.removeFirst()) }
        val provider = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), Secret(sampleKey("api")), transport) as ToolChat
        val calls = arrayListOf<String>()
        val reply = provider.chatWithTools(request, tools, Cancel(), {}) { calls += it.name; ToolResult("результат") }.valueOrNull()!!
        assertEquals(listOf("read_file", "write_file"), calls)
        assertEquals("Готово", reply.text)
        assertIs<Measure.Unknown>(reply.usage.input, "неизвестный расход промежуточного запроса не превращается в частичную сумму")
        assertTrue(transport.bodyOf().contains("reasoning_content"))
        assertTrue(transport.bodyOf().contains("tool_call_id"))
        assertTrue(transport.calls.all { !transport.bodyOf(it).contains(sampleKey("api")) })
    }

    @Test fun duplicateIdsRefuseWholeBatchBeforeAnyWrite() {
        val t = FakeTransport { FakeReply(200, openAi("""{"role":"assistant","tool_calls":[${call("same", "write_file")},${call("same", "write_file")}]}""", "tool_calls")) }
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), null, t) as ToolChat
        var writes = 0
        assertIs<Outcome.Failure>(p.chatWithTools(request, tools, Cancel(), {}) { writes++; ToolResult("ok") })
        assertEquals(0, writes)
    }

    @Test fun truncatedReplyNeverExecutesToolsAndUnknownToolNeverReachesExecutor() {
        for (reason in listOf("length", "content_filter")) {
            val t = FakeTransport { FakeReply(200, openAi("""{"role":"assistant","tool_calls":[${call("a", "write_file")}]}""", reason)) }
            var writes = 0
            val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), null, t) as ToolChat
            p.chatWithTools(request, tools, Cancel(), {}) { writes++; ToolResult("ok") }
            assertEquals(0, writes)
        }
        var number = 0
        val t = FakeTransport { FakeReply(200, if (number++ == 0) openAi("""{"role":"assistant","tool_calls":[${call("a", "foreign_tool")}]}""", "tool_calls") else openAi("""{"role":"assistant","content":"Нет доступа"}""", "stop")) }
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), null, t) as ToolChat
        var foreignCalls = 0
        p.chatWithTools(request, tools, Cancel(), {}) { foreignCalls++; ToolResult("не должен исполняться") }
        assertEquals(0, foreignCalls)
        assertTrue(t.bodyOf().contains("ERROR"))
    }

    @Test fun geminiPreservesThoughtSignatureAndRealIdIncludingLocalLookingPrefix() {
        var n = 0
        val first = """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"gemini-native","name":"read_file","args":{}},"thoughtSignature":"opaque"}]},"finishReason":"STOP"}]}"""
        val last = """{"candidates":[{"content":{"role":"model","parts":[{"text":"Готово"}]},"finishReason":"STOP"}]}"""
        val t = FakeTransport { FakeReply(200, if (n++ == 0) first else last) }
        val p = Providers.of(profile(ProviderKind.GEMINI), Secret(sampleKey("api")), t) as ToolChat
        val answer = p.chatWithTools(request, tools, Cancel(), {}) { ToolResult("текст") }.valueOrNull()!!
        assertEquals("Готово", answer.text)
        assertTrue(t.bodyOf().contains("thoughtSignature"))
        assertTrue(t.bodyOf().contains("functionResponse"))
        assertTrue(t.bodyOf().contains("gemini-native"))
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/model:generateContent", t.last.url.toString())
    }

    @Test fun cancellationAfterReadPreventsTheNextTool() {
        val t = FakeTransport { FakeReply(200, openAi("""{"role":"assistant","tool_calls":[${call("a", "read_file")},${call("b", "write_file")}]}""", "tool_calls")) }
        val p = Providers.of(profile(ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1"), null, t) as ToolChat
        val cancel = Cancel()
        val calls = arrayListOf<String>()
        assertIs<Outcome.Failure>(p.chatWithTools(request, tools, cancel, {}) { calls += it.name; cancel.cancel(); ToolResult("ok") })
        assertEquals(listOf("read_file"), calls)
    }
}
