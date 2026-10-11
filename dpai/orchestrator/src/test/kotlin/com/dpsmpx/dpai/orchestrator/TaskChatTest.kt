package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Лента чата задачи (D-59): формат, пределы по роли, редактирование секретов, последние записи. */
class TaskChatTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(9))

    @Test
    fun roundTripAndLimits() {
        val s = ids.next(IdKinds.SESSION)
        val list = TaskChat.append(
            emptyList(),
            listOf(
                TaskChat.entry(Timestamp(1), ChatRole.USER, "сделай отчёт"),
                TaskChat.entry(Timestamp(2), ChatRole.THINKING, "x".repeat(10_000), s),
                TaskChat.entry(Timestamp(3), ChatRole.ASSISTANT, "Готово.", s),
                TaskChat.entry(Timestamp(4), ChatRole.EVENT, "  ", s),
            ),
        )
        assertEquals(3, list.size, "пустая запись не пишется")
        assertTrue(list[1].text.length <= TaskChat.limit(ChatRole.THINKING) + 2, "рассуждение — кратко")
        val back = TaskChat.fromJson(JsonParser().parse(CanonicalJson.write(TaskChat.toJson(list))).valueOrNull() as JsonObject)
        assertEquals(list, back.valueOrNull())
        assertIs<Outcome.Failure>(TaskChat.fromJson(JsonParser().parse("""{"format":"dpai.task-chat","version":2,"entries":[]}""").valueOrNull() as JsonObject))
    }

    @Test
    fun secretsAreRedactedAndOnlyTheLatestKept() {
        val token = "sk-ant-" + "api03-" + "A".repeat(40)
        assertTrue(token !in TaskChat.entry(Timestamp(1), ChatRole.ASSISTANT, "ключ $token").text, "секрет не попадает в ленту (D-14)")
        val many = (1..TaskChat.MAX + 10).map { TaskChat.entry(Timestamp(it.toLong()), ChatRole.EVENT, "событие $it") }
        val kept = TaskChat.append(emptyList(), many)
        assertEquals(TaskChat.MAX, kept.size)
        assertEquals("событие 11", kept.first().text)
    }

    @Test
    fun queuedMessagesRoundTrip() {
        // D-60: очередь сообщений задачи переживает перезапуск.
        val list = listOf(QueuedMessage("первое", Timestamp(1)), QueuedMessage("второе", Timestamp(2), model = "sonnet", effort = "high"))
        val back = QueuedMessages.fromJson(JsonParser().parse(CanonicalJson.write(QueuedMessages.toJson(list))).valueOrNull() as JsonObject)
        assertEquals(list, back.valueOrNull())
    }
}
