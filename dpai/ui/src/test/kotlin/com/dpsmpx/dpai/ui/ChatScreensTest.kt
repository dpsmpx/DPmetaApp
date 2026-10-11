package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.ChatRole
import com.dpsmpx.dpai.orchestrator.TaskChat
import com.dpsmpx.dpai.orchestrator.TurnMessage
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import kotlin.test.Test
import kotlin.test.assertEquals

/** Лента задачи целиком (D-62): вывод модели — только в чате, и у задач, начатых до него. */
class ChatScreensTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(11))
    private val id = ids.next(IdKinds.TASK)
    private val session = ids.next(IdKinds.SESSION)
    private val task = TaskRecord(id, "Отчёт", "собрать отчёт", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), Timestamp(1_000), state = TaskState.RUNNING)

    @Test
    fun taskStartedBeforeChatGetsGoalAndOldTurnOutputs() {
        val turns = listOf(TurnMessage(session, 1, Timestamp(5_000), "COMPLETED", null, "старый итог"), TurnMessage(session, 2, Timestamp(9_000), "COMPLETED", null, "уже в чате"))
        val chat = listOf(TaskChat.entry(Timestamp(8_000), ChatRole.EVENT, "попытка 2"), TaskChat.entry(Timestamp(9_000), ChatRole.ASSISTANT, "уже в чате"))
        val all = Screens.chatEntries(chat, turns, task)
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.EVENT, ChatRole.ASSISTANT), all.map { it.role })
        assertEquals("Отчёт\n\nсобрать отчёт", all.first().text)
        assertEquals("старый итог", all[1].text, "итог раньше чата — в ленте; позже — уже есть в чате, не дублируется")
    }

    @Test
    fun chatThatStartsWithTheGoalIsTakenAsIs() {
        val chat = listOf(TaskChat.entry(Timestamp(1_000), ChatRole.USER, "Отчёт\n\nсобрать отчёт"), TaskChat.entry(Timestamp(2_000), ChatRole.ASSISTANT, "готово"))
        assertEquals(chat, Screens.chatEntries(chat, emptyList(), task))
        assertEquals(1, Screens.chatEntries(emptyList(), emptyList(), task).size, "пустой чат — цель задачи")
    }
}
