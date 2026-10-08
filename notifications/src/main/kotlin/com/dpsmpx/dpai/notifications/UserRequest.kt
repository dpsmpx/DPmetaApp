package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.core.state.TransitionTable

/**
 * Вид запроса человеку (D-44). Вид задаёт только форму — сколько
 * вариантов и каких ролей, нужен ли текст; что делает ответ, решает
 * назначение запроса в оркестраторе, а не вид.
 */
enum class RequestKind {
    /** Вопрос с вариантами ответа (2–6). */
    QUESTION,

    /** Да или нет: вариант `ACCEPT` и вариант `REJECT`. */
    CONFIRMATION,

    /** Опасное действие: вариант `DESTRUCTIVE` и вариант `REJECT`; подтверждается явно (разблокировка, второе нажатие). */
    DESTRUCTIVE_CONFIRMATION,

    /** Нужен текст: ровно один вариант с текстом (отправить), можно и отказ. */
    TEXT_INPUT,

    /** Выбор из заранее заданных вариантов (2–6). */
    CHOICE,

    /** Принять к сведению: один вариант `ACCEPT`. */
    ACKNOWLEDGEMENT,

    /** Автоматика не решила (проверка, сверка, конфликт): варианты решения, любые роли. */
    USER_RESOLUTION,
}

/** Роль варианта: от неё — вид кнопки и требование подтверждения. */
enum class OptionRole { ACCEPT, REJECT, NEUTRAL, DESTRUCTIVE }

/**
 * Вариант ответа. [id] — короткая латиница, ей кнопка ссылается на вариант;
 * [needsText] — ответ без текста не принимается.
 */
data class RequestOption(val id: String, val label: String, val role: OptionRole = OptionRole.NEUTRAL, val needsText: Boolean = false) {
    init {
        require(ID.matches(id)) { "id варианта — [a-z0-9_-]{1,32}" }
        require(label.isNotBlank() && label.length <= MAX_LABEL) { "подпись варианта — 1…$MAX_LABEL знаков" }
    }

    companion object {
        val ID = Regex("[a-z0-9_-]{1,32}")
        const val MAX_LABEL = 40
    }
}

/** Состояние запроса (D-11): `CREATED` → `DELIVERED` → исход; исходы конечны. */
enum class RequestState {
    CREATED, DELIVERED, ANSWERED, EXPIRED, CANCELLED, FAILED;

    val terminal: Boolean get() = this in RequestLifecycle.table.terminal
}

object RequestLifecycle {
    val table: TransitionTable<RequestState> = TransitionTable(
        name = "запрос",
        states = RequestState.entries,
        initial = RequestState.CREATED,
        terminal = setOf(RequestState.ANSWERED, RequestState.EXPIRED, RequestState.CANCELLED, RequestState.FAILED),
        ordinary = mapOf(
            RequestState.CREATED to setOf(RequestState.DELIVERED, RequestState.ANSWERED, RequestState.EXPIRED, RequestState.CANCELLED, RequestState.FAILED),
            RequestState.DELIVERED to setOf(RequestState.ANSWERED, RequestState.EXPIRED, RequestState.CANCELLED, RequestState.FAILED),
        ),
    )
}

/** Принятый ответ: вариант, текст (если нужен), кто и когда; [effect] — что сделал оркестратор. */
data class RequestAnswer(val option: String, val text: String?, val by: String, val at: Timestamp, val effect: String?)

/**
 * Запрос человеку (D-44): устойчивый id, назначение ([purpose] — закрытый
 * список оркестратора), предмет ([subject]: один ждущий запрос на
 * предмет — запросы разных задач не смешиваются), задача и сессия, срок.
 *
 * Неизменная часть — [hash]: кнопка несёт его, и ответ на изменённый или
 * чужой запрос не принимается (как хеш предложения одобрения, ТЗ, §68).
 * Текст — для экрана блокировки: создаётся оркестратором по шаблону и
 * проходит строгий [Redactor] (D-14), текст модели в него не попадает.
 */
