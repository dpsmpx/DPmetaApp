package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.notifications.OptionRole
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.notifications.RequestOption
import com.dpsmpx.dpai.notifications.RequestState
import com.dpsmpx.dpai.notifications.UserRequest
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Карточка запроса (D-44): задача по названию, срок, кнопки с ролью и ссылкой ответа своего запроса. */
class RequestScreensTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(7))

    @Test
    fun cardShowsOriginLeftTimeAndButtons() {
        val task = ids.next(IdKinds.TASK)
        val tasks = Tasks.applyAll(TasksState.EMPTY, Tasks().create(TasksState.EMPTY, task, "починить сборку", "цель", Priority.NORMAL, clock.now()).valueOrNull()!!)
        val r = UserRequest.create(
            ids.next(IdKinds.REQUEST), RequestKind.USER_RESOLUTION, "task.blocked", "task:x", "Задача остановлена", "Что делать?",
            listOf(RequestOption("retry", "Повторить", OptionRole.ACCEPT), RequestOption("reply", "Ответить", needsText = true), RequestOption("cancel", "Отменить", OptionRole.DESTRUCTIVE)),
            task, null, clock.now(), clock.now() + 90.minutes,
        ).valueOrNull()!!
        val card = RequestScreens.card(r, tasks, clock.now())
        assertEquals("нужно решение", card.kind)
        assertEquals("задача «починить сборку»", card.origin)
        assertEquals("1 ч 30 мин", card.left)
        assertEquals(listOf(Tone.ACTIVE, Tone.NEUTRAL, Tone.ERROR), card.buttons.map { it.tone })
        assertEquals(listOf(false, true, false), card.buttons.map { it.needsText })
        assertEquals(listOf(false, false, true), card.buttons.map { it.confirm })
        assertEquals(r.id, RequestAction.parse(card.buttons[2].uri).valueOrNull()?.request)
        // Закрытые на экран не попадают.
        assertEquals(emptyList(), RequestScreens.cards(listOf(r.copy(state = RequestState.ANSWERED)), tasks, clock.now()))
        assertEquals("истёк", RequestScreens.left(0))
    }
}
