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
class GeminiProvider(profile: ApiProfile, key: Secret?, transport: Transport) : HttpProvider(profile, key, transport), ToolChat {
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

    /** Function calling Gemini сохраняет исходные parts, включая подписи рассуждений (D-214). */
    override fun chatWithTools(request: ChatRequest, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit, run: (ToolCall) -> ToolResult): Outcome<ChatReply> {
        if (tools.isEmpty()) return chat(request, cancel, onText)
        keyMissing()?.let { return it }
        val model = request.model.removePrefix("models/")
        if (!MODEL.matches(model)) return Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "недопустимое имя модели Gemini")
        val messages = request.messages.map { JsonObject.of("role" to jstr(if (it.role == ChatRole.USER) "user" else "model"), "parts" to JsonArray(listOf(JsonObject.of("text" to jstr(it.text))))) }.toMutableList<JsonValue>()
        var serial = 0
        val providerIds = hashMapOf<String, String>()
        return ToolLoop.run(messages, tools, cancel, onText, turn = turn@{ history ->
            val body = linkedMapOf<String, JsonValue>("contents" to JsonArray(history), "tools" to JsonArray(listOf(JsonObject.of("functionDeclarations" to JsonArray(tools.map(ToolLoop::schema))))))
            request.system?.let { body["systemInstruction"] = JsonObject.of("parts" to JsonArray(listOf(JsonObject.of("text" to jstr(it))))) }
            request.maxOutputTokens?.let { body["generationConfig"] = JsonObject.of("maxOutputTokens" to JsonNumber.of(it)) }
            val reply = when (val r = json(HttpCall("POST", url("/models/$model:generateContent"), headers, CanonicalJson.write(JsonObject(body)).toByteArray(Charsets.UTF_8), secret()), cancel)) {
                is Outcome.Failure -> return@turn r
                is Outcome.Success -> r.value
            }
            val candidate = reply["candidates"].arr().singleOrNull().obj()
            val content = candidate?.get("content").obj()
            val reason = candidate?.get("finishReason").str()
            if (content == null || reason == null) return@turn Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "нет завершённого ответа Gemini")
            val parts = content["parts"].arr()
            val calls = arrayListOf<ToolCall>()
            val seenIds = hashSetOf<String>()
            providerIds.clear()
            for (part in parts) {
                val call = part.obj()?.get("functionCall").obj() ?: continue
                val name = call["name"].str()
                val args = call["args"].obj()
                if (name == null || args == null) return@turn Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "неверный вызов функции Gemini")
                val suppliedId = call["id"].str()
                if (suppliedId != null && (suppliedId.isEmpty() || !seenIds.add(suppliedId))) return@turn Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "повторный id функции Gemini")
                val localId = "gemini-${serial++}"
                if (suppliedId != null) providerIds[localId] = suppliedId
                calls += ToolCall(localId, name, args)
            }
            val text = parts.mapNotNull { p -> p.obj()?.takeIf { it["thought"] != JsonBool(true) }?.get("text").str() }.joinToString("")
            val usage = reply["usageMetadata"].obj()
            Outcome.success(ToolLoop.Round(text, stopKind(reason), reason, usage?.get("promptTokenCount").long(), usage?.get("candidatesTokenCount").long(), content, calls, reason == "STOP"))
        }, results = { values -> JsonObject.of("role" to jstr("user"), "parts" to JsonArray(values.map { (call, result) ->
            // id возвращается лишь если он действительно был в functionCall; локальный счётчик не выдаётся за id сервера.
            val fields = linkedMapOf<String, JsonValue>("name" to jstr(call.name), "response" to JsonObject.of("output" to jstr(result.text), "isError" to JsonBool(result.isError)))
            providerIds[call.id]?.let { fields["id"] = jstr(it) }
            JsonObject.of("functionResponse" to JsonObject(fields))
        })) }, execute = run)
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
