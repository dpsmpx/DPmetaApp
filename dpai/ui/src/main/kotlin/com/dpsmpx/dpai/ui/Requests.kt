package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.notifications.OptionRole
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.notifications.UserRequest
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.time.Duration.Companion.milliseconds

/**
 * Кнопка ответа: подпись, ссылка ответа, нужен ли текст и явное
 * подтверждение (опасный вариант, D-44).
 */
data class AnswerButton(val label: String, val uri: String, val needsText: Boolean, val confirm: Boolean, val tone: Tone)

/** Карточка ждущего запроса: что, о какой задаче, сколько осталось, варианты. */
data class RequestCard(
    val id: RequestId,
    val kind: String,
    val title: String,
    val body: String,
    val origin: String,
    val task: TaskId?,
    val session: SessionId?,
    val left: String,
    val buttons: List<AnswerButton>,
)

/**
 * Запросы человеку на экране и в окне поверх приложений (D-44): одна
 * карточка — одна форма для всех оболочек. Чистая функция (D-22).
 */
object RequestScreens {
    fun card(r: UserRequest, tasks: TasksState, now: Timestamp): RequestCard = RequestCard(
        id = r.id,
        kind = kindLabel(r.kind),
        title = r.title,
        body = r.body,
        origin = r.task?.let { t -> "задача «${tasks.tasks[t]?.title ?: t.value}»" + (r.session?.let { " · сессия ${it.value}" } ?: "") }
            ?: r.session?.let { "сессия ${it.value}" } ?: "контур",
        task = r.task,
        session = r.session,
        left = r.expiresAt?.let { left(it.epochMillis - now.epochMillis) } ?: "без срока",
        buttons = RequestAction.buttons(r).zip(r.options) { b, o ->
            AnswerButton(
                b.label, b.uri, b.input, b.confirm,
                when (o.role) {
                    OptionRole.ACCEPT -> Tone.ACTIVE
                    OptionRole.REJECT, OptionRole.NEUTRAL -> Tone.NEUTRAL
                    OptionRole.DESTRUCTIVE -> Tone.ERROR
                },
            )
        },
    )

    fun cards(requests: List<UserRequest>, tasks: TasksState, now: Timestamp): List<RequestCard> =
        requests.filter { !it.terminal }.sortedBy { it.createdAt.epochMillis }.map { card(it, tasks, now) }

    fun kindLabel(k: RequestKind): String = k.label

    /** Остаток срока: «истёк» или длительность словами (D-89). */
    fun left(ms: Long): String = if (ms <= 0) "истёк" else com.dpsmpx.dpai.core.text.Words.duration(ms.milliseconds)
}
