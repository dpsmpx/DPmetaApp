package com.dpsmpx.dpai.providers

import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatHandoffTest {
    @Test fun skipsFailedAnswersAndLimitsTransferredContext() {
        val ids = IdGenerator(Clock.SYSTEM, RandomSource.secure())
        val c = Conversation(ids.next(IdKinds.CORRELATION), ids.next(IdKinds.ACCOUNT), "test",
            listOf(ChatEntry(ChatRole.USER, "первый вопрос"),
                ChatEntry(ChatRole.ASSISTANT, "ошибка, не передавать", status = EntryStatus.FAILED),
                ChatEntry(ChatRole.ASSISTANT, "готовый ответ"),
                ChatEntry(ChatRole.USER, "x".repeat(30_000))))
        val prompt = ChatHandoff.prompt(c)
        assertTrue("первый вопрос" in prompt)
        assertTrue("готовый ответ" in prompt)
        assertFalse("ошибка, не передавать" in prompt)
        assertTrue(prompt.length <= ChatHandoff.MAX_CHARACTERS)
    }

    @Test fun preservesRolesAndBoundsImportedMessages() {
        val source = (1..50).map { ChatMessage(if (it % 2 == 0) ChatRole.ASSISTANT else ChatRole.USER, "№$it") }
        val imported = ChatHandoff.importMessages(source)
        assertEquals(ChatHandoff.MAX_MESSAGES, imported.size)
        assertEquals("№19", imported.first().text)
        assertEquals("№50", imported.last().text)
        assertEquals(ChatRole.ASSISTANT, imported.last().role)
        val longSource = (1..50).map { ChatMessage(ChatRole.USER, "x".repeat(2_000) + it) }
        val bounded = ChatHandoff.importMessages(longSource)
        assertTrue(bounded.sumOf { it.text.length } <= ChatHandoff.MAX_CHARACTERS)
        assertTrue(bounded.last().text.endsWith("50"))
    }
}
