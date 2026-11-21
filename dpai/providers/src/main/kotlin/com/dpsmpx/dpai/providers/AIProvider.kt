package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.accounts.ApiProfile
import com.dpsmpx.dpai.accounts.ProviderKind
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.diagnostics.Secret
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.io.IOException
import java.net.URI

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(val role: ChatRole, val text: String)

/**
 * Запрос чата: модель, переписка (первая реплика — человека), системная подсказка, предел
 * ответа. [effort] — глубина работы модели (`low`…`max`); `null` — по умолчанию модели.
 * Понимает его только Anthropic; остальные провайдеры поле не шлют.
 */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val system: String? = null,
    val maxOutputTokens: Int? = null,
    val effort: String? = null,
)

/** Инструмент для модели (D-118): имя, когда его звать, схема входа (JSON Schema). */
data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)

/** Вызов инструмента моделью: [input] — разобранный строго JSON-объект. */
data class ToolCall(val id: String, val name: String, val input: JsonObject)

/** Итог инструмента — текст для модели; [isError] — инструмент не выполнен, текст — причина. */
data class ToolResult(val text: String, val isError: Boolean = false)

/**
 * Провайдер, умеющий ходить с инструментами (D-118): модель просит данные, приложение
 * исполняет [run] и возвращает итог, пока модель не ответит. Полномочия инструментов
 * определяет вызывающий (D-214); провайдер сам не открывает файлы и не запускает процессы.
 */
interface ToolChat {
    fun chatWithTools(request: ChatRequest, tools: List<ToolSpec>, cancel: Cancel, onText: (String) -> Unit, run: (ToolCall) -> ToolResult): Outcome<ChatReply>
}

/** Уровни effort Anthropic (`output_config.effort`): от быстрого к самому тщательному. */
val EFFORT_LEVELS: List<String> = listOf("low", "medium", "high", "xhigh", "max")

/** Модель провайдера: id для запроса, имя для экрана, предел ответа — если провайдер его сообщает. */
data class ModelInfo(val id: String, val name: String? = null, val maxOutputTokens: Int? = null, val supportsTools: Boolean? = null)

/** Чем кончился ответ: сам, по пределу длины, отказом модели или иначе (сырое значение — в [ChatReply.stopReason]). */
enum class StopKind { END, LENGTH, REFUSAL, OTHER }

/** Расход хода в токенах из ответа провайдера; не сообщил — `Measure.Unknown`, не ноль (D-13). */
data class ChatUsage(val input: Measure<Long>, val output: Measure<Long>) {
    companion object {
        fun of(input: Long?, output: Long?): ChatUsage = ChatUsage(measure(input), measure(output))

        private fun measure(v: Long?): Measure<Long> = if (v == null) Measure.unknown("провайдер не сообщил расход") else Measure.observed(v)
    }
}

data class ChatReply(val text: String, val stop: StopKind, val stopReason: String?, val usage: ChatUsage)

/**
 * Провайдер модели (ТЗ, §52; D-98): список моделей — он же проверка ключа
 * — и ответ потоком. Вызовы блокирующие — с фонового потока; [Cancel]
 * прерывает чтение. Ответ, оборванный отменой или сетью, — отказ
 * (`CANCELLED_BY_USER`, `NETWORK_UNREACHABLE`), а пришедшие куски уже
 * отданы в `onText`.
 */
interface AIProvider {
    fun models(cancel: Cancel = Cancel()): Outcome<List<ModelInfo>>

    fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply>
}

object Providers {
    /** Провайдер профиля; [key] — `null`, если ключа нет (свой сервер без ключа — можно, Anthropic и Gemini — нет). */
    fun of(profile: ApiProfile, key: Secret?, transport: Transport = UrlTransport()): AIProvider = when (profile.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(profile, key, transport)
        ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(profile, key, transport)
        ProviderKind.GEMINI -> GeminiProvider(profile, key, transport)
    }

    /** Нужен ли провайдеру ключ: свой OpenAI-совместимый сервер (Ollama, llama.cpp) работает и без него. */
    fun keyRequired(kind: ProviderKind): Boolean = kind != ProviderKind.OPENAI_COMPATIBLE
}

