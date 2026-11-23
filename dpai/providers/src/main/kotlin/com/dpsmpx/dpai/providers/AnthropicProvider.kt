package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.net.URLEncoder

/**
 * Anthropic по ключу Console (D-98, `DPmetaApp:D-44`): Messages API потоком
 * и список моделей. Версия API — [VERSION]: в ней запрос с `temperature`,
 * `top_p`, `top_k` — отказ 400, поэтому их нет. Ключ — заголовком
 * `x-api-key`. Ходы Claude Code этот путь не используют: они — по подписке
 * (D-29).
 */
class AnthropicProvider(profile: ApiProfile, key: Secret?, transport: Transport) : HttpProvider(profile, key, transport), ToolChat {
    private fun headers(stream: Boolean) = buildMap {
        put("anthropic-version", VERSION)
        put("content-type", "application/json")
        put("accept", if (stream) "text/event-stream" else "application/json")
    }

    private fun secret() = key?.let { SecretHeader("x-api-key", "", it) }

    override fun models(cancel: Cancel): Outcome<List<ModelInfo>> {
        keyMissing()?.let { return it }
        val out = mutableListOf<ModelInfo>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val q = "/models?limit=1000" + (after?.let { "&after_id=" + URLEncoder.encode(it, "UTF-8") }.orEmpty())
            val page = when (val r = json(HttpCall("GET", url(q), headers(false), null, secret()), cancel)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val data = page["data"] as? JsonArray ?: return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "в списке моделей нет data", subject)
            for (m in data.items) {
                val o = m.obj() ?: continue
                val id = o["id"].str() ?: continue
                out += ModelInfo(id, o["display_name"].str(), o["max_tokens"].long()?.toInt())
            }
            if (page["has_more"] != JsonBool(true)) return Outcome.success(out)
            after = page["last_id"].str() ?: return Outcome.success(out)
        }
        return Outcome.success(out)
    }

    override fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply> {
        keyMissing()?.let { return it }
        val messages = JsonArray(request.messages.map { m -> message(m.role, jstr(m.text)) })
        return when (val t = turn(request, messages, emptyList(), cancel, onText)) {
            is Outcome.Failure -> t
            is Outcome.Success -> Outcome.success(ChatReply(t.value.text, stopKind(t.value.stop), t.value.stop, ChatUsage.of(t.value.input, t.value.output)))
        }
    }

    /**
     * Ход с инструментами (D-118): пока модель просит данные (`stop_reason: tool_use`),
     * исполняется [run] и итоги уходят обратно одним сообщением. Ответ модели возвращается
     * ей как пришёл — с блоками рассуждений и их подписями: без них следующий запрос
     * отвергается. Вызовы не исполняются, если ответ оборван пределом или отказом. Расход —
     * сумма по запросам хода. Ответ продолжается до завершения моделью или отмены (D-221).
     */
    override fun chatWithTools(request: ChatRequest, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit, run: (ToolCall) -> ToolResult): Outcome<ChatReply> {
        keyMissing()?.let { return it }
        val messages = request.messages.map { m -> message(m.role, jstr(m.text)) }.toMutableList<JsonValue>()
        val text = StringBuilder()
        var input: Long? = null
        var output: Long? = null
        var newRound = false
        while (true) {
            if (cancel.cancelled) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "ответ прерван", subject)
            val t = when (val r = turn(request, JsonArray(messages), tools, cancel) { piece ->
                // Текст следующего запроса хода — с новой строки, а не вплотную к прежнему.
                if (newRound && text.isNotEmpty() && !text.endsWith("\n")) {
                    text.append("\n\n")
                    onText("\n\n")
                }
                newRound = false
                text.append(piece)
                onText(piece)
            }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            newRound = true
            input = sum(input, t.input)
            output = sum(output, t.output)
            fun reply(stop: String?) = Outcome.success(ChatReply(text.toString(), stopKind(stop), stop, ChatUsage.of(input, output)))
            val calls = t.blocks.filter { it.type == "tool_use" }
            if (t.stop != "tool_use" || calls.isEmpty()) return reply(t.stop)
            if (t.blocks.any { it.opaque }) {
                return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "в ответе с инструментами — блок неизвестного вида: продолжить нельзя", subject)
            }
            val callIds = calls.map { it.fields["id"].str().orEmpty() }
            if (calls.size > 64 || callIds.any { it.isEmpty() || it.length > 256 } || callIds.toSet().size != callIds.size) {
                return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "неверные или повторные id инструментов в одном ответе Anthropic", subject)
            }
            val results = calls.map { b ->
                val id = b.fields["id"].str().orEmpty()
                val name = b.fields["name"].str().orEmpty()
                val parsed = b.input
                val result = when {
                    cancel.cancelled -> return Outcome.failure(DiagCode.CANCELLED_BY_USER, "ответ прерван", subject)
                    // Вход не разобран строго — инструмент не исполняется: модель получает свой текст и повторяет.
                    parsed == null -> ToolResult(CanonicalJson.write(JsonObject.of("INVALID_JSON" to jstr(b.rawInput.take(MAX_BAD_INPUT)))), isError = true)
                    tools.none { it.name == name } -> ToolResult("нет такого инструмента: $name", isError = true)
                    else -> runCatching { run(ToolCall(id, name, parsed)) }.getOrElse { e -> ToolResult("сбой инструмента: ${e.javaClass.simpleName}", isError = true) }
                }
                JsonObject(
                    linkedMapOf<String, JsonValue>(
                        "type" to jstr("tool_result"),
                        "tool_use_id" to jstr(id),
                        "content" to jstr(result.text.take(MAX_TOOL_RESULT).ifEmpty { "(пусто)" }),
                    ).also { if (result.isError) it["is_error"] = JsonBool(true) },
                )
            }
            messages += message(ChatRole.ASSISTANT, JsonArray(t.blocks.mapNotNull { it.echo() }))
            messages += message(ChatRole.USER, JsonArray(results))
        }
    }

    private fun message(role: ChatRole, content: JsonValue) =
        JsonObject.of("role" to jstr(if (role == ChatRole.USER) "user" else "assistant"), "content" to content)

    private fun sum(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else a + b

    /** Блок ответа, собранный из потока: каким пришёл в начале и что дописали дельты. */
    private class Block(start: JsonObject) {
        val fields = LinkedHashMap<String, JsonValue>(start.fields)
        val type: String = start["type"].str().orEmpty()
        val text = StringBuilder(start["text"].str().orEmpty())
        val thinking = StringBuilder(start["thinking"].str().orEmpty())
        val json = StringBuilder()

        /** Дельта неизвестного вида: блок нельзя вернуть модели таким, каким она его написала. */
        var opaque = false

        val rawInput: String get() = json.toString()

        /** Вход инструмента — строго разобранный объект; пустой вход — `{}`; не разобран — `null`. */
        val input: JsonObject? get() = if (json.isBlank()) JsonObject.of() else JsonParser().parse(json.toString()).valueOrNull() as? JsonObject

        /** Блок для возврата модели; пустой текстовый блок не возвращается — API его отвергает. */
        fun echo(): JsonObject? {
            when (type) {
                "text" -> if (text.isEmpty()) return null else fields["text"] = jstr(text.toString())
                "thinking" -> fields["thinking"] = jstr(thinking.toString())
                "tool_use" -> fields["input"] = input ?: JsonObject.of()
            }
            return JsonObject(fields)
        }
    }

    private class Turn(val blocks: List<Block>, val text: String, val stop: String?, val input: Long?, val output: Long?)

    /** Один запрос Messages API потоком: блоки ответа, текст, причина остановки и расход. */
    private fun turn(request: ChatRequest, messages: JsonArray, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit): Outcome<Turn> {
        val body = linkedMapOf<String, JsonValue>(
            "model" to jstr(request.model),
            "max_tokens" to JsonNumber.of(request.maxOutputTokens ?: DEFAULT_MAX_TOKENS),
            "stream" to JsonBool(true),
        )
        request.system?.takeIf { it.isNotBlank() }?.let { body["system"] = jstr(it) }
        request.effort?.takeIf { it in EFFORT_LEVELS }?.let { body["output_config"] = JsonObject.of("effort" to jstr(it)) }
        if (tools.isNotEmpty()) {
            body["tools"] = JsonArray(
                tools.map { t ->
                    JsonObject.of(
                        "name" to jstr(t.name),
                        "description" to jstr(t.description),
                        // Вход приходит кусками сразу: разбор и проверка — у нас, строго.
                        "eager_input_streaming" to JsonBool(true),
                        "input_schema" to t.inputSchema,
                    )
                },
            )
            // Повторные запросы хода читают общее начало из кэша провайдера.
            body["cache_control"] = JsonObject.of("type" to jstr("ephemeral"))
        }
        body["messages"] = messages
        val call = HttpCall("POST", url("/messages"), headers(true), CanonicalJson.write(JsonObject(body)).toByteArray(Charsets.UTF_8), secret())
        val blocks = sortedMapOf<Long, Block>()
        val text = StringBuilder()
        var stop: String? = null
        var input: Long? = null
        var output: Long? = null
        var finished = false
        val r = stream(call, cancel) { _, o, _ ->
            when (o?.get("type").str()) {
                "message_start" -> {
                    input = inputTokens(o?.get("message").obj()?.get("usage").obj())
                    true
                }
                "content_block_start" -> {
                    val i = o?.get("index").long()
                    val b = o?.get("content_block").obj()
                    if (i != null && b != null) blocks[i] = Block(b)
                    true
                }
                "content_block_delta" -> {
                    val d = o?.get("delta").obj()
                    val b = o?.get("index").long()?.let { blocks[it] }
                    when (d?.get("type").str()) {
                        "text_delta" -> d?.get("text").str()?.takeIf { it.isNotEmpty() }?.let {
                            b?.text?.append(it)
                            text.append(it)
                            onText(it)
                        }
                        "input_json_delta" -> d?.get("partial_json").str()?.let { b?.json?.append(it) }
                        "thinking_delta" -> d?.get("thinking").str()?.let { b?.thinking?.append(it) }
                        "signature_delta" -> d?.get("signature").str()?.let { b?.fields?.put("signature", jstr(it)) }
                        else -> b?.opaque = true
                    }
                    true
                }
                "message_delta" -> {
                    o?.get("delta").obj()?.get("stop_reason").str()?.let { stop = it }
                    val u = o?.get("usage").obj()
                    u?.get("output_tokens").long()?.let { output = it }
                    inputTokens(u)?.let { input = it }
                    true
                }
                "message_stop" -> {
                    finished = true
                    false
                }
                "error" -> streamError(o ?: JsonObject.of())
                else -> true // ping, конец блока
            }
        }
        if (r is Outcome.Failure) return r
        if (!finished) return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "поток оборвался до message_stop", subject)
        return Outcome.success(Turn(blocks.values.toList(), text.toString(), stop, input, output))
    }

    private fun inputTokens(u: JsonObject?): Long? {
        val o = u ?: return null
        val base = o["input_tokens"].long() ?: return null
        return base + (o["cache_creation_input_tokens"].long() ?: 0) + (o["cache_read_input_tokens"].long() ?: 0)
    }

    companion object {
        /** Версия API Anthropic (заголовок `anthropic-version`). */
        const val VERSION = "2026-10-21"

        /** Предел ответа, если профиль его не задал: у всех нынешних моделей не меньше. */
        const val DEFAULT_MAX_TOKENS = 16_000

        private const val MAX_PAGES = 10

        /** Пределы текста инструмента для модели и возвращаемого ей неразобранного входа. */
        const val MAX_TOOL_RESULT = 60_000
        private const val MAX_BAD_INPUT = 2_000

        fun stopKind(s: String?): StopKind = when (s) {
            "end_turn", "stop_sequence" -> StopKind.END
            "max_tokens" -> StopKind.LENGTH
            "refusal" -> StopKind.REFUSAL
            else -> StopKind.OTHER
        }
    }
}