data class UserRequest(
    val id: RequestId,
    val kind: RequestKind,
    val purpose: String,
    val subject: String,
    val title: String,
    val body: String,
    val options: List<RequestOption>,
    val task: TaskId?,
    val session: SessionId?,
    val createdAt: Timestamp,
    val expiresAt: Timestamp?,
    val state: RequestState = RequestState.CREATED,
    val deliveries: Int = 0,
    val answer: RequestAnswer? = null,
    val reason: DiagCode? = null,
) {
    val hash: ContentHash get() = Hashing.sha256(immutableJson())

    val terminal: Boolean get() = state.terminal

    fun option(id: String): RequestOption? = options.firstOrNull { it.id == id }

    /** Опасный ли вариант: такой подтверждается явно на любом канале. */
    fun destructive(option: String): Boolean = option(option)?.role == OptionRole.DESTRUCTIVE

    fun expired(now: Timestamp): Boolean = expiresAt != null && now >= expiresAt

    /** Неизменная часть — то, что видел человек: вид, назначение, текст, варианты, привязка, срок. */
    fun immutableJson(): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>(
            "id" to JsonString(id.value), "kind" to JsonString(kind.name), "purpose" to JsonString(purpose),
            "subject" to JsonString(subject), "title" to JsonString(title), "body" to JsonString(body),
            "options" to JsonArray(options.map(::optionJson)),
        ).apply {
            task?.let { put("task", JsonString(it.value)) }
            session?.let { put("session", JsonString(it.value)) }
            put("createdAt", JsonNumber.of(createdAt.epochMillis))
            expiresAt?.let { put("expiresAt", JsonNumber.of(it.epochMillis)) }
        },
    )

    companion object {
        const val MAX_TITLE = 120
        const val MAX_BODY = 1000
        const val MAX_OPTIONS = 6
        const val MAX_TEXT = 2000
        val PURPOSE = Regex("[a-z][a-z0-9_.-]{0,63}")

        internal fun optionJson(o: RequestOption): JsonObject = JsonObject(
            linkedMapOf<String, JsonValue>("id" to JsonString(o.id), "label" to JsonString(o.label), "role" to JsonString(o.role.name)).apply {
                if (o.needsText) put("needsText", JsonBool(true))
            },
        )

        /**
         * Новый запрос с проверкой формы по виду. Текст — через строгий
         * редактор и с пределами длины: он виден на экране блокировки.
         */
        fun create(
            id: RequestId,
            kind: RequestKind,
            purpose: String,
            subject: String,
            title: String,
            body: String,
            options: List<RequestOption>,
            task: TaskId?,
            session: SessionId?,
            createdAt: Timestamp,
            expiresAt: Timestamp?,
        ): Outcome<UserRequest> {
            if (!PURPOSE.matches(purpose)) return Outcome.failure(DiagCode.VALUE_INVALID, "назначение запроса — [a-z][a-z0-9_.-]{0,63}")
            if (subject.isBlank() || subject.length > 200) return Outcome.failure(DiagCode.VALUE_INVALID, "предмет запроса — 1…200 знаков")
            if (title.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "у запроса нет заголовка")
            if (expiresAt != null && expiresAt <= createdAt) return Outcome.failure(DiagCode.VALUE_INVALID, "срок запроса — позже создания")
            shapeError(kind, options)?.let { return Outcome.failure(DiagCode.VALUE_INVALID, "запрос $kind: $it") }
            val r = UserRequest(
                id, kind, purpose, subject,
                clip(Redactor.STRICT.redact(title), MAX_TITLE), clip(Redactor.STRICT.redact(body), MAX_BODY),
                options.map { it.copy(label = clip(Redactor.STRICT.redact(it.label), RequestOption.MAX_LABEL)) },
                task, session, createdAt, expiresAt,
            )
            return Outcome.success(r)
        }

        private fun clip(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"

        /** Форма вариантов по виду; `null` — подходит. */
        fun shapeError(kind: RequestKind, options: List<RequestOption>): String? {
            if (options.isEmpty() || options.size > MAX_OPTIONS) return "вариантов — 1…$MAX_OPTIONS"
            if (options.map { it.id }.toSet().size != options.size) return "id вариантов повторяются"
            val roles = options.map { it.role }
            val texts = options.count { it.needsText }
            return when (kind) {
                RequestKind.QUESTION, RequestKind.CHOICE ->
                    when {
                        options.size < 2 -> "нужно не меньше двух вариантов"
                        OptionRole.DESTRUCTIVE in roles -> "опасный вариант — только в DESTRUCTIVE_CONFIRMATION или USER_RESOLUTION"
                        else -> null
                    }
                RequestKind.CONFIRMATION ->
                    if (options.size == 2 && roles.toSet() == setOf(OptionRole.ACCEPT, OptionRole.REJECT) && texts == 0) null
                    else "ровно два варианта: ACCEPT и REJECT"
                RequestKind.DESTRUCTIVE_CONFIRMATION ->
                    if (options.size == 2 && roles.toSet() == setOf(OptionRole.DESTRUCTIVE, OptionRole.REJECT) && texts == 0) null
                    else "ровно два варианта: DESTRUCTIVE и REJECT"
                RequestKind.TEXT_INPUT ->
                    if (texts == 1 && options.size <= 2 && options.none { !it.needsText && it.role != OptionRole.REJECT }) null
                    else "один вариант с текстом и, может быть, отказ"
                RequestKind.ACKNOWLEDGEMENT ->
                    if (options.size == 1 && roles.single() == OptionRole.ACCEPT && texts == 0) null else "ровно один вариант ACCEPT"
                RequestKind.USER_RESOLUTION -> null
            }
        }
    }
}
