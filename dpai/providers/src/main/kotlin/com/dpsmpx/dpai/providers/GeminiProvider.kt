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
 * Google Gemini по ключу (D-98; `DPmetaApp:D-52`): `models` и
 * `streamGenerateContent?alt=sse`. Ключ — заголовком `x-goog-api-key`, не
 * параметром адреса: адрес попадает в журналы прокси и серверов.
 * Рассуждения модели (`thought`) в текст ответа не попадают.
 */
class GeminiProvider(profile: ApiProfile, key: Secret?, transport: Transport) : HttpProvider(profile, key, transport) {
    private val headers = mapOf("content-type" to "application/json")

    private fun secret() = key?.let { SecretHeader("x-goog-api-key", "", it) }

    override fun models(cancel: Cancel): Outcome<List<ModelInfo>> {
        keyMissing()?.let { return it }
        val out = mutableListOf<ModelInfo>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val q = "/models?pageSize=1000" + (token?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") }.orEmpty())
            val page = when (val r = json(HttpCall("GET", url(q), headers + ("accept" to "application/json"), null, secret()), cancel)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            val models = page["models"] as? JsonArray ?: return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "в списке моделей нет models", subject)
            for (m in models.items) {
                val o = m.obj() ?: continue
                val name = o["name"].str() ?: continue
                if (o["supportedGenerationMethods"].arr().none { it.str() == "generateContent" }) continue
                out += ModelInfo(name.removePrefix("models/"), o["displayName"].str(), o["outputTokenLimit"].long()?.toInt())
            }
            token = page["nextPageToken"].str()?.takeIf { it.isNotEmpty() } ?: return Outcome.success(out)
        }
        return Outcome.success(out)
    }

    override fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply> {
        keyMissing()?.let { return it }
        val model = request.model.removePrefix("models/")
        if (!MODEL.matches(model)) {
            return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "имя модели Gemini — латиница, цифры и ._-", subject)
        }
        val body = linkedMapOf<String, JsonValue>(
            "contents" to JsonArray(
                request.messages.map { m ->
                    JsonObject.of(
                        "role" to jstr(if (m.role == ChatRole.USER) "user" else "model"),
                        "parts" to JsonArray(listOf(JsonObject.of("text" to jstr(m.text)))),
                    )
                },
            ),
        )
        request.system?.takeIf { it.isNotBlank() }?.let {
            body["systemInstruction"] = JsonObject.of("parts" to JsonArray(listOf(JsonObject.of("text" to jstr(it)))))
        }
        request.maxOutputTokens?.let { body["generationConfig"] = JsonObject.of("maxOutputTokens" to JsonNumber.of(it)) }
        val call = HttpCall(
            "POST", url("/models/$model:streamGenerateContent?alt=sse"), headers + ("accept" to "text/event-stream"),
            CanonicalJson.write(JsonObject(body)).toByteArray(Charsets.UTF_8), secret(),
        )
        val text = StringBuilder()
        var stop: String? = null
        var input: Long? = null
        var output: Long? = null
        val r = stream(call, cancel) { _, o, _ ->
            if (o == null) return@stream true
            if (o["error"] != null) streamError(o)
            o["promptFeedback"].obj()?.get("blockReason").str()?.let { stop = it }
            val cand = o["candidates"].arr().firstOrNull().obj()
            for (p in cand?.get("content").obj()?.get("parts").arr().orEmpty()) {
                val part = p.obj() ?: continue
                if (part["thought"] == JsonBool(true)) continue
                part["text"].str()?.takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    onText(it)
                }
            }
            cand?.get("finishReason").str()?.let { stop = it }
            o["usageMetadata"].obj()?.let { u ->
                u["promptTokenCount"].long()?.let { input = it }
                u["candidatesTokenCount"].long()?.let { output = it }
            }
            true
        }
        if (r is Outcome.Failure) return r
        if (stop == null) return Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "поток оборвался без причины остановки", subject)
        return Outcome.success(ChatReply(text.toString(), stopKind(stop), stop, ChatUsage.of(input, output)))
    }

    companion object {
        private const val MAX_PAGES = 10
        private val MODEL = Regex("[A-Za-z0-9._-]{1,128}")

        fun stopKind(s: String?): StopKind = when (s) {
            "STOP" -> StopKind.END
            "MAX_TOKENS" -> StopKind.LENGTH
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY", "OTHER_BLOCK" -> StopKind.REFUSAL
            else -> StopKind.OTHER
        }
    }
}
