package com.dpsmpx.dpai.orchestrator.efficiency

import com.dpsmpx.dpai.contexts.Handoff
import com.dpsmpx.dpai.contexts.HandoffReason
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.execution.claude.ClaudeTurn
import com.dpsmpx.dpai.execution.claude.TurnFiles
import com.dpsmpx.dpai.execution.claude.TurnStatus
import com.dpsmpx.dpai.execution.claude.TurnUsage
import com.dpsmpx.dpai.sessions.PromptFacts
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.sessions.UsageTotals
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Решения слоя D-40 без контура: продолжение, маршрут модели, бюджет (`docs/EFFICIENCY.md`, §3, §4, §7). */
class EfficiencyLogicTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(77))
    private val task = ids.next(IdKinds.TASK)
    private val slot = ids.next(IdKinds.ACCOUNT)
    private val target = ids.next(IdKinds.TARGET)
    private val provider = ProviderSessionId.newUuid(RandomSource.seeded(78))

    private fun session(usage: UsageTotals, continues: SessionId? = null, p: ProviderSessionId = provider) = SessionRecord(
        ids.next(IdKinds.SESSION), "k-" + ids.next(IdKinds.OPERATION).value, task, slot, ids.next(IdKinds.SUBSCRIPTION), target, "/c", p, continues,
        SessionState.CANCELLED, clock.now(), clock.now(), usage = usage,
    )

    private fun seen(mode: PromptMode, context: Long? = 20_000, compacted: Boolean = false, turns: Int = 1, unknown: Int = 0) = UsageTotals(
        turns = turns, unknownTurns = unknown, lastContextTokens = context, contextWindow = 200_000, compacted = compacted,
        prompts = 1, lastPrompt = PromptFacts(mode, 100, 40, 1),
    )

    private fun handoff(prev: SessionRecord, reason: HandoffReason) = Handoff(
        ids.next(IdKinds.HANDOFF), "h", reason, prev.id, task, ids.next(IdKinds.PROJECT), ids.next(IdKinds.CONTEXT), Hashing.sha256("c"),
        "s", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, "дальше", emptyList(), null, null, null, clock.now(),
    )

    private fun plan(prev: SessionRecord?, all: List<SessionRecord> = listOfNotNull(prev), h: Handoff? = null, policy: ResumePolicy = ResumePolicy()) =
        Continuation.plan(prev, h, SessionsState(all.associateBy { it.id }), slot, target, policy, 200_000)

    @Test
    fun resumedSessionThatSawContextGetsDelta() {
        val prev = session(seen(PromptMode.FULL))
        val p = plan(prev)
        assertEquals(PromptMode.DELTA, p.mode)
        assertTrue(p.reuseProvider)
    }

    @Test
    fun unknownOrCompactedHistoryGetsFullPrompt() {
        // Ход без итога (расход неизвестен) или сжатая история — что видела сессия, неизвестно.
        for (u in listOf(seen(PromptMode.FULL, unknown = 1), seen(PromptMode.FULL, compacted = true), seen(PromptMode.FULL, turns = 0), UsageTotals.EMPTY)) {
            val p = plan(session(u))
            assertEquals(PromptMode.FULL, p.mode, u.toString())
            assertTrue(p.reuseProvider)
        }
    }

    @Test
    fun deltaChainIsWalkedBackToTheFullPrompt() {
        val first = session(seen(PromptMode.FULL))
        val second = session(seen(PromptMode.DELTA), continues = first.id)
        assertEquals(PromptMode.DELTA, plan(second, listOf(first, second)).mode)
        // Сжатие в начале цепочки — разница небезопасна.
        val compactedFirst = first.copy(usage = seen(PromptMode.FULL, compacted = true))
        assertEquals(PromptMode.FULL, plan(second, listOf(compactedFirst, second)).mode)
    }

    @Test
    fun bigHistoryOverflowOrOtherSlotIsCheckpoint() {
        val big = plan(session(seen(PromptMode.FULL, context = 150_000)))
        assertEquals(PromptMode.CHECKPOINT, big.mode)
        assertFalse(big.reuseProvider)
        val prev = session(seen(PromptMode.FULL))
        assertEquals(PromptMode.CHECKPOINT, plan(prev, h = handoff(prev, HandoffReason.CONTEXT_OVERFLOW)).mode)
        val elsewhere = Continuation.plan(prev, null, SessionsState(mapOf(prev.id to prev)), ids.next(IdKinds.ACCOUNT), target, ResumePolicy(), 200_000)
        assertEquals(PromptMode.CHECKPOINT, elsewhere.mode)
        // Выключено — как до D-40: продолжение той же сессии полным prompt.
        val legacy = plan(session(seen(PromptMode.FULL, context = 190_000)), policy = ResumePolicy(enabled = false))
        assertEquals(PromptMode.FULL, legacy.mode)
        assertTrue(legacy.reuseProvider)
        assertEquals(PromptMode.FULL, plan(null).mode)
    }

    private fun taskRecord(policy: TaskPolicy, verificationFailures: Int = 0) = TaskRecord(
        task, "t", "o", Priority.NORMAL, emptySet(), emptySet(), policy, clock.now(),
        failures = if (verificationFailures == 0) emptyMap() else mapOf("verification:x" to verificationFailures),
    )

    @Test
    fun routeByClassEscalatesAfterQualityFailure() {
        val router = ModelRouter(RoutingPolicy())
        val trivial = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.TRIVIAL)), BudgetMode.NORMAL)
        assertEquals("haiku", trivial.model)
        assertEquals("low", trivial.effort)
        assertFalse(trivial.subagents)
        assertNull(trivial.fallbackModel)
        val up = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.TRIVIAL), verificationFailures = 1), BudgetMode.NORMAL)
        assertEquals("sonnet", up.model)
        assertEquals("medium", up.effort)
        assertEquals("haiku", up.fallbackModel)
        assertEquals("escalated:1", up.route)
        // Модель аккаунта (обычная разработка) не угадывается: растёт усилие.
        val normal = router.route(taskRecord(TaskPolicy(), verificationFailures = 2), BudgetMode.NORMAL)
        assertNull(normal.model)
        assertEquals("xhigh", normal.effort)
        val top = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.ARCHITECTURE), verificationFailures = 5), BudgetMode.NORMAL)
        assertEquals("opus", top.model)
        assertEquals("max", top.effort)
    }

    @Test
    fun pinnedModelAndBudgetDowngradeRules() {
        val router = ModelRouter(RoutingPolicy())
        val pinned = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.TRIVIAL, model = "opus"), verificationFailures = 3), BudgetMode.FRUGAL)
        assertEquals("opus", pinned.model)
        assertEquals("pinned", pinned.route)
        val recon = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.REPOSITORY_RECON)), BudgetMode.FRUGAL)
        assertEquals("haiku", recon.model)
        assertTrue(recon.downgraded)
        // Критичное и уже проваленное не удешевляется.
        val review = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.CRITICAL_REVIEW)), BudgetMode.FRUGAL)
        assertEquals("opus", review.model)
        assertFalse(review.downgraded)
        val failed = router.route(taskRecord(TaskPolicy(taskClass = TaskClass.REPOSITORY_RECON), verificationFailures = 1), BudgetMode.FRUGAL)
        assertEquals("opus", failed.model)
        val legacy = ModelRouter(RoutingPolicy(enabled = false)).route(taskRecord(TaskPolicy(taskClass = TaskClass.TRIVIAL)), BudgetMode.FRUGAL)
        assertNull(legacy.model)
        assertTrue(legacy.subagents)
    }

    @Test
    fun contextBudgetLeavesRoomForTheTurn() {
        val p = ContextBudgetPolicy()
        // (200k − 32k − 25k) × 0,8 × 0,25.
        assertEquals(28_600, p.tokens(200_000))
        assertEquals(28_600, p.tokens(null))
        assertEquals(17_160, p.tokens(200_000, BudgetMode.FRUGAL))
        assertEquals(60_000, p.tokens(1_000_000))
        assertNull(ContextBudgetPolicy(enabled = false).policy(200_000).maxTokens)
    }

    private fun turn(read: List<String>, edited: List<String>, text: String?, status: TurnStatus = TurnStatus.COMPLETED) = ClaudeTurn(
        status, ProviderSessionId.newUuid(RandomSource.seeded(5)), text, null, TurnUsage.unknown("тест"), null, 0, emptyList(),
        files = TurnFiles(read, edited),
    )

    @Test
    fun workingSetRanksEditedThenOftenReadInsideTheTreeOnly() {
        // D-41: пути — от рабочего дерева; вне дерева — не берутся; изменённые — первыми; названные git status — нет.
        val ws = WorkingSet("/t/task-1/")
        ws.add(turn(listOf("/t/task-1/a.kt", "/t/task-1/b.kt", "/home/u/.gradle/x.jar", "/t/task-10/z.kt"), listOf("/t/task-1/c.kt"), "первый"), 100)
        ws.add(turn(listOf("/t/task-1/b.kt"), emptyList(), null, TurnStatus.INCOMPLETE), 100)
        assertEquals(listOf("c.kt", "b.kt", "a.kt"), ws.files(10, emptyList()))
        assertEquals(listOf("b.kt"), ws.files(10, listOf("c.kt", "a.kt")))
        assertEquals(listOf("c.kt"), ws.files(1, emptyList()))
        // Итог — только завершённого хода; прерванный ход итог не меняет.
        assertEquals("первый", ws.summary)
        // Ключ в тексте модели не уходит дальше (D-14); длинный итог обрезан с пометкой.
        ws.add(turn(emptyList(), emptyList(), "ключ sk-ant-api03-" + "A".repeat(40) + " " + "слово ".repeat(50)), 60)
        val sum = ws.summary!!
        assertFalse("sk-ant-api03-AAAA" in sum, sum)
        assertTrue(sum.endsWith("… (обрезано)") && sum.length <= 60 + 13, sum)
    }

    @Test
    fun workingSetSeedsFromHandoffAfterRestart() {
        val ws = WorkingSet("/t/task-1")
        ws.seed(listOf("x.kt", "y.kt"), "до перезапуска")
        ws.add(turn(listOf("/t/task-1/y.kt"), emptyList(), null, TurnStatus.INCOMPLETE), 100)
        assertEquals(listOf("y.kt", "x.kt"), ws.files(10, emptyList()))
        assertEquals("до перезапуска", ws.summary)
    }
}
