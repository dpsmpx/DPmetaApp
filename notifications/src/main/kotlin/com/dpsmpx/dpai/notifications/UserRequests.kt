package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Запросы человеку: все, по id; ждущие — по предмету (D-44). */
data class UserRequestsState(val requests: Map<RequestId, UserRequest> = emptyMap()) {
    val pending: List<UserRequest> get() = requests.values.filter { !it.terminal }.sortedBy { it.id.value }

    fun pendingFor(subject: String): UserRequest? = requests.values.firstOrNull { !it.terminal && it.subject == subject }

    companion object {
        val EMPTY = UserRequestsState()
    }
}

/** Факт о запросе — событие журнала (D-12). */
sealed interface RequestEvent {
    val request: RequestId
    val at: Timestamp

    data class Created(val value: UserRequest) : RequestEvent {
        override val request: RequestId get() = value.id
        override val at: Timestamp get() = value.createdAt
    }

    /** Показан каналом ([channel]: `notification`, `overlay`, `app`); повторный показ — тоже факт, без смены исхода. */
    data class Delivered(override val request: RequestId, val channel: String, override val at: Timestamp) : RequestEvent

    data class Answered(override val request: RequestId, val answer: RequestAnswer) : RequestEvent {
        override val at: Timestamp get() = answer.at
    }

    data class Expired(override val request: RequestId, override val at: Timestamp) : RequestEvent

    /** Снят оркестратором: предмет решён иначе (задача пошла дальше, запрос заменён). */
    data class Cancelled(override val request: RequestId, val reason: DiagCode?, override val at: Timestamp) : RequestEvent

    /** Ответ принят, но действие не удалось: ответ записан, запрос закрыт с причиной. */
    data class Failed(override val request: RequestId, val option: String?, val reason: DiagCode, override val at: Timestamp) : RequestEvent
}

/**
 * Проверка ответа: применить действие ([Apply]) или ответ уже принят тем
 * же вариантом — повтор нажатия, второго действия нет ([Repeat]).
 */
sealed interface AnswerCheck {
    data class Apply(val request: UserRequest, val option: RequestOption, val text: String?) : AnswerCheck

    data class Repeat(val request: UserRequest) : AnswerCheck
}

/**
 * Логика запросов (D-44, D-11): чистые функции над [UserRequestsState] —
 * решения детерминированы (D-22), запись в журнал — у оркестратора.
 */
object UserRequests {
    fun apply(state: UserRequestsState, e: RequestEvent): UserRequestsState {
        val r = state.requests[e.request]
        val next: UserRequest? = when (e) {
            is RequestEvent.Created -> e.value
            is RequestEvent.Delivered -> r?.let { it.copy(state = if (it.state == RequestState.CREATED) RequestState.DELIVERED else it.state, deliveries = it.deliveries + 1) }
            is RequestEvent.Answered -> r?.copy(state = RequestState.ANSWERED, answer = e.answer)
            is RequestEvent.Expired -> r?.copy(state = RequestState.EXPIRED)
            is RequestEvent.Cancelled -> r?.copy(state = RequestState.CANCELLED, reason = e.reason)
            is RequestEvent.Failed -> r?.copy(state = RequestState.FAILED, reason = e.reason)
        }
        return if (next == null) state else state.copy(requests = state.requests + (next.id to next))
    }

    fun applyAll(state: UserRequestsState, es: List<RequestEvent>): UserRequestsState = es.fold(state, ::apply)

    /**
     * Создать запрос. По предмету ждёт уже такой же (тот же вид, назначение
     * и варианты) — нового нет: повтор того же вопроса не плодит
     * уведомлений. Ждёт другой — прежний снимается, новый его заменяет:
     * кнопки старого больше ничего не делают.
     */
    fun create(state: UserRequestsState, r: UserRequest): Outcome<List<RequestEvent>> {
        if (state.requests.containsKey(r.id)) return Outcome.failure(DiagCode.VALUE_INVALID, "запрос ${r.id} уже есть")
        val prev = state.pendingFor(r.subject)
        if (prev != null && prev.kind == r.kind && prev.purpose == r.purpose && prev.options == r.options && prev.title == r.title && prev.body == r.body) {
            return Outcome.success(emptyList())
        }
        val replace = prev?.let { listOf(RequestEvent.Cancelled(it.id, DiagCode.REQUEST_NOT_PENDING, r.createdAt)) }.orEmpty()
        return Outcome.success(replace + RequestEvent.Created(r))
    }

