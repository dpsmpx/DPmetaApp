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
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.serialization.JsonParser

/**
 * OpenAI-совместимый провайдер (D-98; `DPmetaApp:D-52`): OpenAI,
 * OpenRouter, DeepSeek, Mistral, свой сервер. Адрес API — с версией пути
 * (`…/v1`); запросы — `models` и `chat/completions` со `stream`. Ключ —
 * `Authorization: Bearer`; у своего сервера ключа может не быть.
 *
 * Предел ответа уходит, только если задан в профиле: у OpenAI —
 * `max_completion_tokens` (`max_tokens` новые модели отвергают), у
 * остальных — `max_tokens`.
 */
class OpenAiCompatibleProvider(profile: ApiProfile, key: Secret?, transport: Transport) : HttpProvider(profile, key, transport), ToolChat {
    private val headers = mapOf("content-type" to "application/json")

    private fun secret() = key?.takeIf { !it.isEmpty }?.let { SecretHeader("Authorization", "Bearer ", it) }

    override fun models(cancel: Cancel): Outcome<List<ModelInfo>> {
        val page = when (val r = json(HttpCall("GET", url("/models"), headers + ("accept" to "application/json"), null, secret()), cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val data = page["data"] as? JsonArray ?: return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "в списке моделей нет data", subject)
        return Outcome.success(
            data.items.mapNotNull { m ->
                val o = m.obj() ?: return@mapNotNull null
                val id = o["id"].str() ?: return@mapNotNull null
                ModelInfo(id, o["name"].str())
            }.sortedBy { it.id },
        )
    }

    override fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply> {
        val messages = buildList<JsonValue> {
            request.system?.takeIf { it.isNotBlank() }?.let { add(JsonObject.of("role" to jstr("system"), "content" to jstr(it))) }
            request.messages.forEach { m -> add(JsonObject.of("role" to jstr(if (m.role == ChatRole.USER) "user" else "assistant"), "content" to jstr(m.text))) }
        }
        val body = linkedMapOf<String, JsonValue>("model" to jstr(request.model), "stream" to JsonBool(true), "messages" to JsonArray(messages))
        request.maxOutputTokens?.let { body[if (profile.host == OPENAI_HOST) "max_completion_tokens" else "max_tokens"] = JsonNumber.of(it) }
        val call = HttpCall(
            "POST", url("/chat/completions"), headers + ("accept" to "text/event-stream"),
            CanonicalJson.write(JsonObject(body)).toByteArray(Charsets.UTF_8), secret(),
        )
        val text = StringBuilder()
        var stop: String? = null
        var input: Long? = null
        var output: Long? = null
        var done = false
        val r = stream(call, cancel) { _, o, raw ->
            if (raw.trim() == "[DONE]") {
                done = true
                return@stream false
            }
            if (o == null) return@stream true
            if (o["error"] != null) streamError(o)
            val choice = o["choices"].arr().firstOrNull().obj()
            choice?.get("delta").obj()?.get("content").str()?.takeIf { it.isNotEmpty() }?.let {
                text.append(it)
                onText(it)
            }
            choice?.get("finish_reason").str()?.let { stop = it }
            o["usage"].obj()?.let { u ->
                u["prompt_tokens"].long()?.let { input = it }
                u["completion_tokens"].long()?.let { output = it }
            }
            true
        }
        if (r is Outcome.Failure) return r
        // Не все совместимые серверы шлют [DONE]: конец ответа — и причина остановки.
        if (!done && stop == null) return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "поток оборвался без причины остановки", subject)
        return Outcome.success(ChatReply(text.toString(), stopKind(stop), stop, ChatUsage.of(input, output)))
    }

    /** Проектные инструменты используют тот же транспорт/список адресов, ключ не уходит в процессы (D-214). */
    override fun chatWithTools(request: ChatRequest, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit, run: (ToolCall) -> ToolResult): Outcome<ChatReply> {
        if (tools.isEmpty()) return chat(request, cancel, onText)
        val messages = buildList<JsonValue> {
            request.system?.takeIf { it.isNotBlank() }?.let { add(JsonObject.of("role" to jstr("system"), "content" to jstr(it))) }
            request.messages.forEach { add(JsonObject.of("role" to jstr(if (it.role == ChatRole.USER) "user" else "assistant"), "content" to jstr(it.text))) }
        }.toMutableList()
        // OpenAI требует отдельное сообщение tool на каждый вызов, поэтому они разворачиваются перед HTTP.
        return ToolLoop.run(messages, tools, cancel, onText, turn = turn@{ history ->
            val flattened = history.flatMap { if (it is JsonArray) it.items else listOf(it) }
            val body = linkedMapOf<String, JsonValue>("model" to jstr(request.model), "stream" to JsonBool(false), "messages" to JsonArray(flattened),
                "tools" to JsonArray(tools.map { JsonObject.of("type" to jstr("function"), "function" to ToolLoop.schema(it)) }))
            request.maxOutputTokens?.let { body[if (profile.host == OPENAI_HOST) "max_completion_tokens" else "max_tokens"] = JsonNumber.of(it) }
            val reply = when (val r = json(HttpCall("POST", url("/chat/completions"), headers, CanonicalJson.write(JsonObject(body)).toByteArray(Charsets.UTF_8), secret()), cancel)) {
                is Outcome.Failure -> return@turn r
                is Outcome.Success -> r.value
            }
            val choice = reply["choices"].arr().singleOrNull().obj()
            val message = choice?.get("message").obj()
            val reason = choice?.get("finish_reason").str()
            if (message == null || reason == null) return@turn Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "нет завершённого ответа API")
            val calls = arrayListOf<ToolCall>()
            for (value in message["tool_calls"].arr()) {
                val o = value.obj()
                val f = o?.get("function").obj()
                val id = o?.get("id").str()
                val name = f?.get("name").str()
                val raw = f?.get("arguments").str()
                val args = raw?.takeIf { it.length <= 128 * 1024 }?.let { JsonParser().parse(it).valueOrNull() as? JsonObject }
                if (o?.get("type").str() != "function" || id == null || name == null || args == null) return@turn Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "неверный вызов инструмента API")
                calls += ToolCall(id, name, args)
            }
            val usage = reply["usage"].obj()
            Outcome.success(ToolLoop.Round(message["content"].str().orEmpty(), stopKind(reason), reason, usage?.get("prompt_tokens").long(), usage?.get("completion_tokens").long(), message, calls, reason == "tool_calls"))
        }, results = { values -> JsonArray(values.map { (call, result) -> JsonObject.of("role" to jstr("tool"), "tool_call_id" to jstr(call.id), "content" to jstr(if (result.isError) "ERROR: ${result.text}" else result.text)) }) }, execute = run)
    }

    companion object {
        const val OPENAI_HOST = "api.openai.com"

        fun stopKind(s: String?): StopKind = when (s) {
            "stop", null -> StopKind.END
            "length" -> StopKind.LENGTH
            "content_filter" -> StopKind.REFUSAL
            else -> StopKind.OTHER
        }
    }
}
