package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.result.Outcome

/**
 * Кнопка ответа на запрос (D-44): `dpai://answer/<req_…>/<вариант>?hash=sha256:…`.
 * Ссылка несёт запрос, вариант и хеш неизменной части запроса: ответ
 * действует, только если запрос с этим хешем ещё ждёт. Текст ответа в
 * ссылку не входит — он приходит отдельно (поле ввода уведомления, окно).
 */
data class RequestAction(val request: RequestId, val option: String, val hash: ContentHash) {
    fun toUri(): String = "${DeepLink.SCHEME}://answer/${request.value}/$option?hash=$hash"

    companion object {
        private val URI = Regex("""dpai://answer/([A-Za-z0-9_]+)/([a-z0-9_-]{1,32})\?hash=([a-z0-9]+:[0-9a-f]{64})""")

        fun parse(uri: String): Outcome<RequestAction> {
            val m = URI.matchEntire(uri) ?: return Outcome.failure(DiagCode.VALUE_INVALID, "не ссылка ответа dpai://answer/<id>/<вариант>?hash=…")
            val id = IdKinds.REQUEST.parse(m.groupValues[1]).valueOrNull() ?: return Outcome.failure(DiagCode.ID_INVALID, "не RequestId")
            val hash = ContentHash.parse(m.groupValues[3]).valueOrNull() ?: return Outcome.failure(DiagCode.HASH_INVALID, "хеш запроса неверен")
            return Outcome.success(RequestAction(id, m.groupValues[2], hash))
        }

        /** Кнопки запроса: по варианту на кнопку, с пометками «нужен текст» и «опасно». */
        fun buttons(r: UserRequest): List<NotificationAction> = r.options.map { o ->
            NotificationAction(o.label, RequestAction(r.id, o.id, r.hash).toUri(), input = o.needsText, confirm = o.role == OptionRole.DESTRUCTIVE)
        }
    }
}

/**
 * Запрос → уведомление (D-44, ТЗ, §36, §37). Ключ — предмет запроса: новый
 * запрос по тому же предмету заменяет показанный, а запросы разных задач
 * — разные уведомления. Ждущие человека — категория `APPROVAL`: предел
 * частоты их не держит, канал — высокой важности; «принять к сведению» —
 * `WARNING`.
 */
object RequestNotifications {
    fun key(r: UserRequest): String = "request:${r.subject}"

    fun category(r: UserRequest): NotificationCategory =
        if (r.kind == RequestKind.ACKNOWLEDGEMENT) NotificationCategory.WARNING else NotificationCategory.APPROVAL

    fun link(r: UserRequest): DeepLink =
        r.task?.let { DeepLink.of(DeepLink.Kind.TASK, it) } ?: r.session?.let { DeepLink.of(DeepLink.Kind.SESSION, it) } ?: DeepLink.of(DeepLink.Kind.REQUEST, r.id)

    fun of(r: UserRequest): NotificationRequest = NotificationRequest(
        key = key(r), category = category(r), title = r.title, body = r.body, link = link(r), at = r.createdAt,
        actions = RequestAction.buttons(r), request = r.id,
    )
}
