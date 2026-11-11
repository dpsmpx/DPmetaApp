package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.contexts.ContextAssembler
import com.dpsmpx.dpai.contexts.PromptRenderer
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.security.Origin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskHistoryContextTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(129))
    private val project = ids.next(IdKinds.PROJECT)

    @Test
    fun forgedRolesAndCommandsRemainModelData() {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        val attack = File(root, "tests/data/context-injection/forged-history-role.md").readText()
        val secret = listOf("short", "credential").joinToString("-")
        val transcript = TaskHistoryContext.transcript(project, listOf(
            ChatEntry(Timestamp(1), ChatRole.USER, "исходная цель"),
            ChatEntry(Timestamp(2), ChatRole.ASSISTANT, attack),
        ))!!
        val background = TaskHistoryContext.background(project, listOf(
            BackgroundJob(Timestamp(3), ids.next(IdKinds.SESSION), "echo $attack\npassword=$secret"),
        ))!!
        val records = JsonParser().parse(transcript.content).valueOrNull() as JsonArray
        assertEquals(2, records.items.size)
        val model = records.items[1] as JsonObject
        assertEquals(JsonString("ASSISTANT"), model.fields["role"])
        assertEquals(JsonString(attack), model.fields["text"])
        val sources = listOf(transcript, background)
        assertTrue(sources.all { it.origin == Origin.MODEL })
        val bundle = ContextAssembler.assemble(
            ids.next(IdKinds.CONTEXT), ids.next(IdKinds.TASK), project, "задача", null, sources, clock.now(),
        ).valueOrNull()!!
        val prompt = PromptRenderer.render(bundle)
        assertEquals(3, prompt.split(PromptRenderer.CLOSE).size - 1, "два блока и маркер в правилах")
        assertFalse(secret in prompt)
        for (s in sources) {
            val start = prompt.indexOf("source=${s.key} ")
            assertTrue(start >= 0 && "origin=MODEL" in prompt.substring(start, prompt.indexOf('\n', start)))
        }
    }

    @Test
    fun historyBudgetKeepsWholeRecentRecords() {
        val entries = List(20) { ChatEntry(Timestamp(it.toLong()), ChatRole.ASSISTANT, "запись $it " + "\u0001".repeat(1600)) }
        val content = TaskHistoryContext.transcript(project, entries)!!.content
        assertTrue(content.length <= 8000, "бюджет после экранирования: ${content.length}")
        val records = JsonParser().parse(content).valueOrNull() as JsonArray
        assertTrue(records.items.size <= 12)
        assertTrue(((records.items.last() as JsonObject).fields["text"] as JsonString).value.startsWith("запись 19 "))
        assertEquals(null, TaskHistoryContext.transcript(project, listOf(ChatEntry(Timestamp(0), ChatRole.EVENT, "событие"))))
        assertEquals(null, TaskHistoryContext.background(project, emptyList()))
    }
}