/**
 * Общее у провайдеров по HTTP: список адресов до транспорта, разбор отказа
 * по коду ответа, JSON-ответ целиком и поток SSE. Ответ провайдера
 * читается терпимо к новым полям — формат чужой и растёт; нужного поля
 * нет — `PROVIDER_REPLY_INVALID`, а не догадка (D-87).
 */
abstract class HttpProvider(
    protected val profile: ApiProfile,
    protected val key: Secret?,
    private val transport: Transport,
) : AIProvider {
    private val custom: Set<String> = if (profile.customHostAllowed) setOf(profile.host) else emptySet()

    protected val subject: String get() = profile.kind.key

    protected fun url(path: String): URI = URI(profile.baseUrl + path)

    protected fun keyMissing(): Outcome.Failure? =
        if (Providers.keyRequired(profile.kind) && (key == null || key.isEmpty)) {
            Outcome.failure(DiagCode.PROVIDER_KEY_MISSING, "у профиля «${profile.name}» нет ключа", subject)
        } else {
            null
        }

    protected fun open(call: HttpCall, cancel: Cancel): Outcome<HttpReply> {
        ProviderHosts.check(call, custom).let { if (it is Outcome.Failure) return it }
        return transport.open(call, cancel)
    }

    /** JSON-объект ответа 200; иной код — отказ по коду. */
    protected fun json(call: HttpCall, cancel: Cancel): Outcome<JsonObject> {
        val reply = when (val r = open(call, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return reply.use {
            try {
                if (it.status != 200) return refusal(it)
                val text = it.readText(MAX_JSON_CHARS)
                when (val p = JsonParser().parse(text)) {
                    is Outcome.Failure -> Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "ответ — не JSON", subject)
                    is Outcome.Success -> (p.value as? JsonObject)?.let { o -> Outcome.success(o) }
                        ?: Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "ответ — не объект JSON", subject)
                }
            } catch (e: IOException) {
                broken(e, cancel)
            }
        }
    }

    /**
     * Поток SSE ответа 200: [onEvent] получает разобранные данные события и
     * имя события; возвращает `false` — конец, [StreamError] — отказ.
     */
    protected fun stream(call: HttpCall, cancel: Cancel, onEvent: (String?, JsonObject?, String) -> Boolean): Outcome<Unit> {
        val reply = when (val r = open(call, cancel)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        return reply.use {
            try {
                if (it.status != 200) return refusal(it)
                var bad: Outcome.Failure? = null
                Sse.read(it::readLine) { e ->
                    if (cancel.cancelled) return@read false
                    val obj = if (e.data.startsWith("{")) (JsonParser().parse(e.data).valueOrNull() as? JsonObject) else null
                    if (e.data.startsWith("{") && obj == null) {
                        bad = Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "событие потока — не JSON", subject)
                        return@read false
                    }
                    try {
                        onEvent(e.event, obj, e.data)
                    } catch (s: StreamError) {
                        bad = s.failure
                        false
                    }
                }
                when {
                    cancel.cancelled -> Outcome.failure(DiagCode.CANCELLED_BY_USER, "ответ прерван", subject)
                    else -> bad ?: Outcome.success(Unit)
                }
            } catch (e: IOException) {
                broken(e, cancel)
            }
        }
    }

    /** Отказ внутри потока: ошибка провайдера событием. */
    protected class StreamError(val failure: Outcome.Failure) : RuntimeException(null, null, false, false)

    /** Отказ по коду ответа: код диагностики — по классу, текст — сообщение провайдера через [Redactor]. */
    protected fun refusal(reply: HttpReply): Outcome.Failure {
        val status = reply.status
        val detail = try {
            errorMessage(JsonParser().parse(reply.readText(MAX_ERROR_CHARS)).valueOrNull())
        } catch (_: IOException) {
            null
        }
        val tail = detail?.let { ": $it" }.orEmpty()
        return when (status) {
            401, 403 -> Outcome.failure(DiagCode.PROVIDER_AUTH_FAILED, "ключ не принят ($status)$tail", subject)
            429 -> Outcome.failure(DiagCode.PROVIDER_RATE_LIMITED, "предел запросов ($status)$tail", subject)
            in 300..399 -> Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "переадресация ($status) не выполняется: проверьте адрес API", subject)
            in 500..599 -> Outcome.failure(DiagCode.PROVIDER_UNAVAILABLE, "провайдер недоступен ($status)$tail", subject)
            else -> Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "запрос отвергнут ($status)$tail", subject)
        }
    }

    /** Ошибка событием потока (`{"error": {...}}`): тип — по полю `type`/`status` провайдера. */
    protected fun streamError(obj: JsonObject): Nothing {
        val err = obj["error"] as? JsonObject
        val type = ((err?.get("type") ?: err?.get("status")) as? JsonString)?.value.orEmpty().lowercase()
        val msg = errorMessage(obj)?.let { ": $it" }.orEmpty()
        val f = when {
            "rate_limit" in type || "resource_exhausted" in type -> Outcome.failure(DiagCode.PROVIDER_RATE_LIMITED, "предел запросов$msg", subject)
            "auth" in type || "permission" in type -> Outcome.failure(DiagCode.PROVIDER_AUTH_FAILED, "ключ не принят$msg", subject)
            "invalid_request" in type || "not_found" in type || "invalid_argument" in type ->
                Outcome.failure(DiagCode.PROVIDER_REQUEST_REJECTED, "запрос отвергнут$msg", subject)
            else -> Outcome.failure(DiagCode.PROVIDER_UNAVAILABLE, "ошибка провайдера в потоке$msg", subject)
        }
        throw StreamError(f)
    }

    private fun broken(e: IOException, cancel: Cancel): Outcome.Failure = when {
        cancel.cancelled -> Outcome.failure(DiagCode.CANCELLED_BY_USER, "ответ прерван", subject)
        e is ReplyTooLarge -> Outcome.failure(DiagCode.PROVIDER_REPLY_INVALID, "ответ длиннее предела", subject)
        else -> Outcome.failure(DiagCode.NETWORK_UNREACHABLE, "соединение оборвалось: ${e.javaClass.simpleName}", subject)
    }

    companion object {
        const val MAX_JSON_CHARS = 8 * 1024 * 1024
        const val MAX_ERROR_CHARS = 64 * 1024
        const val MAX_DETAIL = 300

        /** `error.message` (Anthropic, OpenAI, Gemini) или `message`; через [Redactor], не длиннее [MAX_DETAIL]. */
        fun errorMessage(v: JsonValue?): String? {
            val o = v as? JsonObject ?: return null
            val m = ((o["error"] as? JsonObject)?.get("message") ?: o["message"]) as? JsonString ?: return null
            val text = Redactor.STANDARD.redact(m.value.replace(Regex("\\s+"), " ").trim())
            return if (text.length > MAX_DETAIL) text.take(MAX_DETAIL) + "…" else text.ifEmpty { null }
        }
    }
}