    fun delivered(state: UserRequestsState, id: RequestId, channel: String, at: Timestamp): Outcome<List<RequestEvent>> {
        val r = state.requests[id] ?: return notFound(id)
        if (r.terminal) return Outcome.success(emptyList())
        if (!CHANNEL.matches(channel)) return Outcome.failure(DiagCode.VALUE_INVALID, "канал — [a-z]{1,16}")
        return Outcome.success(listOf(RequestEvent.Delivered(id, channel, at)))
    }

    /**
     * Проверить ответ: запрос ждёт, не истёк, хеш кнопки — хеш этого
     * запроса, вариант — его, текст — если вариант его требует. Ответ тем
     * же вариантом на уже отвеченный — [AnswerCheck.Repeat]; любой другой
     * ответ на закрытый — отказ `REQUEST_NOT_PENDING`.
     */
    fun check(state: UserRequestsState, id: RequestId, option: String, text: String?, hash: ContentHash, now: Timestamp): Outcome<AnswerCheck> {
        val r = state.requests[id] ?: return notFound(id)
        if (r.hash != hash) return Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "кнопка относится к другому запросу", "request $id")
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }
        if (r.terminal) {
            val a = r.answer
            return if (r.state == RequestState.ANSWERED && a != null && a.option == option && a.text == clean) {
                Outcome.success(AnswerCheck.Repeat(r))
            } else {
                Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "запрос уже закрыт: ${r.state}", "request $id")
            }
        }
        if (r.expired(now)) return Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "срок запроса истёк", "request $id")
        val o = r.option(option) ?: return Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "у запроса нет варианта «$option»", "request $id")
        if (o.needsText && clean == null) return Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "нужен текст ответа", "request $id")
        if (clean != null && !o.needsText) return Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "вариант «$option» без текста", "request $id")
        if (clean != null && clean.length > UserRequest.MAX_TEXT) return Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "текст длиннее ${UserRequest.MAX_TEXT} знаков", "request $id")
        return Outcome.success(AnswerCheck.Apply(r, o, clean))
    }

    /** Истёкшие ждущие запросы — `EXPIRED`. */
    fun expire(state: UserRequestsState, now: Timestamp): List<RequestEvent> =
        state.pending.filter { it.expired(now) }.map { RequestEvent.Expired(it.id, now) }

    /** Снять ждущие запросы по предмету (предмет решён иначе). */
    fun cancel(state: UserRequestsState, subject: String, reason: DiagCode?, now: Timestamp): List<RequestEvent> =
        state.requests.values.filter { !it.terminal && it.subject == subject }.sortedBy { it.id.value }.map { RequestEvent.Cancelled(it.id, reason, now) }

    private fun notFound(id: RequestId) = Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого запроса", "request $id")

    private val CHANNEL = Regex("[a-z]{1,16}")
}

/**
 * Запросы в JSON (`dpai.user-requests-state` и полезная нагрузка событий
 * `USER_REQUEST_*`): строгий разбор, неизвестное поле — ошибка (D-09).
 */
object UserRequestCodec {
    fun requestJson(r: UserRequest): JsonObject = JsonObject(
        LinkedHashMap(r.immutableJson().fields).apply {
            put("state", JsonString(r.state.name))
            put("deliveries", JsonNumber.of(r.deliveries))
            r.answer?.let { put("answer", answerJson(it)) }
            r.reason?.let { put("reason", JsonString(it.name)) }
        },
    )

