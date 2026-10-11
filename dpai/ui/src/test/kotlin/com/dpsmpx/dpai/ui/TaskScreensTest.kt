package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.git.TaskChanges
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Действия задачи по состоянию (D-42): экран не предлагает того, что оркестратор отклонит. */
class TaskScreensTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(3))
    private val id = ids.next(IdKinds.TASK)
    private fun task(state: TaskState, held: Boolean = false) =
        TaskRecord(id, "задача", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = state, held = held)

    @Test
    fun actionsFollowState() {
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.RUNNING)), "идущей — без правки политики")
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.RESUME, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY, held = true)))
        assertTrue(TaskAction.REPLY in TaskScreens.actions(task(TaskState.BLOCKED)) && TaskAction.RETRY in TaskScreens.actions(task(TaskState.BLOCKED)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.VERIFY, TaskAction.WAIVE, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.COMPLETION_UNVERIFIED)))
        assertEquals(listOf(TaskAction.POLICY, TaskAction.RETRY, TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.FAILED)))
        assertEquals(listOf(TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.DONE)))
        assertTrue(TaskAction.CANCEL.dangerous && TaskAction.WAIVE.dangerous && !TaskAction.HOLD.dangerous)
        assertEquals(id, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).id)
    }

    @Test
    fun changesKeepPartsAndKinds() {
        val committed = listOf(
            " calc.py | 3 ++-", " 1 file changed, 2 insertions(+), 1 deletion(-)", "",
            "diff --git a/calc.py b/calc.py", "index 1111111..2222222 100644", "--- a/calc.py", "+++ b/calc.py",
            "@@ -1,2 +1,3 @@", " def add(a, b):", "-    return a - b", "+    return a + b", "+++ строка, начатая с ++", "--- строка, начатая с --",
            "\\ No newline at end of file",
        )
        val c = TaskChanges("task/x", "main", committed, emptyList(), listOf("notes.txt"), truncated = true)
        val v = TaskChangesScreen.of(c, listOf("незакоммиченное: нет связи"))
        assertEquals("слияние в main: файлов 1 · новых файлов 1", v.summary)
        val kinds = v.lines.map { it.kind }
        assertEquals(DiffKind.SECTION, kinds.first())
        assertEquals(listOf(DiffKind.STAT, DiffKind.STAT, DiffKind.STAT, DiffKind.FILE, DiffKind.FILE, DiffKind.FILE, DiffKind.FILE), kinds.subList(1, 8))
        assertEquals(
            listOf(DiffKind.HUNK, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.REMOVED, DiffKind.NOTE),
            kinds.subList(8, 15),
            "после @@ строки +++ и --- — содержимое, а не имена файлов",
        )
        assertTrue(v.lines.none { it.text == "Не закоммичено в дереве задачи" }, "пустая часть — без заголовка")
        assertEquals(DiffLine(DiffKind.FILE, "notes.txt"), v.lines[v.lines.size - 3])
        assertTrue(v.lines.takeLast(2).all { it.kind == DiffKind.NOTE } && "обрезан" in v.lines[v.lines.size - 2].text)

        assertTrue(TaskChangesScreen.of(TaskChanges("task/x", "main", null, null, emptyList(), false)).summary.contains("не начиналась"))
        assertTrue(TaskChangesScreen.of(TaskChanges("task/x", "main", emptyList(), null, emptyList(), false)).summary.startsWith("Изменений нет"))
        val long = TaskChangesScreen.of(c, limit = 5)
        assertEquals(6, long.lines.size)
        assertTrue("dpai task <id> diff" in long.lines.last().text, "длинное — целиком в dpai")
    }

    @Test
    fun usageOfTaskAndSessionOnScreen() {
        fun session(u: com.dpsmpx.dpai.sessions.UsageTotals, task: com.dpsmpx.dpai.core.ids.TaskId = id) = com.dpsmpx.dpai.sessions.SessionRecord(
            ids.next(IdKinds.SESSION), "k-" + ids.next(IdKinds.OPERATION).value, task, ids.next(IdKinds.ACCOUNT), ids.next(IdKinds.SUBSCRIPTION),
            ids.next(IdKinds.TARGET), "/c", com.dpsmpx.dpai.core.ids.ProviderSessionId.newUuid(RandomSource.seeded(4)), null,
            com.dpsmpx.dpai.core.state.SessionState.COMPLETED, clock.now(), clock.now(), usage = u,
        )
        val s1 = session(com.dpsmpx.dpai.sessions.UsageTotals(turns = 2, inputTokens = 1_000, cacheReadTokens = 39_000, outputTokens = 1_500, costUsd = java.math.BigDecimal("0.1234"), costEstimated = true))
        val s2 = session(com.dpsmpx.dpai.sessions.UsageTotals(unknownTurns = 1))
        val other = session(com.dpsmpx.dpai.sessions.UsageTotals(turns = 5, inputTokens = 9_000_000, costUsd = java.math.BigDecimal("3")), task = ids.next(IdKinds.TASK))
        val sessions = SessionsState(listOf(s1, s2, other).associateBy { it.id })

        assertEquals(
            "ходов 2 (+1 без итога — суммы не меньше) · вход 40 тыс., из кеша 97 % · выход 1500 · 0.12 $ (оценка)",
            TaskScreens.detail(task(TaskState.RUNNING), sessions).usage,
            "сессии чужой задачи не считаются",
        )
        assertEquals(null, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).usage, "ходов нет — не «0 токенов»")
        assertTrue(Screens.usageLine(Screens.usage(sessions) { it.id == s2.id })!!.startsWith("ходов 0 (+1 без итога"), "только ход без итога — строка есть")
        assertEquals("ходов 5 · вход 9.0 млн, из кеша 0 % · выход 0 · 3.00 $", Screens.usageLine(Screens.usage(sessions) { it.id == other.id }))
        val cheap = s1.copy(usage = s1.usage.copy(costUsd = java.math.BigDecimal("0.004"), costEstimated = false))
        assertEquals("< 0.01 $", Screens.usageLine(Screens.usage(SessionsState(mapOf(cheap.id to cheap))) { true })!!.substringAfterLast(" · "))
        assertEquals(listOf("9999", "10 тыс.", "1.5 млн"), listOf(9_999L, 10_000L, 1_500_000L).map(Screens::tokens))
    }

    @Test
    fun newTaskScopeAndDependencyChoices() {
        assertEquals(setOf("src/a.kt", "docs", "README.md"), TaskScreens.scope(" src/a.kt, docs\nREADME.md "))
        assertEquals(null, TaskScreens.scope(" , "), "пусто — область неизвестна, а не пустая")
        val open = task(TaskState.READY)
        val done = TaskRecord(ids.next(IdKinds.TASK), "готовая", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.DONE)
        assertEquals(listOf(open.id to "задача"), TaskScreens.dependencyChoices(com.dpsmpx.dpai.tasks.TasksState(mapOf(open.id to open, done.id to done))))
    }

    @Test
    fun executorAndHandoffsAreNamedBySlot() {
        // D-56: лист задачи говорит, кто её выполняет и кому и почему она передавалась.
        val logic = com.dpsmpx.dpai.accounts.Accounts()
        var acc = com.dpsmpx.dpai.accounts.AccountsState.EMPTY
        val subA = ids.next(IdKinds.SUBSCRIPTION)
        val subB = ids.next(IdKinds.SUBSCRIPTION)
        acc = com.dpsmpx.dpai.accounts.Accounts.applyAll(acc, logic.registerSubscription(acc, subA, "подписка A", clock.now()).valueOrNull()!!)
        acc = com.dpsmpx.dpai.accounts.Accounts.applyAll(acc, logic.registerSubscription(acc, subB, "подписка B", clock.now()).valueOrNull()!!)
        val tA = ids.next(IdKinds.TARGET)
        val tB = ids.next(IdKinds.TARGET)
        val slotA = ids.next(IdKinds.ACCOUNT)
        val slotB = ids.next(IdKinds.ACCOUNT)
        acc = com.dpsmpx.dpai.accounts.Accounts.applyAll(acc, logic.registerSlot(acc, slotA, subA, tA, "/h/.claude-slot-1", clock.now()).valueOrNull()!!)
        acc = com.dpsmpx.dpai.accounts.Accounts.applyAll(acc, logic.registerSlot(acc, slotB, subB, tB, "/h/.claude-slot-2", clock.now()).valueOrNull()!!)
        fun session(slot: com.dpsmpx.dpai.core.ids.AccountId, sub: com.dpsmpx.dpai.core.ids.SubscriptionId, target: com.dpsmpx.dpai.core.ids.ExecutionTargetId, state: com.dpsmpx.dpai.core.state.SessionState, reason: com.dpsmpx.dpai.core.diagnostics.DiagCode?) =
            com.dpsmpx.dpai.sessions.SessionRecord(
                ids.next(IdKinds.SESSION), "k-" + ids.next(IdKinds.OPERATION).value, id, slot, sub, target, "/c",
                com.dpsmpx.dpai.core.ids.ProviderSessionId.newUuid(RandomSource.seeded(5)), null, state, clock.now(), clock.now(), lastReason = reason,
            ).also { clock.advance(kotlin.time.Duration.parse("10m")) }
        val first = session(slotA, subA, tA, com.dpsmpx.dpai.core.state.SessionState.CANCELLED, com.dpsmpx.dpai.core.diagnostics.DiagCode.CLAUDE_RATE_LIMITED)
        val second = session(slotB, subB, tB, com.dpsmpx.dpai.core.state.SessionState.RUNNING, null)
        val sessions = SessionsState(listOf(first, second).associateBy { it.id })
        val labels = mapOf(tA to "Termux (claude-slot-1)", tB to "Termux (claude-slot-2)")
        val d = TaskScreens.detail(task(TaskState.RUNNING), sessions, null, acc, targetLabel = { labels.getValue(it) })
        assertEquals("Termux (claude-slot-2) · подписка B", d.executor)
        assertEquals(2, d.assignments.size)
        assertTrue(d.assignments[0].contains("Termux (claude-slot-1) · подписка A — начата"), d.assignments.toString())
        assertTrue(d.assignments[1].contains("Termux (claude-slot-2) · подписка B — передана другой подписке: предел подписки · RUNNING"), d.assignments.toString())
        assertEquals(null, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).executor, "сессий нет — исполнителя нет")
    }
}
