package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.operation.OperationStatus
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.AgentClient
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
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.control.ControlSnapshot
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.orchestrator.review.Review
import com.dpsmpx.dpai.orchestrator.verification.ProcessStepRunner
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.FindingSeverity
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.ReviewVerdict
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Ревью задачи (D-59) сквозь контур: настоящий `git` и шаги проверки,
 * `MockRunner` вместо Claude. Роли — отдельными сессиями после проверки и
 * коммита, по одной за раз; блокирующее — повтор с выжимкой; без итога —
 * повтор, затем запрос человеку; перезапуск во время ревью ревью не теряет.
 */
class ReviewFlowTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(159))
    private val host = LocalProcessHost()
    private lateinit var orchestrator: Orchestrator
    private lateinit var runner: MockRunner
    private lateinit var repo: Repository
    private val started = mutableListOf<Pair<SessionId, ClaudeRequest>>()

    /** Сценарии работы задачи по попыткам и ответы ревью по ролям — по порядку запусков. */
    private val work = ArrayDeque<MockScenario>()
    private val reviews = HashMap<String, ArrayDeque<MockScenario>>()
    private var boot: () -> Unit = {}

    private fun sh(vararg argv: String) {
        val r = host.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    private fun setUp() {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path
        sh("init", "-q", "-b", "main", root)
        sh("-C", root, "config", "user.email", "dpai-test@example.org")
        sh("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("проект\n")
        sh("-C", root, "add", "README.md")
        sh("-C", root, "commit", "-q", "-m", "начало")
        repo = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), root)
        val logic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        val target = ids.next(IdKinds.TARGET)
        val slot = ids.next(IdKinds.ACCOUNT)
        accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, target, "/home/dev/.claude-0", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        val java = File(File(System.getProperty("java.home"), "bin"), if (File.separatorChar == '\\') "java.exe" else "java").path
        val profile = VerificationProfile(ids.next(IdKinds.PROJECT), mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("сборка", listOf(java, "-version")))))
        runner = MockRunner.create(clock, RandomSource.seeded(160)) { session, _ ->
            val role = orchestrator.sessions.sessions[session]?.review
            if (role == null) work.removeFirstOrNull() ?: MockScenario.SUCCESS else reviews[role]?.removeFirstOrNull() ?: MockScenario.REVIEW_PASS
        }
        val accountsAtStart = accounts
        boot = {
            val journal = (EventJournal.open(NioVfs(dir), "data/journal.log") as Outcome.Success).value
            orchestrator = Orchestrator(
                clock, ids, RandomSource.seeded(161), journal, ReviewRecording(runner, started),
                ProjectConfig(profile.project, repo, dir.resolve("trees").toString(), profile),
                listOf(TargetSlot(target, emptySet(), 2)), GitService(host, GitCommands(ApprovalLedger()), clock), Verifier(ProcessStepRunner(host), clock), accountsAtStart,
            )
        }
        boot()
    }

    /** Задача с ревью; «Claude» её первой попытки правит файл в своём дереве. */
    private fun reviewedTask(vararg roles: ReviewRole): TaskId {
        val t = orchestrator.addTask("фича", "цель: фича", policy = TaskPolicy(retryBackoff = 0.minutes, reviews = roles.toSet())).valueOrNull()!!
        orchestrator.tick()
        val tree = orchestrator.worktrees.worktrees.values.single { it.owner != null }
        File(tree.path, "feature.txt").writeText("новая возможность\n")
        return t
    }

    private fun runUntilQuiet(maxTicks: Int = 12) {
        repeat(maxTicks) {
            orchestrator.tick()
            orchestrator.sessions.sessions.keys.forEach { s -> while (runner.advance(s)) continue }
            orchestrator.tick()
            assertEquals(emptyList(), orchestrator.invariants(), "инварианты после такта")
            clock.advance(2.minutes)
        }
    }

    private fun task(t: TaskId) = orchestrator.tasks.tasks.getValue(t)

    /** Команда, как с экрана (D-42): через очередь контура. */
    private fun Orchestrator.run(c: Command) = submit(c).also { runCommands() }.get()

    private fun reviewRequests() = started.filter { (s, _) -> orchestrator.sessions.sessions[s]?.review != null }

    @Test
    fun rolesRunOneByOneReadOnlyAndTaskIsDoneAfterThem() {
        setUp()
        work += MockScenario.SLOW_OUTPUT
        val t = reviewedTask(ReviewRole.SECURITY, ReviewRole.CODE)
        runUntilQuiet()
        assertEquals(TaskState.DONE, task(t).state)
        // Роли — по порядку, каждая своей сессией после коммита; запросы — только чтение и ответ по схеме.
        assertEquals(listOf("CODE", "SECURITY"), reviewRequests().map { (s, _) -> orchestrator.sessions.sessions.getValue(s).review })
        val reqs = reviewRequests().map { it.second }
        assertTrue(reqs.all { it.tools == Review.TOOLS && it.jsonSchema == Review.SCHEMA && ClaudeCodeCli.SUBAGENT_TOOL in it.disallowedTools }, reqs.toString())
        assertTrue(reqs.all { "feature.txt" in it.prompt && "<<<DPAI-DATA source=task-diff" in it.prompt }, "изменения задачи — блоком данных")
        val code = task(t).reviews.getValue(ReviewRole.CODE)
        assertEquals(ReviewVerdict.PASSED, code.verdict)
        assertEquals(FindingSeverity.MAJOR, code.findings.single().severity, "серьёзное замечание видно, но DONE не держит")
        assertEquals(1, orchestrator.pendingRequests().count { it.purpose == "task.merge" }, "после ревью — запрос слияния")
        // Канал `dpai` (D-47): роли и итоги — в состоянии, находки — по задаче.
        val row = (ControlSnapshot.of(orchestrator)["tasks"] as JsonArray).items.map { it as JsonObject }.single { (it["id"] as JsonString).value == t.value }
        assertEquals(JsonObject.of("CODE" to JsonString("PASSED"), "SECURITY" to JsonString("PASSED")), row["verdicts"])
        val reviews = assertNotNull(ControlSnapshot.reviews(orchestrator, t))["reviews"] as JsonArray
        assertEquals(JsonString("MAJOR"), (((reviews.items[0] as JsonObject)["findings"] as JsonArray).items.single() as JsonObject)["severity"])
        val log = host.run(ProcessSpec(listOf("git", "-C", orchestrator.worktrees.worktrees.values.single { !it.removed }.path, "log", "--format=%s", "-n", "1")), 1.minutes).valueOrNull()!!.stdout
        assertEquals(listOf("dpai: фича"), log, "ревью — после коммита")
    }

    @Test
    fun blockingReviewSendsTaskBackWithDigestAndContinuesTheWorkSession() {
        setUp()
        work += listOf(MockScenario.SLOW_OUTPUT, MockScenario.SUCCESS)
        reviews["CODE"] = ArrayDeque(listOf(MockScenario.REVIEW_BLOCK, MockScenario.REVIEW_PASS))
        val t = reviewedTask(ReviewRole.CODE)
        runUntilQuiet()
        assertEquals(TaskState.DONE, task(t).state)
        assertEquals(1, task(t).retries, "блокирующее ревью — попытка")
        val workSessions = orchestrator.sessions.sessions.values.filter { it.task == t && it.review == null }.sortedBy { it.id.value }
        assertEquals(2, workSessions.size)
        assertEquals(workSessions[0].id, workSessions[1].continues, "повтор продолжает рабочую сессию, а не сессию ревью")
        val retry = started.single { it.first == workSessions[1].id }.second
        assertTrue("деление на ноль" in retry.prompt, "выжимка ревью — в prompt повтора: ${retry.prompt.take(400)}")
        assertEquals(2, orchestrator.sessions.sessions.values.count { it.task == t && it.review == "CODE" })
        // Коммит задачи (ТЗ, §75): хеш — тот, что в ветке задачи, в handoff повтора и после перезапуска.
        val tree = orchestrator.worktrees.worktrees.values.single { it.branch.endsWith(t.value) }
        val head = host.run(ProcessSpec(listOf("git", "-C", tree.path, "rev-parse", "HEAD")), 1.minutes).valueOrNull()!!.stdout.single().trim()
        assertEquals(head, orchestrator.mergesState.commits[t]?.commit)
        assertEquals(head, orchestrator.handoffs.all().single { it.task == t && it.key.contains(":review:") }.commit, "handoff повтора знает коммит, который ревьюили")
        boot()
        assertEquals(head, orchestrator.mergesState.commits[t]?.commit, "коммит — из журнала")
    }

    @Test
    fun reviewWithoutAnswerIsRetriedThenAskedAndRetriedOnAnswer() {
        setUp()
        work += MockScenario.SLOW_OUTPUT
        reviews["CODE"] = ArrayDeque(listOf(MockScenario.REVIEW_NO_SCHEMA, MockScenario.REVIEW_NO_SCHEMA, MockScenario.REVIEW_PASS))
        val t = reviewedTask(ReviewRole.CODE)
        runUntilQuiet()
        assertEquals(TaskState.VERIFIED, task(t).state, "без итога — не DONE")
        assertEquals(2, orchestrator.sessions.sessions.values.count { it.task == t && it.review != null }, "сбой — один повтор, не больше")
        val request = assertNotNull(orchestrator.pendingRequests().singleOrNull { it.purpose == "task.review" })
        assertTrue("не по схеме" in request.body, request.body)
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(request.id, "retry", null, request.hash, "автор").status)
        runUntilQuiet()
        assertEquals(TaskState.DONE, task(t).state)
        assertEquals(3, orchestrator.sessions.sessions.values.count { it.task == t && it.review != null })
    }

    @Test
    fun reviewWithoutAnswerCanBeAcceptedWithoutIt() {
        setUp()
        work += MockScenario.SLOW_OUTPUT
        reviews["SECURITY"] = ArrayDeque(listOf(MockScenario.REVIEW_NO_SCHEMA, MockScenario.REVIEW_NO_SCHEMA))
        val t = reviewedTask(ReviewRole.SECURITY)
        runUntilQuiet()
        val request = assertNotNull(orchestrator.pendingRequests().singleOrNull { it.purpose == "task.review" })
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.answer(request.id, "waive", null, request.hash, "автор").status)
        assertEquals(TaskState.DONE, task(t).state)
        val waived = task(t).reviews.getValue(ReviewRole.SECURITY)
        assertEquals(ReviewVerdict.WAIVED, waived.verdict)
        assertEquals("автор", waived.by)
    }

    @Test
    fun restartDuringReviewDoesNotLoseIt() {
        setUp()
        work += MockScenario.SLOW_OUTPUT
        reviews["CODE"] = ArrayDeque(listOf(MockScenario.SLOW_OUTPUT))
        val t = reviewedTask(ReviewRole.CODE)
        // Доводим до запуска ревью: работа, проверка, коммит, сессия ревью начата.
        repeat(6) {
            if (orchestrator.sessions.sessions.values.any { it.task == t && it.review != null }) return@repeat
            orchestrator.tick()
            orchestrator.sessions.sessions.values.filter { it.review == null }.forEach { s -> while (runner.advance(s.id)) continue }
            orchestrator.tick()
            clock.advance(2.minutes)
        }
        assertEquals(TaskState.VERIFIED, task(t).state)
        assertTrue(orchestrator.sessions.sessions.values.any { it.task == t && it.review == "CODE" && !it.terminal }, "ревью идёт")
        boot()
        orchestrator.recover { AgentClient(runner.host, listOf("dpai"), null, "target mock") }
        runUntilQuiet()
        // Ответ того хода — без схемы (SLOW_OUTPUT): ревью без итога, повтор — и ответ по схеме.
        assertEquals(TaskState.DONE, task(t).state)
        assertEquals(2, orchestrator.sessions.sessions.values.count { it.task == t && it.review != null })
    }

    /** Роли в `task.policy` (D-59): без поля — как были, пустой набор снимает, у `TRIVIAL` — отказ, а не тихое снятие. */
    @Test
    fun policyKeepsRolesUnlessGiven() {
        setUp()
        val t = orchestrator.addTask("правка", "цель", policy = TaskPolicy(reviews = setOf(ReviewRole.CODE))).valueOrNull()!!
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.run(Command.SetPolicy(t, TaskClass.ARCHITECTURE, null, null, null)).status)
        assertEquals(setOf(ReviewRole.CODE), task(t).policy.reviews, "клиент без ролей их не снимает")
        assertEquals(DiagCode.VALUE_INVALID, orchestrator.run(Command.SetPolicy(t, TaskClass.TRIVIAL, null, null, null)).diagnostics.single().code)
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.run(Command.SetPolicy(t, TaskClass.TRIVIAL, null, null, null, emptySet())).status)
        assertEquals(emptySet(), task(t).policy.reviews)
    }

    /**
     * «Остановить ход» у сессии ревью ставит задачу на паузу (D-42): сессия
     * снята без handoff — задача остаётся проверенной, а не идёт на новую
     * попытку работы; ревью не перезапускается, пока не «Продолжить».
     */
    @Test
    fun stoppedReviewWaitsForResume() {
        setUp()
        work += MockScenario.SLOW_OUTPUT
        reviews["CODE"] = ArrayDeque(listOf(MockScenario.SLOW_OUTPUT, MockScenario.REVIEW_PASS))
        val t = reviewedTask(ReviewRole.CODE)
        repeat(6) {
            if (orchestrator.sessions.sessions.values.any { it.task == t && it.review != null }) return@repeat
            orchestrator.tick()
            orchestrator.sessions.sessions.values.filter { it.review == null }.forEach { s -> while (runner.advance(s.id)) continue }
            orchestrator.tick()
            clock.advance(2.minutes)
        }
        val review = orchestrator.sessions.sessions.values.single { it.task == t && it.review == "CODE" }
        // Ход ревью пошёл (первая строка вывода) — его и останавливаем.
        runner.advance(review.id)
        orchestrator.tick()
        assertEquals(com.dpsmpx.dpai.core.state.SessionState.RUNNING, orchestrator.sessions.sessions.getValue(review.id).state)
        // Проверенную задачу не отменяют — и отказ не снимает идущее ревью.
        assertEquals(OperationStatus.FAILED, orchestrator.run(Command.Cancel(t)).status)
        assertEquals(com.dpsmpx.dpai.core.state.SessionState.RUNNING, orchestrator.sessions.sessions.getValue(review.id).state)
        val stop = orchestrator.run(Command.StopSession(review.id))
        assertEquals(OperationStatus.SUCCEEDED, stop.status, stop.diagnostics.toString())
        runUntilQuiet(4)
        assertEquals(TaskState.VERIFIED, task(t).state)
        assertTrue(task(t).held)
        assertEquals(1, orchestrator.sessions.sessions.values.count { it.task == t && it.review != null }, "на паузе ревью не перезапускается")
        assertEquals(OperationStatus.SUCCEEDED, orchestrator.run(Command.Resume(t)).status)
        runUntilQuiet()
        assertEquals(TaskState.DONE, task(t).state)
        assertEquals(2, orchestrator.sessions.sessions.values.count { it.task == t && it.review != null })
    }
}

private class ReviewRecording(private val inner: ClaudeRunner, private val requests: MutableList<Pair<SessionId, ClaudeRequest>>) : ClaudeRunner by inner {
    override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
        requests += session to request
        return inner.start(session, request, provider, resume)
    }
}
