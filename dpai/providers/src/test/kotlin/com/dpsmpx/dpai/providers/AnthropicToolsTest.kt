package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ход Anthropic с инструментами (D-118): цикл «модель → инструмент → модель» — на синтетических образцах потока. */
class AnthropicToolsTest {
    private val key = Secret(sampleKey("sk-ant-api03"))
    private val tools = listOf(
        ToolSpec(
            "get_project", "Состояние проекта",
            JsonParser().parse("""{"type":"object","properties":{"project":{"type":"string"}},"required":["project"],"additionalProperties":false}""").valueOrNull() as JsonObject,
        ),
    )
    private val request = ChatRequest("claude-opus-5-5", listOf(ChatMessage(ChatRole.USER, "Что с Alpha?")), system = "Снимок", effort = "high")

    private fun chat(t: Transport, run: (ToolCall) -> ToolResult, onText: (String) -> Unit = {}) =
        (Providers.of(profile(ProviderKind.ANTHROPIC, model = "claude-opus-5-5"), key, t) as ToolChat).chatWithTools(request, tools, Cancel(), onText, run)

    /** Ответ с вызовом инструмента: блок рассуждений с подписью, затем `tool_use` со входом кусками. */
    private fun toolTurn(json: List<String>, stop: String = "tool_use", id: String = "toolu_1", name: String = "get_project") = sse(
        "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":20,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"SIG=="}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":1}""",
        "content_block_start" to """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""",
        *json.map { "content_block_delta" to """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":${JsonString(it).let { s -> com.dpsmpx.dpai.core.serialization.CanonicalJson.write(s) }}}}""" }.toTypedArray(),
        "content_block_stop" to """{"type":"content_block_stop","index":2}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$stop"},"usage":{"output_tokens":30}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private val answer = sse(
        "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":200,"output_tokens":1}}}""",
        "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Alpha в работе."}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private fun body(t: FakeTransport, i: Int) = JsonParser().parse(t.bodyOf(t.calls[i])).valueOrNull() as JsonObject

    @Test
    fun duplicateToolIdsRejectTheWholeBatchBeforeExecution() {
        val turn = sse(
            "message_start" to """{"type":"message_start","message":{"usage":{"input_tokens":1,"output_tokens":1}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"same","name":"get_project","input":{}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"same","name":"get_project","input":{}}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            "message_stop" to """{"type":"message_stop"}""",
        )
        var invoked = 0
        assertIs<Outcome.Failure>(chat(FakeTransport { FakeReply(200, turn) }, { invoked++; ToolResult("не должен исполняться") }))
        assertEquals(0, invoked)
    }

    @Test
    fun toolRoundThenAnswer() {
        var n = 0
        val t = FakeTransport { FakeReply(200, if (n++ == 0) toolTurn(listOf("{\"proj", "ect\":\"Alpha\"}")) else answer) }
        val calls = mutableListOf<ToolCall>()
        val pieces = mutableListOf<String>()
        val reply = chat(t, { c -> calls += c; ToolResult("этап 5, PR 2") }, { pieces += it }).valueOrNull()!!
        assertEquals("Alpha в работе.", reply.text)
        assertEquals(StopKind.END, reply.stop)
        assertEquals(Measure.observed(320L), reply.usage.input, "расход — сумма по запросам хода, с кэшем")
        assertEquals(Measure.observed(42L), reply.usage.output)
        assertEquals("Alpha", (calls.single().input["project"] as JsonString).value)
        assertEquals("toolu_1", calls.single().id)

        val first = body(t, 0)
        val tool = (first["tools"] as JsonArray).items.single() as JsonObject
        assertEquals(JsonBool(true), tool["eager_input_streaming"])
        assertEquals("get_project", (tool["name"] as JsonString).value)
        assertEquals("high", ((first["output_config"] as JsonObject)["effort"] as JsonString).value)
        assertEquals("ephemeral", ((first["cache_control"] as JsonObject)["type"] as JsonString).value)
        assertNull(first["thinking"], "рассуждения — по умолчанию модели: поле не шлём")
        assertNull(first["tool_choice"], "принудительный вызов новые модели отвергают")

        val second = body(t, 1)
        assertEquals(first["system"], second["system"])
        assertEquals(first["tools"], second["tools"], "инструменты и подсказка в ходе не меняются")
        val messages = (second["messages"] as JsonArray).items.map { it as JsonObject }
        assertEquals(listOf("user", "assistant", "user"), messages.map { (it["role"] as JsonString).value })
        val echoed = (messages[1]["content"] as JsonArray).items.map { it as JsonObject }
        assertEquals(listOf("thinking", "tool_use"), echoed.map { (it["type"] as JsonString).value }, "пустой текстовый блок не возвращается")
        assertEquals("SIG==", (echoed[0]["signature"] as JsonString).value, "рассуждения возвращаются с подписью, как пришли")
        assertEquals("Alpha", ((echoed[1]["input"] as JsonObject)["project"] as JsonString).value)
        val result = (messages[2]["content"] as JsonArray).items.single() as JsonObject
        assertEquals(listOf("tool_result", "toolu_1", "этап 5, PR 2"), listOf("type", "tool_use_id", "content").map { (result[it] as JsonString).value })
        assertNull(result["is_error"])
    }

    @Test
    fun badInputIsNotRunAndGoesBackAsError() {
        var n = 0
        val t = FakeTransport { FakeReply(200, if (n++ == 0) toolTurn(listOf("{\"project\": \"Al")) else answer) }
        var ran = 0
        chat(t, { ran++; ToolResult("x") }).valueOrNull()!!
        assertEquals(0, ran, "неразобранный вход — инструмент не исполняется")
        val result = ((body(t, 1)["messages"] as JsonArray).items[2] as JsonObject).let { (it["content"] as JsonArray).items.single() as JsonObject }
        assertEquals(JsonBool(true), result["is_error"])
        assertTrue("INVALID_JSON" in (result["content"] as JsonString).value)
    }

    @Test
    fun unknownToolAndThrowingToolAreErrors() {
        var n = 0
        val t = FakeTransport { FakeReply(200, if (n++ == 0) toolTurn(listOf("{}"), name = "delete_repo") else answer) }
        var ran = 0
        chat(t, { ran++; ToolResult("x") }).valueOrNull()!!
        assertEquals(0, ran, "чего нет в списке — не исполняется")
        var m = 0
        val t2 = FakeTransport { FakeReply(200, if (m++ == 0) toolTurn(listOf("{}")) else answer) }
        chat(t2, { error("сбой") }).valueOrNull()!!
        val result = ((body(t2, 1)["messages"] as JsonArray).items[2] as JsonObject).let { (it["content"] as JsonArray).items.single() as JsonObject }
        assertEquals(JsonBool(true), result["is_error"])
    }

    @Test
    fun cutOrRefusedTurnDoesNotRunTools() {
        for ((stop, kind) in listOf("max_tokens" to StopKind.LENGTH, "refusal" to StopKind.REFUSAL)) {
            val t = FakeTransport { FakeReply(200, toolTurn(listOf("{\"project\":\"Alpha\"}"), stop = stop)) }
            var ran = 0
            val reply = chat(t, { ran++; ToolResult("x") }).valueOrNull()!!
            assertEquals(kind, reply.stop, stop)
            assertEquals(0, ran, "$stop: вызов не исполняется")
            assertEquals(1, t.calls.size)
        }
    }

    @Test
    fun roundsAreLimited() {
        val t = FakeTransport { FakeReply(200, toolTurn(listOf("{\"project\":\"Alpha\"}"))) }
        var ran = 0
        val reply = chat(t, { ran++; ToolResult("x") }).valueOrNull()!!
        assertEquals(AnthropicProvider.MAX_TOOL_ROUNDS, ran)
        assertEquals(AnthropicProvider.MAX_TOOL_ROUNDS + 1, t.calls.size)
        assertEquals(StopKind.OTHER, reply.stop)
        assertEquals(AnthropicProvider.STOP_TOOL_ROUNDS, reply.stopReason)
    }

    @Test
    fun refusalsAndKeyStillApply() {
        val r = chat(FakeTransport { FakeReply(401, """{"type":"error","error":{"type":"authentication_error","message":"нет"}}""") }, { ToolResult("x") })
        assertEquals(DiagCode.PROVIDER_AUTH_FAILED, assertIs<Outcome.Failure>(r).errors.single().code)
        val noKey = (Providers.of(profile(ProviderKind.ANTHROPIC, model = "m"), null, FakeTransport { FakeReply(200, answer) }) as ToolChat)
            .chatWithTools(request, tools, Cancel(), {}) { ToolResult("x") }
        assertEquals(DiagCode.PROVIDER_KEY_MISSING, assertIs<Outcome.Failure>(noKey).errors.single().code)
    }
}
