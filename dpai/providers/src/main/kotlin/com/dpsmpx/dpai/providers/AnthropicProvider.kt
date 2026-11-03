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
import java.net.URLEncoder

/**
 * Anthropic по ключу Console (D-98, `DPmetaApp:D-44`): Messages API потоком
 * и список моделей. Версия API — [VERSION]: в ней запрос с `temperature`,
 * `top_p`, `top_k` — отказ 400, поэтому их нет. Ключ — заголовком
 * `x-api-key`. Ходы Claude Code этот путь не используют: они — по подписке
 * (D-29).
 */
class AnthropicProvider(profile: ApiProfile, key: Secret?, transport: Transport) : HttpProvider(profile, key, transport) {
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
        val body = linkedMapOf<String, JsonValue>(
            "model" to jstr(request.model),
            "max_tokens" to JsonNumber.of(request.maxOutputTokens ?: DEFAULT_MAX_TOKENS),
            "stream" to JsonBool(true),
        )
        request.system?.takeIf { it.isNotBlank() }?.let { body["system"] = jstr(it) }
        body["messages"] = JsonArray(
            request.messages.map { m ->
                JsonObject.of("role" to jstr(if (m.role == ChatRole.USER) "user" else "assistant"), "content" to jstr(m.text))
            },
        )
        val call = HttpCall("POST", url("/messages"), headers(true), CanonicalJson.write(JsonObject(body)).toByteArray(Charsets.UTF_8), secret())
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
                "content_block_delta" -> {
                    val d = o?.get("delta").obj()
                    if (d?.get("type").str() == "text_delta") {
                        d?.get("text").str()?.takeIf { it.isNotEmpty() }?.let {
                            text.append(it)
                            onText(it)
                        }
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
                else -> true // ping, начало и конец блоков, рассуждения — не текст ответа
            }
        }
        if (r is Outcome.Failure) return r
        if (!finished) return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "поток оборвался до message_stop", subject)
        return Outcome.success(ChatReply(text.toString(), stopKind(stop), stop, ChatUsage.of(input, output)))
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

        fun stopKind(s: String?): StopKind = when (s) {
            "end_turn", "stop_sequence" -> StopKind.END
            "max_tokens" -> StopKind.LENGTH
            "refusal" -> StopKind.REFUSAL
            else -> StopKind.OTHER
        }
    }
}
