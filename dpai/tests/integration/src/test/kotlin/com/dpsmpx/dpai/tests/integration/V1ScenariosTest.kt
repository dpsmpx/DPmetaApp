package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCli
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.RunnerState
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.control.ControlSnapshot
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.orchestrator.git.WorktreesProjection
import com.dpsmpx.dpai.orchestrator.verification.ProcessStepRunner
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Сценарии V1 1–4 (`docs/direction/V1_SCOPE.md`, §1; критерии этапа 14):
 * сквозной контур без вмешательства для разрешённых операций, на
 * `MockRunner` вместо Claude, с настоящим `git` и настоящими шагами
 * проверки. Слияние в ветку интеграции — только по одобрению.
 */
class V1ScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(101))
    private val host = LocalProcessHost()
    private val scenarios = HashMap<String, List<MockScenario>>()
    private lateinit var orchestrator: Orchestrator
    private lateinit var runner: MockRunner
    /** Запросы ходов, которые оркестратор передал Claude (D-38). */
    private val started = mutableListOf<ClaudeRequest>()
    private lateinit var repo: Repository
    private lateinit var journal: EventJournal

    private fun sh(vararg argv: String) {
        val r = host.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    /**
     * Проект с репозиторием, [slots] слотов на своих targets: одной подписки
     * или, при [ownSubscriptions], у каждого слота — своя подписка, которая
     * берёт задачи упёршихся в предел (D-56).
     */
    private fun setUp(slots: Int, perTarget: Int = 1, ownSubscriptions: Boolean = false) {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path
        sh("init", "-q", "-b", "main", root)
        sh("-C", root, "config", "user.email", "dpai-test@example.org")
        sh("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        sh("-C", root, "add", "README.md")
        sh("-C", root, "commit", "-q", "-m", "начало")
        repo = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), root)

        val accountsLogic = Accounts()
        var accounts = AccountsState.EMPTY
        val shared = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, accountsLogic.registerSubscription(accounts, shared, "основная", clock.now(), maxParallelSessions = 64).valueOrNull()!!)
        val targets = mutableListOf<TargetSlot>()
        repeat(slots) { i ->
            val sub = if (!ownSubscriptions) {
                shared
            } else {
                ids.next(IdKinds.SUBSCRIPTION).also { s ->
                    accounts = Accounts.applyAll(accounts, accountsLogic.registerSubscription(accounts, s, "подписка $i", clock.now(), maxParallelSessions = 64, rotationAllowed = true).valueOrNull()!!)
                }
            }
            val target = ids.next(IdKinds.TARGET)
            targets += TargetSlot(target, emptySet(), perTarget)
            val slot = ids.next(IdKinds.ACCOUNT)
            accounts = Accounts.applyAll(accounts, accountsLogic.registerSlot(accounts, slot, sub, target, "/home/dev/.claude-$i", clock.now()).valueOrNull()!!)
            val email = if (ownSubscriptions) "a$i@example.org" else "a@example.org"
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", email), "max")
            accounts = Accounts.applyAll(accounts, accountsLogic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        }
        val java = File(File(System.getProperty("java.home"), "bin"), if (File.separatorChar == '\\') "java.exe" else "java").path
        val profile = VerificationProfile(com.dpsmpx.dpai.core.ids.IdKinds.PROJECT.let { ids.next(it) }, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("сборка", listOf(java, "-version")))))
        runner = MockRunner.create(clock, RandomSource.seeded(102)) { session, turn ->
            val title = orchestrator.sessions.sessions[session]?.let { orchestrator.tasks.tasks[it.task]?.title }
            val plan = scenarios[title] ?: listOf(MockScenario.SUCCESS)
            plan[(attemptOf(session) - 1).coerceAtMost(plan.size - 1)].also { check(turn >= 1) }
        }
        boot = {
            journal = (EventJournal.open(NioVfs(dir), "data/journal.log") as Outcome.Success).value
            val git = GitService(host, GitCommands(ApprovalLedger()), clock)
            orchestrator = Orchestrator(
                clock, ids, RandomSource.seeded(103), journal, Recording(runner, started),
                ProjectConfig(profile.project, repo, dir.resolve("trees").toString(), profile),
                targets, git, Verifier(ProcessStepRunner(host), clock), accounts,
            )
        }
        boot()
    }

    /** Создать контур заново над тем же журналом, репозиторием и target — перезапуск приложения. */
    private var boot: () -> Unit = {}

    /** Номер попытки задачи для сессии: первая сессия задачи — 1, следующая — 2… */
    private fun attemptOf(session: com.dpsmpx.dpai.core.ids.SessionId): Int {
        val task = orchestrator.sessions.sessions.getValue(session).task
        return orchestrator.sessions.sessions.values.filter { it.task == task }.sortedBy { it.id.value }.indexOfFirst { it.id == session } + 1
    }

    private fun task(title: String, vararg plan: MockScenario): TaskId {
        if (plan.isNotEmpty()) scenarios[title] = plan.toList()
        return orchestrator.addTask(title, "цель: $title").valueOrNull()!!
    }

    /** Такты, пока всё не устоится; между тактами — минута (паузы повторов). */
    private fun runUntilQuiet(maxTicks: Int = 30) {
        repeat(maxTicks) {
            orchestrator.tick()
            orchestrator.sessions.sessions.keys.forEach { s -> while (runner.advance(s)) continue }
            orchestrator.tick()
            // Инварианты ТЗ, §118 — после каждого такта, а не только в конце.
            assertEquals(emptyList(), orchestrator.invariants(), "инварианты после такта")
            clock.advance(2.minutes)
        }
    }

    private fun state(t: TaskId) = orchestrator.tasks.tasks.getValue(t).state

    @Test
    fun scenario1ParallelDevelopmentInIsolatedWorktrees() {
        setUp(slots = 10)
        val all = (1..10).map { task("задача $it", MockScenario.SLOW_OUTPUT) }
        orchestrator.tick()
        orchestrator.tick()
        val running = orchestrator.sessions.sessions.values.filter { it.state == SessionState.RUNNING }
        assertEquals(10, running.size, "10 задач — 10 сессий")
        assertTrue(started.size >= 10 && started.all { it.permissionMode == ClaudeCodeCli.TURN_PERMISSION_MODE }, "ход — с режимом разрешений D-38")
        assertEquals(10, orchestrator.dashboard().activeSessions)
        val owners = orchestrator.worktrees.worktrees.values.filter { it.owner != null }
        assertEquals(10, owners.map { it.path }.toSet().size, "у каждой сессии своё дерево")
        assertEquals(running.map { it.id }.toSet(), owners.map { it.owner }.toSet())
        // Владельцы деревьев — в журнале: после перезапуска те же (ТЗ, §118).
        assertEquals(orchestrator.worktrees, StateRecovery(WorktreesProjection).replayAll(journal))
        runUntilQuiet()
        assertEquals(orchestrator.worktrees, StateRecovery(WorktreesProjection).replayAll(journal))
        assertTrue(all.all { state(it) == TaskState.DONE }, all.map(::state).toString())
        assertTrue(orchestrator.problems.isEmpty(), orchestrator.problems.toString())
    }

    @Test
    fun scenario2AutomaticContinuationAfterLimit() {
        setUp(slots = 2)
        val t = task("продолжение", MockScenario.PARTIAL_COMPLETION, MockScenario.SUCCESS)
        runUntilQuiet()
        assertEquals(TaskState.DONE, state(t))
        val sessions = orchestrator.sessions.sessions.values.filter { it.task == t }.sortedBy { it.id.value }
        assertEquals(2, sessions.size)
        assertEquals(sessions[0].id, sessions[1].continues, "вторая сессия продолжает первую")
        assertEquals(sessions[0].provider, sessions[1].provider, "та же сессия Claude (--resume)")
        assertTrue(orchestrator.handoffs.latest(t) != null, "handoff создан")
    }

    @Test
    fun taskAtSubscriptionLimitMovesToAnotherSubscriptionWithHandoff() {
        // D-56: подписка первого слота упёрлась в предел — задача не ждёт сброса, а продолжает на слоте
        // другой подписки свежей сессией с handoff; переход записан в журнале аккаунтов.
        setUp(slots = 2, ownSubscriptions = true)
        val t = task("передача", MockScenario.RATE_LIMIT, MockScenario.SUCCESS)
        runUntilQuiet()
        assertEquals(TaskState.DONE, state(t))
        val sessions = orchestrator.sessions.sessions.values.filter { it.task == t }.sortedBy { it.id.value }
        assertEquals(2, sessions.size, sessions.toString())
        val subOf = { s: com.dpsmpx.dpai.sessions.SessionRecord -> orchestrator.accounts.slots.getValue(s.slot).subscriptionId }
        assertTrue(subOf(sessions[0]) != subOf(sessions[1]), "вторая сессия — на другой подписке")
        assertTrue(sessions[0].provider != sessions[1].provider, "другой аккаунт — свежая сессия, не --resume")
        assertTrue(orchestrator.handoffs.latest(t) != null, "контекст передан handoff")
        assertEquals(1, orchestrator.accounts.switches[t].orEmpty().size, "переход между подписками записан")
        assertEquals(com.dpsmpx.dpai.contexts.HandoffReason.ACCOUNT_EXHAUSTED, orchestrator.handoffs.all().single { it.session == sessions[0].id }.reason, "предел — причина handoff")
    }

    @Test
    fun taskChatHoldsRequestsModelTextsAndEventsInOrder() {
        // D-59: лента чата задачи — цель задачи, событие начала на слоте, тексты модели, запрос человека;
        // без вызовов инструментов.
        setUp(slots = 1)
        val t = task("чат", MockScenario.SUCCESS)
        runUntilQuiet()
        val chat = orchestrator.chat(t)
        assertEquals(com.dpsmpx.dpai.orchestrator.ChatRole.USER, chat.first().role)
        assertTrue(chat.first().text.startsWith("чат"), chat.first().text)
        assertTrue(chat.any { it.role == com.dpsmpx.dpai.orchestrator.ChatRole.EVENT && it.text.startsWith("попытка 1") }, chat.toString())
        assertTrue(chat.any { it.role == com.dpsmpx.dpai.orchestrator.ChatRole.ASSISTANT }, chat.toString())
        assertTrue(chat.none { "tool_use" in it.text || "\"name\"" in it.text }, "инструменты в ленту не попадают")
        assertEquals(chat.sortedBy { it.at.epochMillis }, chat, "по порядку")
    }

    @Test
    fun scenario3RecoveryAfterFailure() {
        setUp(slots = 1)
        val t = task("падение", MockScenario.CRASH, MockScenario.SUCCESS)
        runUntilQuiet()
        assertEquals(TaskState.DONE, state(t))
        val sessions = orchestrator.sessions.sessions.values.filter { it.task == t }.sortedBy { it.id.value }
        assertEquals(listOf(SessionState.FAILED, SessionState.COMPLETED), sessions.map { it.state })
        assertEquals(1, orchestrator.tasks.tasks.getValue(t).retries)
    }

    @Test
    fun scenario4CompletionVerifiedCommittedAndMergedOnlyAfterApproval() {
        setUp(slots = 1)
        val t = task("фича", MockScenario.SLOW_OUTPUT)
        orchestrator.tick()
        // «Claude» правит файл в своём дереве.
        val tree = orchestrator.worktrees.worktrees.values.single { it.owner != null }
        File(tree.path, "feature.txt").writeText("новая возможность\n")
        runUntilQuiet(3)
        assertEquals(TaskState.DONE, state(t))
        assertEquals("PASSED", orchestrator.tasks.tasks.getValue(t).verification!!.verdict)
        val log = host.run(ProcessSpec(listOf("git", "-C", tree.path, "log", "--format=%s", "-n", "1")), 1.minutes).valueOrNull()!!.stdout
        assertEquals(listOf("dpai: фича"), log, "коммит в ветке задачи")
        // Слияние в main — чувствительно: ждёт ответа человека на запрос (D-49).
        assertFalse(File(repo.root, "feature.txt").exists(), "без одобрения main не тронут")
        val request = mergeRequest()
        assertEquals(1, orchestrator.dashboard().waitingApproval.orNull())
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(request.id, "merge", null, request.hash, "автор").status)
        assertTrue(File(repo.root, "feature.txt").exists(), "после одобрения — слито")
        assertEquals(listOf(t), orchestrator.merged)
        assertEquals(emptyList(), orchestrator.invariants(), "слияние — с записью одобрения")
    }

    /**
     * Сценарий 4 с перезапуском между запросом одобрения и ответом (ТЗ, §54,
     * §68): запрос переживает перезапуск, старая кнопка сливает ветку; после
     * ещё одного перезапуска задача слита, а не предлагается снова.
     */
    @Test
    fun scenario4ApprovalSurvivesRestart() {
        setUp(slots = 1)
        val t = task("фича", MockScenario.SLOW_OUTPUT)
        orchestrator.tick()
        val tree = orchestrator.worktrees.worktrees.values.single { it.owner != null }
        File(tree.path, "feature.txt").writeText("новая возможность\n")
        runUntilQuiet(3)
        val request = mergeRequest()

        boot()
        assertEquals(1, orchestrator.dashboard().waitingApproval.orNull(), "запрос ждёт ответа и после перезапуска")
        assertEquals(request.id, mergeRequest().id, "тот же запрос, не второй")
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(request.id, "merge", null, request.hash, "автор").status)
        assertTrue(File(repo.root, "feature.txt").exists())

        boot()
        assertEquals(listOf(t), orchestrator.merged, "слитое — из журнала")
        assertEquals(0, orchestrator.dashboard().waitingApproval.orNull())
        assertEquals(emptyList(), orchestrator.invariants())
    }

    /**
     * Сбой слияния (A-30): запрос не теряется — приходит новый с причиной и
     * «Повторить слияние»; «Не сливать» снимает слияние, main не тронут.
     */
    @Test
    fun scenario4MergeFailureAsksAgainAndDeclineLeavesMain() {
        setUp(slots = 1)
        val t = task("фича", MockScenario.SLOW_OUTPUT)
        orchestrator.tick()
        val tree = orchestrator.worktrees.worktrees.values.single { it.owner != null }
        File(tree.path, "feature.txt").writeText("новая возможность\n")
        runUntilQuiet(3)
        val first = mergeRequest()
        // Ветку задачи удалили до ответа — git merge откажет.
        host.run(ProcessSpec(listOf("git", "-C", repo.root, "worktree", "remove", "--force", tree.path)), 1.minutes)
        host.run(ProcessSpec(listOf("git", "-C", repo.root, "branch", "-D", tree.branch)), 1.minutes)
        assertEquals(OperationStatus.FAILED, orchestrator.answer(first.id, "merge", null, first.hash, "автор").status)
        val again = mergeRequest()
        assertTrue(again.id != first.id && "Слияние не удалось" in again.body, again.body)
        assertEquals("Повторить слияние", again.options.first().label)
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(again.id, "decline", null, again.hash, "автор").status)
        assertEquals(0, orchestrator.dashboard().waitingApproval.orNull())
        assertTrue(orchestrator.pendingRequests().none { it.purpose == "task.merge" }, "отказ — запрос не возвращается")
        assertFalse(File(repo.root, "feature.txt").exists())
        assertEquals(emptyList<TaskId>(), orchestrator.merged)
    }

    /** Запрос истёк, слияние ждёт (A-51): такт спрашивает снова, а не теряет слияние. */
    @Test
    fun expiredMergeRequestIsAskedAgain() {
        setUp(slots = 1)
        val t = task("фича", MockScenario.SLOW_OUTPUT)
        orchestrator.tick()
        File(orchestrator.worktrees.worktrees.values.single { it.owner != null }.path, "feature.txt").writeText("новая возможность\n")
        runUntilQuiet(3)
        val first = mergeRequest()
        clock.advance(25.hours)
        orchestrator.tick()
        val again = mergeRequest()
        assertTrue(again.id != first.id, "истёкший — заменён новым")
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(again.id, "merge", null, again.hash, "автор").status)
        assertEquals(listOf(t), orchestrator.merged)
    }

    /**
     * Изменения задачи (D-42, п. 3): до слияния видно, что задача поменяла, —
     * незакоммиченное и новые файлы, пока идёт ход; что принесёт слияние —
     * после коммита; после слияния дерево убрано, ветке нечего принести.
     */
    @Test
    fun taskChangesShowWhatTheMergeWouldBring() {
        setUp(slots = 1)
        val t = task("фича", MockScenario.SLOW_OUTPUT)
        val before = orchestrator.taskChanges(t).valueOrNull()!!
        assertEquals("task/${t.value}", before.branch)
        assertTrue(before.empty && before.committed == null && before.uncommitted == null, "до запуска ветки и дерева нет: $before")

        orchestrator.tick()
        val tree = orchestrator.worktrees.worktrees.values.single { it.owner != null }
        File(tree.path, "feature.txt").writeText("новая возможность\n")
        File(tree.path, "README.md").appendText("правка задачи\n")
        val working = orchestrator.taskChanges(t).valueOrNull()!!
        assertEquals(emptyList(), working.committed, "коммитов в ветке ещё нет")
        assertTrue(working.uncommitted!!.any { it == "+правка задачи" }, working.uncommitted.toString())
        assertEquals(listOf("feature.txt"), working.untracked)
        // Канал `dpai` (D-47): нет части — нет поля; пустая часть — пустой список.
        val json = CanonicalJson.write(ControlSnapshot.changes(working, emptyList()))
        assertTrue(""""committed":[]""" in json && """"untracked":["feature.txt"]""" in json && "+правка задачи" in json, json)
        assertFalse("committed" in CanonicalJson.write(ControlSnapshot.changes(before, emptyList())))

        runUntilQuiet(3)
        assertEquals(TaskState.DONE, state(t))
        // Расход задачи в снимке канала (D-42, п. 4): ход был — поле есть.
        assertTrue(""""usage":{""" in CanonicalJson.write(ControlSnapshot.of(orchestrator)))
        val done = orchestrator.taskChanges(t)
        assertTrue(done.diagnostics.isEmpty(), done.diagnostics.toString())
        val committed = done.valueOrNull()!!.committed!!
        assertTrue(committed.any { it.contains("feature.txt") && it.contains("|") }, "сводка --stat: $committed")
        assertTrue("+новая возможность" in committed && "+правка задачи" in committed, committed.toString())
        assertEquals(emptyList(), done.valueOrNull()!!.uncommitted, "всё закоммичено")
        assertEquals("main", done.valueOrNull()!!.base)
        assertFalse(done.valueOrNull()!!.truncated)

        val request = mergeRequest()
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(request.id, "merge", null, request.hash, "автор").status)
        val merged = orchestrator.taskChanges(t).valueOrNull()!!
        assertEquals(null, merged.uncommitted, "дерево убрано после слияния (A-65)")
        assertEquals(emptyList(), merged.committed, "слитой ветке нечего принести")

        val unknown = orchestrator.taskChanges(com.dpsmpx.dpai.core.ids.IdKinds.TASK.let { ids.next(it) })
        assertEquals(DiagCode.ENTITY_NOT_FOUND, (unknown as Outcome.Failure).errors.single().code)
    }

    private fun mergeRequest() = orchestrator.pendingRequests().single { it.purpose == "task.merge" }
}

/** Раннер, запоминающий запросы ходов: что оркестратор передаёт Claude. */
private class Recording(private val inner: ClaudeRunner, private val requests: MutableList<ClaudeRequest>) : ClaudeRunner by inner {
    override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
        requests += request
        return inner.start(session, request, provider, resume)
    }
}
