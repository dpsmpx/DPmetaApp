package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Ограниченный цикл API-инструментов (D-214); владение проектом остаётся у вызывающего. */
internal object ToolLoop {
    const val MAX_ROUNDS = 16
    const val MAX_CALLS = 64
    const val MAX_RESULT = 32_768
    private val NAME = Regex("[A-Za-z0-9_-]{1,64}")

    data class Round(val text: String, val stop: StopKind, val reason: String?, val input: Long?, val output: Long?,
        val echo: JsonValue, val calls: List<ToolCall>, val useTools: Boolean)

    fun run(messages: MutableList<JsonValue>, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit,
        turn: (List<JsonValue>) -> Outcome<Round>, results: (List<Pair<ToolCall, ToolResult>>) -> JsonValue,
        execute: (ToolCall) -> ToolResult): Outcome<ChatReply> {
        if (tools.size > MAX_CALLS || tools.any { !NAME.matches(it.name) } || tools.map { it.name }.toSet().size != tools.size) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "неверный каталог инструментов")
        }
        val text = StringBuilder()
        var input: Long? = 0
        var output: Long? = 0
        repeat(MAX_ROUNDS + 1) { number ->
            if (cancel.cancelled) return cancelled()
            val round = when (val r = turn(messages)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
            if (cancel.cancelled) return cancelled()
            if (round.text.isNotEmpty()) {
                val piece = (if (text.isNotEmpty()) "\n\n" else "") + round.text
                text.append(piece); onText(piece)
            }
            input = input?.let { a -> round.input?.takeIf { it >= 0 && a <= Long.MAX_VALUE - it }?.let { a + it } }
            output = output?.let { a -> round.output?.takeIf { it >= 0 && a <= Long.MAX_VALUE - it }?.let { a + it } }
            if (!round.useTools || round.calls.isEmpty()) return Outcome.success(ChatReply(text.toString(), round.stop, round.reason, ChatUsage.of(input, output)))
            if (number == MAX_ROUNDS) return Outcome.success(ChatReply(text.toString(), StopKind.OTHER, "tool_round_limit", ChatUsage.of(input, output)))
            // Весь ответ проверяется до первого побочного эффекта; id однозначен внутри этого ответа.
            if (round.calls.size > MAX_CALLS || round.calls.any { it.id.isBlank() || it.id.length > 256 } || round.calls.map { it.id }.toSet().size != round.calls.size) {
                return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "неверные или повторные вызовы инструментов")
            }
            val values = round.calls.map { call ->
                if (cancel.cancelled) return cancelled()
                val result = if (tools.none { it.name == call.name }) ToolResult("нет такого инструмента", true)
                    else runCatching { execute(call) }.getOrElse { ToolResult("сбой инструмента: ${it.javaClass.simpleName}", true) }
                call to result.copy(text = result.text.take(MAX_RESULT))
            }
            messages += round.echo
            messages += results(values)
        }
        return Outcome.failure(DiagCode.INTERNAL_ERROR, "цикл инструментов не завершился")
    }

    private fun cancelled() = Outcome.failure(DiagCode.CANCELLED_BY_USER, "ход API прерван")

    fun schema(tool: ToolSpec) = JsonObject.of("name" to jstr(tool.name), "description" to jstr(tool.description), "parameters" to tool.inputSchema)
}
