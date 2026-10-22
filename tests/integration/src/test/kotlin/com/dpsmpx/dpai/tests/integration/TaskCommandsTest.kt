package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Действия человека с задачами (D-42, п. 2): команды через очередь —
 * политика до запуска, пауза идущего хода и продолжение той же сессией,
 * отмена с остановкой хода, повтор со сбросом счётчиков, исключение
 * для непроверенного; удержание переживает перезапуск (снимок v4).
 */
class TaskCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(242))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(243)))
    private val targets = List(2) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
    private val accounts: AccountsState = run {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        for ((i, t) in targets.withIndex()) {
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, t.id, "/c/$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        acc
    }
    private val project = ids.next(IdKinds.PROJECT)
    private var scenario: (Int) -> MockScenario = { MockScenario.SLOW_OUTPUT }
    private var launches = 0

    /** [verified] — с профилем проверки (успешная проверка → `DONE`), без — непроверенное завершение. */
    private fun boot(verified: Boolean = true): Pair<Orchestrator, MockRunner> {
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "journal.log", segmentSize = 50) as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { _, _ -> scenario(++launches) }
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(244), journal, runner, ProjectConfig(project, null, dir.toString(), profile.takeIf { verified }), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
            snapshots = SnapshotStore(DocumentStore(vfs, "snapshots")), snapshotEvery = 25, documents = DocumentStore(vfs, "docs"),
        )
        o.recover { AgentClient(host, listOf("dpai"), null, "target mock") }
        return o to runner
    }

    private fun Orchestrator.run(c: Command): OperationResult<*> = submit(c).also { runCommands() }.get()

    private fun advance(o: Orchestrator, runner: MockRunner, ticks: Int) = repeat(ticks) {
        o.tick()
        for (s in host.runIds()) while (runner.advance(s)) continue
        o.tick()
        assertEquals(emptyList(), o.invariants())
        clock.advance(1.minutes)
    }

    private fun state(o: Orchestrator, t: TaskId) = o.tasks.tasks.getValue(t).state

    @Test
    fun policyChangesBeforeStartAndIsRefusedWhileRunning() {
        val (o, runner) = boot()
        val t = o.addTask("задача", "цель").valueOrNull()!!
        // Целей больше, чем мест: вторая задача ждёт в очереди, её можно править.
        val busy = (1..4).map { o.addTask("занятость $it", "цель $it").valueOrNull()!! }
        val r = o.run(Command.SetPolicy(t, TaskClass.TRIVIAL, "haiku", "low", 25))
        assertEquals(OperationStatus.SUCCEEDED, r.status, r.diagnostics.toString())
        assertEquals(TaskPolicy().copy(taskClass = TaskClass.TRIVIAL, model = "haiku", effort = "low", maxBudgetUsdCents = 25), o.tasks.tasks.getValue(t).policy)
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SetPriority(t, Priority.CRITICAL)).status)
        o.tick()
        assertEquals(TaskState.RUNNING, state(o, t), "критичная — первой")
        val s = o.sessions.sessions.values.single { it.task == t }
        assertEquals("haiku", s.usage.lastPrompt?.model, "ход запущен с моделью из политики")
        val refused = o.run(Command.SetPolicy(t, TaskClass.ARCHITECTURE, null, null, null))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, refused.diagnostics.single().code, "идущей задаче политику не меняют")
        assertEquals(DiagCode.VALUE_INVALID, o.run(Command.SetPolicy(busy[3], TaskClass.TRIVIAL, "плохая модель", null, null)).diagnostics.single().code)
        // Роли ревью (D-59): у проекта без Git — отказ при постановке, а не запрос человеку после работы.
        val q = busy[3]
        assertEquals(DiagCode.UNSUPPORTED, o.run(Command.SetPolicy(q, TaskClass.NORMAL_ENGINEERING, null, null, null, setOf(ReviewRole.CODE))).diagnostics.single().code)
        assertEquals(DiagCode.UNSUPPORTED, o.addTask("с ревью", "цель", policy = TaskPolicy(reviews = setOf(ReviewRole.CODE))).diagnostics.single().code)
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SetPolicy(q, TaskClass.TRIVIAL, null, null, null, emptySet())).status, "снять роли можно и без Git")
        advance(o, runner, 1)
    }

    @Test
    fun holdStopsTurnAndResumeContinuesSameProviderSession() {
        val (o, runner) = boot()
        val t = o.addTask("долгая", "цель").valueOrNull()!!
        o.tick()
        val first = o.sessions.sessions.values.single { it.task == t }
        assertEquals(SessionState.RUNNING, first.state)
        val held = o.run(Command.Hold(t))
        assertEquals(OperationStatus.SUCCEEDED, held.status, held.diagnostics.toString())
        assertEquals(TaskState.READY, state(o, t))
        assertTrue(o.tasks.tasks.getValue(t).held)
        assertEquals(SessionState.CANCELLED, o.sessions.sessions.getValue(first.id).state, "ход остановлен, сессия сдана handoff")
        assertTrue(o.accounts.slots.values.all { it.sessions.isEmpty() }, "аренда снята")
        repeat(3) { o.tick(); clock.advance(1.minutes) }
        assertEquals(1, o.sessions.sessions.values.count { it.task == t }, "удержанная не запускается")
        assertEquals(emptyList(), o.invariants())

        // Перезапуск: удержание — из журнала и снимка (dpai.tasks-state v4).
        val (second, runner2) = boot()
        assertTrue(second.tasks.tasks.getValue(t).held, "удержание пережило перезапуск")
        assertEquals(OperationStatus.SUCCEEDED, second.run(Command.Resume(t)).status)
        second.tick()
        val next = second.sessions.sessions.values.filter { it.task == t }.maxBy { it.createdAt.epochMillis }
        assertEquals(first.provider, next.provider, "продолжение — та же сессия провайдера")
        advance(second, runner2, 3)
        assertEquals(TaskState.DONE, state(second, t))
    }

    /**
     * Пауза, пришедшая посреди такта (ТЗ, §73, «Пауза всем»), не даёт
     * запустить задачу, уже попавшую в план этого такта: удержанная
     * остаётся READY, решает признак held.
     */
    @Test
    fun holdArrivingMidTickStopsAlreadyPlannedLaunch() {
        val (o, _) = boot()
        val a = o.addTask("первая", "цель").valueOrNull()!!
        val b = o.addTask("вторая", "цель").valueOrNull()!!
        // Команды приходят, пока такт запускает первую из запланированных: их исполнит такт перед следующим запуском.
        scenario = { n ->
            if (n == 1) listOf(a, b).forEach { o.submit(Command.Hold(it)) }
            MockScenario.SLOW_OUTPUT
        }
        o.tick()
        val started = listOf(a, b).filter { t -> o.sessions.sessions.values.any { it.task == t } }
        assertEquals(1, started.size, "вторая из плана такта не запускается: $started")
        assertTrue(listOf(a, b).all { o.tasks.tasks.getValue(it).held })
        assertEquals(emptyList(), o.invariants())
    }

    @Test
    fun cancelStopsRunningTurnAndFreesResources() {
        val (o, _) = boot()
        val t = o.addTask("отменить", "цель").valueOrNull()!!
        o.tick()
        assertEquals(TaskState.RUNNING, state(o, t))
        val r = o.run(Command.Cancel(t))
        assertEquals(OperationStatus.SUCCEEDED, r.status, r.diagnostics.toString())
        assertEquals(TaskState.CANCELLED, r.value, "итог — состояние после действия")
        assertEquals(TaskState.CANCELLED, state(o, t))
        assertTrue(o.sessions.sessions.values.filter { it.task == t }.all { it.terminal })
        assertTrue(o.accounts.slots.values.all { it.sessions.isEmpty() })
        assertEquals(emptyList(), o.invariants())
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, o.run(Command.Cancel(t)).diagnostics.single().code, "отменённую не отменяют")
    }

    @Test
    fun archiveHidesOnlyFinishedAndSurvivesRestart() {
        val (o, _) = boot()
        val done = o.addTask("отменить и спрятать", "цель").valueOrNull()!!
        val live = o.addTask("идёт", "цель").valueOrNull()!!
        o.tick()
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, o.run(Command.Archive(live, true)).diagnostics.single().code, "идущую не прячут")
        o.run(Command.Cancel(done))
        val r = o.run(Command.ArchiveFinished())
        assertEquals(1, r.value, "спрятана одна завершённая")
        assertTrue(o.tasks.tasks.getValue(done).archived)
        assertTrue(!o.tasks.tasks.getValue(live).archived)

        // Перезапуск: архив — из журнала и снимка (dpai.tasks-state v5); повтор возвращает из архива.
        val (second, _) = boot()
        assertTrue(second.tasks.tasks.getValue(done).archived, "архив пережил перезапуск")
        assertEquals(OperationStatus.SUCCEEDED, second.run(Command.Retry(done)).status)
        assertTrue(!second.tasks.tasks.getValue(done).archived, "снова в работе — не в архиве")
        assertEquals(emptyList(), second.invariants())
    }

    @Test
    fun retryResetsCountersForFailedCancelledAndBlocked() {
        scenario = { n -> if (n <= 4) MockScenario.FAILURE else MockScenario.SUCCESS }
        val (o, runner) = boot()
        val t = o.addTask("падает", "цель", policy = TaskPolicy(maxRetries = 1, retryBackoff = 0.minutes)).valueOrNull()!!
        advance(o, runner, 4)
        val failedState = state(o, t)
        assertTrue(failedState == TaskState.FAILED || failedState == TaskState.BLOCKED, "$failedState")
        assertTrue(o.tasks.tasks.getValue(t).retries > 0)
        launches = 10 // дальше — успех
        val r = o.run(Command.Retry(t))
        assertEquals(OperationStatus.SUCCEEDED, r.status, r.diagnostics.toString())
        assertEquals(0, o.tasks.tasks.getValue(t).retries, "счётчики сброшены")
        advance(o, runner, 4)
        assertEquals(TaskState.DONE, state(o, t))

        val c = o.addTask("отменённая", "цель").valueOrNull()!!
        o.run(Command.Cancel(c))
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.Retry(c)).status)
        assertEquals(TaskState.QUEUED, state(o, c))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, o.run(Command.Retry(c, "текст")).diagnostics.single().code, "ответ — только заблокированной")
    }

    @Test
    fun blockedRetryWithTextAndWaiver() {
        scenario = { n -> if (n == 1) MockScenario.AUTH_ERROR else MockScenario.SUCCESS }
        val (o, runner) = boot(verified = false)
        val t = o.addTask("блок", "цель").valueOrNull()!!
        advance(o, runner, 2)
        assertEquals(TaskState.BLOCKED, state(o, t))
        val logic = Accounts()
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        for (slot in o.accounts.slots.keys) o.commitAccounts(logic.observeAuthStatus(o.accounts, slot, status, clock.now()).valueOrNull().orEmpty())
        val r = o.run(Command.Retry(t, "вход восстановлен, продолжай"))
        assertEquals(OperationStatus.SUCCEEDED, r.status, r.diagnostics.toString())
        advance(o, runner, 3)
        assertTrue(o.lastContext(t)!!.sources.any { "вход восстановлен" in it.content }, "ответ — в контексте хода")
        assertEquals(TaskState.COMPLETION_UNVERIFIED, state(o, t))
        assertEquals(DiagCode.VALUE_INVALID, o.run(Command.Waive(t, " ", "автор")).diagnostics.single().code, "исключение — с причиной")
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.Waive(t, "проверил руками", "автор")).status)
        assertEquals(TaskState.DONE, state(o, t))
        assertEquals("проверил руками", o.tasks.tasks.getValue(t).verification?.reason)
    }

    /**
     * Подписка у предела окна (D-30, D-40): несрочная задача ждёт сброса
     * и человек видит почему; срочная (HIGH) начинается.
     */
    @Test
    fun nearLimitDefersNormalTasksWithReasonButStartsUrgent() {
        val (o, runner) = boot()
        val logic = Accounts()
        val slot = o.accounts.slots.keys.first()
        o.commitAccounts(
            logic.observeLimit(o.accounts, slot, com.dpsmpx.dpai.accounts.LimitWindow.WEEKLY, com.dpsmpx.dpai.accounts.LimitLevel.NEAR, clock.now() + kotlin.time.Duration.parse("5d"), clock.now()).valueOrNull()!!,
        )
        val t = o.addTask("обычная", "цель").valueOrNull()!!
        o.tick()
        assertEquals(TaskState.READY, state(o, t), "у предела несрочная ждёт")
        val why = o.waitingReason(t)
        assertTrue(why != null && "близко к пределу" in why, why.toString())
        assertTrue(o.posted.any { it is com.dpsmpx.dpai.notifications.NotificationDecision.Post && it.notification.key == "task:${t.value}:waiting" }, "причина — уведомлением")
        val posts = o.posted.size
        o.tick()
        assertEquals(posts, o.posted.size, "та же причина — без нового уведомления")
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SetPriority(t, Priority.HIGH)).status)
        o.tick()
        assertEquals(TaskState.RUNNING, state(o, t), "срочная начинается и у предела")
        advance(o, runner, 2)
    }

    /**
     * Порог «у предела» в процентах (D-45): загрузка окна 85 % при пороге
     * 80 % — несрочное ждёт; порог 90 % — задача начинается сразу.
     */
    @Test
    fun nearThresholdPercentDecides() {
        val (o, runner) = boot()
        val slot = o.accounts.slots.keys.first()
        val sub = o.accounts.slots.getValue(slot).subscriptionId
        o.commitAccounts(Accounts().observeUsage(o.accounts, slot, com.dpsmpx.dpai.accounts.LimitWindow.WEEKLY, 0.85, clock.now() + kotlin.time.Duration.parse("3d"), clock.now()).valueOrNull()!!)
        assertEquals(emptyList(), Accounts().observeUsage(o.accounts, slot, com.dpsmpx.dpai.accounts.LimitWindow.WEEKLY, 0.853, null, clock.now()).valueOrNull(), "меньше процента — без события")
        val t = o.addTask("обычная", "цель").valueOrNull()!!
        o.tick()
        assertEquals(TaskState.READY, state(o, t), "85 % ≥ 80 %: ждёт")
        assertEquals(DiagCode.VALUE_INVALID, o.run(Command.SetNearPercent(sub, 30)).diagnostics.single().code)
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SetNearPercent(sub, 90)).status)
        assertEquals(90, o.accounts.subscriptions.getValue(sub).nearPercent)
        o.tick()
        assertEquals(TaskState.RUNNING, state(o, t), "85 % < 90 %: начинается")
        advance(o, runner, 2)
        // Порог и загрузка — в журнале: после перезапуска те же.
        val (second, _) = boot()
        assertEquals(90, second.accounts.subscriptions.getValue(sub).nearPercent)
        assertEquals(0.85, second.accounts.subscriptions.getValue(sub).window(com.dpsmpx.dpai.accounts.LimitWindow.WEEKLY).utilization)
    }

    /**
     * Проверить вручную (D-42): шаг проверки раз отказал (target на миг
     * недоступен) — задача без проверки; «Проверить снова» без хода
     * Claude — проверка прошла, задача DONE; причина отказа — в проблемах.
     */
    @Test
    fun manualVerifyAfterUnavailableStep() {
        var failOnce = true
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(1), host) { _, _ -> MockScenario.SUCCESS }
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        val seen = mutableListOf<com.dpsmpx.dpai.core.diagnostics.Diagnostic>()
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(9), journal, runner, ProjectConfig(project, null, dir.toString(), profile), targets, null,
            Verifier({ _, _, _ ->
                if (failOnce) {
                    failOnce = false
                    Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "обрыв")
                } else {
                    Outcome.success(Completed(0, emptyList(), emptyList()))
                }
            }, clock),
            accounts, onProblem = { seen += it },
        )
        val t = o.addTask("задача", "цель").valueOrNull()!!
        advance(o, runner, 2)
        assertEquals(TaskState.COMPLETION_UNVERIFIED, state(o, t))
        assertTrue(seen.any { it.code == DiagCode.VERIFICATION_STEP_UNAVAILABLE && it.rootCauses().any { c -> c.code == DiagCode.SSH_CONNECTION_FAILED } }, seen.toString())
        val r = o.run(Command.Verify(t))
        assertEquals(OperationStatus.SUCCEEDED, r.status, r.diagnostics.toString())
        assertEquals(TaskState.DONE, state(o, t))
        assertEquals(emptyList(), o.invariants())
        assertTrue(o.requests.requests.values.filter { it.task == t }.all { it.terminal }, "запрос о непроверенной снят")
    }

    /**
     * Настройки контура (D-42, п. 5, 6): выключенный target не получает
     * новых сессий (и после перезапуска), общий предел держит число
     * одновременных сессий, выключенный слот не арендуется.
     */
    @Test
    fun targetsSlotsAndSessionLimit() {
        val (o, runner) = boot()
        val (a, b) = targets.map { it.id }
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SetTargetEnabled(a, false)).status)
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SetSessionLimit(1)).status)
        assertEquals(DiagCode.VALUE_INVALID, o.run(Command.SetSessionLimit(0)).diagnostics.single().code)
        val tasks = (1..3).map { o.addTask("задача $it", "цель $it").valueOrNull()!! }
        o.tick()
        val running = o.sessions.sessions.values.filter { !it.terminal }
        assertEquals(1, running.size, "общий предел — одна сессия")
        assertTrue(running.all { it.target == b }, "выключенный target новых сессий не получает")

        val (second, runner2) = boot()
        assertTrue(a in second.control.disabledTargets, "выключение пережило перезапуск")
        assertEquals(1, second.control.maxSessions)
        assertEquals(OperationStatus.SUCCEEDED, second.run(Command.SetSessionLimit(null)).status)
        val slotB = second.accounts.slots.values.single { it.targetId == b }.id
        assertEquals(OperationStatus.SUCCEEDED, second.run(Command.SetSlotEnabled(slotB, false)).status)
        repeat(3) { second.tick(); for (s in host.runIds()) while (runner2.advance(s)) continue; clock.advance(1.minutes) }
        assertTrue(second.sessions.sessions.values.none { !it.terminal && it.target == b }, "выключенный слот не арендуется")
        assertEquals(OperationStatus.SUCCEEDED, second.run(Command.SetTargetEnabled(a, true)).status)
        advance(second, runner2, 4)
        assertTrue(tasks.all { state(second, it) == TaskState.DONE }, tasks.map { state(second, it) }.toString())
    }
}