// Чтение чужого JSON без строгости к лишним полям.
internal fun JsonValue?.obj(): JsonObject? = this as? JsonObject
internal fun JsonValue?.arr(): List<JsonValue> = (this as? JsonArray)?.items.orEmpty()
internal fun JsonValue?.str(): String? = (this as? JsonString)?.value
internal fun JsonValue?.long(): Long? = (this as? JsonNumber)?.toLongExact()

/** Строка в JSON запроса. */
internal fun jstr(s: String): JsonString = JsonString(s)

/**
 * Провайдер для тестов (D-98, как `MockRunner` у исполнителей): модели и
 * ответ кусками без сети.
 */
class MockProvider(
    private val modelList: List<ModelInfo> = listOf(ModelInfo("mock-model", "Mock")),
    private val chunks: (ChatRequest) -> List<String> = { listOf("ok") },
) : AIProvider {
    val requests: MutableList<ChatRequest> = mutableListOf()

    override fun models(cancel: Cancel): Outcome<List<ModelInfo>> = Outcome.success(modelList)

    override fun chat(request: ChatRequest, cancel: Cancel, onText: (String) -> Unit): Outcome<ChatReply> {
        synchronized(requests) { requests += request }
        val sb = StringBuilder()
        for (c in chunks(request)) {
            if (cancel.cancelled) return Outcome.failure(DiagCode.CANCELLED_BY_USER, "ответ прерван", "mock")
            sb.append(c)
            onText(c)
        }
        return Outcome.success(ChatReply(sb.toString(), StopKind.END, "end", ChatUsage.of(null, null)))
    }
}