    fun answerJson(a: RequestAnswer): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>("option" to JsonString(a.option), "by" to JsonString(a.by), "at" to JsonNumber.of(a.at.epochMillis)).apply {
            a.text?.let { put("text", JsonString(it)) }
            a.effect?.let { put("effect", JsonString(it)) }
        },
    )

    fun readRequest(f: JsonFields, d: DiagnosticCollector): UserRequest? {
        val id = f.string("id")?.let { IdKinds.REQUEST.parse(it).valueOrNull() }
        val kind = f.string("kind")?.let { k -> RequestKind.entries.firstOrNull { it.name == k } }
        val purpose = f.string("purpose")
        val subject = f.string("subject")
        val title = f.string("title")
        val body = f.string("body")
        val options = f.array("options")?.items?.mapIndexed { i, v -> (v as? JsonObject)?.let { readOption(JsonFields(it, "${f.path}.options[$i]", d)) } }
        val task = f.optString("task")?.let { IdKinds.TASK.parse(it).valueOrNull() ?: return null }
        val session = f.optString("session")?.let { IdKinds.SESSION.parse(it).valueOrNull() ?: return null }
        val createdAt = f.long("createdAt")?.let(::Timestamp)
        val expiresAt = f.optLong("expiresAt")?.let(::Timestamp)
        val state = if (f.has("state")) f.string("state")?.let { s -> RequestState.entries.firstOrNull { it.name == s } } else RequestState.CREATED
        val deliveries = if (f.has("deliveries")) f.int("deliveries") else 0
        val answer = f.optObj("answer")?.let { a ->
            val opt = a.string("option")
            val by = a.string("by")
            val at = a.long("at")?.let(::Timestamp)
            val text = a.optString("text")
            val effect = a.optString("effect")
            a.finish()
            if (opt == null || by == null || at == null) return null
            RequestAnswer(opt, text, by, at, effect)
        }
        val reason = f.optString("reason")?.let { r -> DiagCode.entries.firstOrNull { it.name == r } ?: return null }
        if (id == null || kind == null || purpose == null || subject == null || title == null || body == null || options == null ||
            options.any { it == null } || createdAt == null || state == null || deliveries == null
        ) {
            return null
        }
        return UserRequest(id, kind, purpose, subject, title, body, options.filterNotNull(), task, session, createdAt, expiresAt, state, deliveries, answer, reason)
    }

    private fun readOption(f: JsonFields): RequestOption? {
        val id = f.string("id")
        val label = f.string("label")
        val role = f.string("role")?.let { r -> OptionRole.entries.firstOrNull { it.name == r } }
        val needsText = if (f.has("needsText")) f.boolean("needsText") else false
        f.finish()
        if (id == null || label == null || role == null || needsText == null || !RequestOption.ID.matches(id) || label.isBlank() || label.length > RequestOption.MAX_LABEL) return null
        return RequestOption(id, label, role, needsText)
    }

    /** Полезная нагрузка события. */
    fun payload(e: RequestEvent): JsonObject = when (e) {
        is RequestEvent.Created -> e.value.immutableJson()
        is RequestEvent.Delivered -> JsonObject.of("request" to JsonString(e.request.value), "channel" to JsonString(e.channel))
        is RequestEvent.Answered -> JsonObject.of("request" to JsonString(e.request.value), "answer" to answerJson(e.answer))
        is RequestEvent.Expired -> JsonObject.of("request" to JsonString(e.request.value))
        is RequestEvent.Cancelled -> JsonObject(linkedMapOf<String, JsonValue>("request" to JsonString(e.request.value)).apply { e.reason?.let { put("reason", JsonString(it.name)) } })
        is RequestEvent.Failed -> JsonObject(
            linkedMapOf<String, JsonValue>("request" to JsonString(e.request.value), "reason" to JsonString(e.reason.name)).apply { e.option?.let { put("option", JsonString(it)) } },
        )
    }

    /** Разбор полезной нагрузки по типу события; [kind] — имя типа без префикса `USER_REQUEST_`. */
    fun decode(kind: String, at: Timestamp, payload: JsonObject): Outcome<RequestEvent> {
        val d = DiagnosticCollector()
        val f = JsonFields(payload, "$.payload", d)
        val id: () -> RequestId? = { f.string("request")?.let { IdKinds.REQUEST.parse(it).valueOrNull() } }
        val code: (String?) -> DiagCode? = { s -> s?.let { n -> DiagCode.entries.firstOrNull { it.name == n } } }
        val e: RequestEvent? = when (kind) {
            "CREATED" -> readRequest(f, d)?.let { RequestEvent.Created(it.copy(state = RequestState.CREATED, deliveries = 0, answer = null, reason = null)) }
            "DELIVERED" -> id()?.let { r -> f.string("channel")?.let { RequestEvent.Delivered(r, it, at) } }
            "ANSWERED" -> id()?.let { r ->
                f.obj("answer")?.let { a ->
                    val opt = a.string("option")
                    val by = a.string("by")
                    val aat = a.long("at")?.let(::Timestamp)
                    val text = a.optString("text")
                    val effect = a.optString("effect")
                    a.finish()
                    if (opt != null && by != null && aat != null) RequestEvent.Answered(r, RequestAnswer(opt, text, by, aat, effect)) else null
                }
            }
            "EXPIRED" -> id()?.let { RequestEvent.Expired(it, at) }
            "CANCELLED" -> id()?.let { r -> RequestEvent.Cancelled(r, code(f.optString("reason")), at) }
            "FAILED" -> id()?.let { r ->
                val option = f.optString("option")
                code(f.string("reason"))?.let { c -> RequestEvent.Failed(r, option, c, at) }
            }
            else -> null
        }
        f.finish()
        return if (d.hasErrors || e == null) {
            Outcome.Failure(d.all.ifEmpty { listOf(Diagnostic.error(DiagCode.FIELD_MISSING, "событие запроса неполно", "$.payload")) })
        } else {
            Outcome.success(e)
        }
    }

    fun stateJson(s: UserRequestsState): JsonArray = JsonArray(s.requests.values.sortedBy { it.id.value }.map(::requestJson))
}
