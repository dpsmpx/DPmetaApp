package com.dpsmpx.dpai.monitoring

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.sessions.PromptFacts
import com.dpsmpx.dpai.sessions.ToolTotals
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.sessions.UsageTotals
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.TasksState
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Отчёт расхода (D-40; `docs/EFFICIENCY.md`, §9): суммы, доли, подписи наблюдаемого и оценки. */
class UsageReportTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(91))

    private fun session(task: com.dpsmpx.dpai.core.ids.TaskId, u: UsageTotals) = SessionRecord(
        ids.next(IdKinds.SESSION), "k-" + ids.next(IdKinds.OPERATION).value, task, ids.next(IdKinds.ACCOUNT), ids.next(IdKinds.SUBSCRIPTION),
        ids.next(IdKinds.TARGET), "/c", ProviderSessionId.newUuid(RandomSource.seeded(92)), null, SessionState.COMPLETED, clock.now(), clock.now(), usage = u,
    )

    @Test
    fun sumsSharesAndLabels() {
        val a = ids.next(IdKinds.TASK)
        val b = ids.next(IdKinds.TASK)
        val s1 = session(
            a,
            UsageTotals(
                turns = 2, inputTokens = 1_000, outputTokens = 500, cacheReadTokens = 8_000, cacheCreationTokens = 1_000, costUsd = BigDecimal("0.10"),
                costEstimated = true, models = mapOf("claude-opus-x" to 2_000L, "claude-haiku-y" to 8_500L),
                prompts = 2, promptTokens = 3_000, reusedTokens = 1_000, lastPrompt = PromptFacts(PromptMode.DELTA, 10, 4, 1, route = "escalated:1"),
            ),
        )
        val s2 = session(b, UsageTotals(unknownTurns = 1, prompts = 1, promptTokens = 1_000, omittedTokens = 0, lastPrompt = PromptFacts(PromptMode.FULL, 10, 4, 1, route = "class")))
        val done = TaskRecord(a, "a", "a", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.DONE, retries = 1)
        val r = UsageReport.build(SessionsState(listOf(s1, s2).associateBy { it.id }), TasksState(mapOf(a to done)))
        assertEquals(10_000, r.totalInput)
        assertEquals(0.8, r.cacheReadShare)
        assertEquals(10_500, r.tokensPerDoneTask)
        assertEquals(2_000.0 / 10_500, r.expensiveShare)
        assertEquals(0.2, r.contextSavedShare)
        assertEquals(mapOf(PromptMode.DELTA to 1, PromptMode.FULL to 1), r.modes)
        assertEquals(mapOf("escalated" to 1, "class" to 1), r.routes)
        assertEquals(listOf(a to 10_500L), r.topTasks)
        val text = r.render()
        assertTrue("+1 без итога: суммы — нижняя граница" in text, text)
        assertTrue("(оценка по прейскуранту)" in text && "## Контекст (оценка оркестратора)" in text)
    }

    @Test
    fun emptyStateHasNoFakeZeros() {
        val r = UsageReport.build(SessionsState.EMPTY, TasksState.EMPTY)
        assertNull(r.cacheReadShare)
        assertNull(r.tokensPerDoneTask)
        assertNull(r.contextSavedShare)
        assertNull(r.duplicateShare)
        assertNull(r.avgContextTokens)
        assertNull(r.successShare)
        assertTrue("—" in r.render())
        // Без замера инструментов — нет и раздела о них, а не нули.
        assertTrue("## Инструменты в ходе" !in r.render())
    }

    @Test
    fun toolsSectionSumsSessionsAndSplitsByMode() {
        // D-41: замер инструментов по сессиям; повторы — доля вывода; вызовы на сессию — по режиму prompt.
        val a = ids.next(IdKinds.TASK)
        fun tools(read: ToolCount, bash: ToolCount) = ToolTotals(turns = 1, byTool = mapOf("Read" to read, "Bash" to bash), filesRead = 3, largestResultBytes = bash.resultBytes)
        val full = session(
            a,
            UsageTotals(
                turns = 1, inputTokens = 100, cacheReadTokens = 2_900, durationMs = 4_000, lastContextTokens = 30_000,
                lastPrompt = PromptFacts(PromptMode.FULL, 10, 4, 1), tools = tools(ToolCount(6, 3_000, 0, 2, 1_000), ToolCount(4, 3_000, 1)),
            ),
        )
        val checkpoint = session(
            a,
            UsageTotals(
                turns = 1, inputTokens = 100, durationMs = 2_000, lastContextTokens = 10_000,
                lastPrompt = PromptFacts(PromptMode.CHECKPOINT, 10, 4, 1), tools = tools(ToolCount(2, 300), ToolCount(0, 0)),
            ),
        )
        val done = TaskRecord(a, "a", "a", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.DONE)
        val r = UsageReport.build(SessionsState(listOf(full, checkpoint).associateBy { it.id }), TasksState(mapOf(a to done)))
        assertEquals(ToolCount(12, 6_300, 1, 2, 1_000), r.tools.total)
        assertEquals(2, r.tools.turns)
        assertEquals(1_000.0 / 6_300, r.duplicateShare)
        assertEquals(2_100, r.toolTokens)
        assertEquals(2_100.0 / 3_100, r.toolShareOfInput)
        assertEquals(mapOf(PromptMode.FULL to 10.0, PromptMode.CHECKPOINT to 2.0), r.toolCallsByMode)
        assertEquals(20_000, r.avgContextTokens)
        assertEquals(3_000, r.avgTurnMs)
        assertEquals(1.0, r.successShare)
        assertEquals(listOf("Read", "Bash"), r.topTools().map { it.key })
        val text = r.render()
        assertTrue("## Инструменты в ходе (замер оркестратора по потоку)" in text, text)
        assertTrue("нижняя граница" in text && "(≈2100)" in text, text)
    }
}
