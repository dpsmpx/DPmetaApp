package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.git.FileChange
import com.dpsmpx.dpai.orchestrator.git.TaskDiff
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.FindingSeverity
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.ReviewFinding
import com.dpsmpx.dpai.tasks.ReviewRecord
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.ReviewVerdict
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.time.Duration.Companion.minutes
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
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.RUN_FIRST, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.RUNNING)), "идущей — без правки политики")
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.RUN_FIRST, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.RESUME, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY, held = true)))
        assertTrue(TaskAction.REPLY in TaskScreens.actions(task(TaskState.BLOCKED)) && TaskAction.RETRY in TaskScreens.actions(task(TaskState.BLOCKED)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.VERIFY, TaskAction.WAIVE, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.COMPLETION_UNVERIFIED)))
        assertEquals(listOf(TaskAction.POLICY, TaskAction.RETRY, TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.FAILED)), "завершённой — без области")
        assertEquals(listOf(TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.DONE)))
        assertTrue(TaskAction.CANCEL.dangerous && TaskAction.WAIVE.dangerous && !TaskAction.HOLD.dangerous)
        assertEquals(id, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).id)
    }

    @Test
    fun changesKeepKinds() {
        val patch = listOf(
            "diff --git a/calc.py b/calc.py", "index 1111111..2222222 100644", "--- a/calc.py", "+++ b/calc.py",
            "@@ -1,2 +1,3 @@", " def add(a, b):", "-    return a - b", "+    return a + b", "+++ строка, начатая с ++", "--- строка, начатая с --",
            "\\ No newline at end of file",
        ).joinToString("\n")
        val d = TaskDiff("main", "0123456789abcdef", "task/x", listOf(FileChange("calc.py", 2, 1)), listOf("notes.txt"), patch, truncated = true)
        val v = TaskChangesScreen.of(d)
        assertEquals("файлов 1 · новых 1 · от main до «task/x»", v.summary)
        val kinds = v.lines.map { it.kind }
        assertEquals(listOf(DiffKind.SECTION, DiffKind.STAT, DiffKind.SECTION, DiffKind.FILE, DiffKind.SECTION), kinds.subList(0, 5))
        assertEquals(DiffLine(DiffKind.STAT, "+2 −1  calc.py"), v.lines[1])
        assertEquals(DiffLine(DiffKind.FILE, "notes.txt"), v.lines[3])
        assertEquals(List(4) { DiffKind.FILE }, kinds.subList(5, 9), "заголовок файла: --- и +++ до @@ — имена")
        assertEquals(
            listOf(DiffKind.HUNK, DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.ADDED, DiffKind.REMOVED, DiffKind.NOTE),
            kinds.subList(9, 16),
            "после @@ строки +++ и --- — содержимое, а не имена файлов",
        )
        assertTrue(v.lines.last().kind == DiffKind.NOTE && "обрезан" in v.lines.last().text)
        assertTrue(TaskChangesScreen.of(d.copy(files = emptyList(), untracked = emptyList(), patch = "", truncated = false)).summary.startsWith("Изменений нет"))
        val long = TaskChangesScreen.of(d, limit = 5)
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
            "ходов 2 (+1 без данных) · вход 1,0 тыс. · выход 1,5 тыс. · кеш 39,0 тыс. · \$0.12 (оценка)",
            TaskScreens.detail(task(TaskState.RUNNING), sessions).usage,
            "сессии чужой задачи не считаются",
        )
        assertEquals(null, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).usage, "ходов нет — не «0 токенов»")
        assertEquals("ходов 5 · вход 9,0 млн · выход 0 · \$3.00", TaskScreens.detail(task(TaskState.RUNNING).copy(id = other.task), sessions).usage)
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
        assertTrue(d.assignments[1].contains("Termux (claude-slot-2) · подписка B — передана другой подписке: предел подписки · идёт"), d.assignments.toString())
        assertEquals(null, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).executor, "сессий нет — исполнителя нет")
        val tasks = com.dpsmpx.dpai.tasks.TasksState(mapOf(id to task(TaskState.RUNNING)))
        val work = TaskScreens.work(sessions, tasks, "dpai-proj", java.time.ZoneOffset.UTC)
        assertEquals(setOf(tB), work.keys, "завершённая сессия слота A — он свободен")
        assertTrue(work.getValue(tB).single().startsWith("«задача» · проект dpai-proj · с "), work.toString())
    }

    /** D-42, п. 1: лист задачи показывает область и зависимости; выбор зависимостей — без завершённых и самой задачи. */
    @Test
    fun scopeAndDependenciesInDetail() {
        val gen = IdGenerator(clock, RandomSource.seeded(4))
        val dep = TaskRecord(gen.next(IdKinds.TASK), "база", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.QUEUED)
        val done = TaskRecord(gen.next(IdKinds.TASK), "готовая", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.DONE)
        val t = task(TaskState.READY).copy(dependencies = setOf(dep.id), scope = setOf("src/a"))
        val all = listOf(t, dep, done).associateBy { it.id }
        val d = TaskScreens.detail(t, SessionsState.EMPTY, tasks = all)
        assertEquals(setOf("src/a"), d.scope)
        assertEquals(listOf(DependencyLine(dep.id, "база", "QUEUED")), d.dependencies)
        assertEquals(listOf(dep.id), TaskScreens.dependencyChoices(all, except = t.id).map { it.id })
    }

    /** D-73: лист задачи — роли из политики и итог каждой; нет итога — «ещё нет». */
    @Test
    fun reviewsInDetail() {
        val blocked = ReviewRecord(
            ReviewRole.SECURITY, ReviewVerdict.BLOCKED, 1, null,
            listOf(ReviewFinding(FindingSeverity.BLOCKING, "секрет в журнале", "src/Log.kt", 12, "ключ пишется целиком"), ReviewFinding(FindingSeverity.MINOR, "имя", null, null, null)),
            omitted = 2,
        )
        val t = task(TaskState.READY).copy(policy = TaskPolicy(reviews = setOf(ReviewRole.SECURITY, ReviewRole.CODE)), reviews = mapOf(ReviewRole.SECURITY to blocked))
        val d = TaskScreens.detail(t, SessionsState.EMPTY)
        assertEquals(listOf(ReviewRole.CODE, ReviewRole.SECURITY), d.reviews.map { it.role })
        assertEquals("ещё нет", d.reviews[0].verdict)
        val sec = d.reviews[1]
        assertEquals("нужно исправить", sec.verdict)
        assertEquals(Tone.WARNING, sec.tone)
        assertEquals(FindingLine(FindingSeverity.BLOCKING, "секрет в журнале", "src/Log.kt:12", "ключ пишется целиком"), sec.findings.first())
        assertEquals("не показано находок: 2", sec.note)
        val waived = TaskScreens.review(ReviewRole.CODE, ReviewRecord(ReviewRole.CODE, ReviewVerdict.WAIVED, 2, null, by = "автор", reason = "ревью не отвечает"))
        assertEquals("решил: автор · ревью не отвечает", waived.note)
        assertEquals(emptyList(), TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).reviews, "без ролей — без строк")
    }

    /** D-73: сессию ревью не продолжают — запрос и свежая сессия у последней рабочей; в строке сводки — роль. */
    @Test
    fun reviewSessionIsNotContinued() {
        val gen = IdGenerator(clock, RandomSource.seeded(5))
        fun session(review: String?): SessionRecord {
            clock.advance(1.minutes)
            return SessionRecord(
                gen.next(IdKinds.SESSION), "k", id, gen.next(IdKinds.ACCOUNT), gen.next(IdKinds.SUBSCRIPTION), gen.next(IdKinds.TARGET), "/c",
                com.dpsmpx.dpai.core.ids.ProviderSessionId.newUuid(RandomSource.seeded(6)), null, SessionState.COMPLETED, clock.now(), clock.now(), review = review,
            )
        }
        val work = session(null)
        val review = session("CODE")
        val sessions = SessionsState(listOf(work, review).associateBy { it.id })
        val tasks = TasksState(mapOf(id to task(TaskState.BLOCKED)))
        assertEquals(emptyList(), Screens.sessionActions(review.id, sessions, tasks), "ревью только читает")
        assertEquals(listOf(SessionAction.SEND, SessionAction.FRESH, SessionAction.REASSIGN), Screens.sessionActions(work.id, sessions, tasks), "продолжают работу, хотя ревью было позже")
        // «Перенести» (D-77) — у рабочей сессии незавершённой задачи, не на проверке.
        assertTrue(SessionAction.REASSIGN !in Screens.sessionActions(work.id, sessions, TasksState(mapOf(id to task(TaskState.VERIFIED)))))
        assertTrue(SessionAction.REASSIGN !in Screens.sessionActions(work.id, sessions, TasksState(mapOf(id to task(TaskState.DONE)))))
        assertEquals("ревью кода", Screens.reviewLabel("CODE"))
        assertEquals("ревью STYLE", Screens.reviewLabel("STYLE"))
    }
}
