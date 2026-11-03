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
class OpenAiCompatibleProvider(profile: ApiProfile, key: Secret?, transport: Transport) : HttpProvider(profile, key, transport) {
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
