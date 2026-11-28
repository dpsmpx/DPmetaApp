package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.claude.RunnerState
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.efficiency.EfficiencyPolicy
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.StepRunner
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** Старт хода глазами Claude: сессия, запрос, сессия провайдера, `--resume` ли. */
data class Start(val session: SessionId, val request: ClaudeRequest, val provider: ProviderSessionId?, val resume: Boolean)

/** Что стенд даёт настройке аккаунтов: логика, состояние со слотом Claude, его target и слот. */
data class AccountsHook(
    val logic: Accounts,
    val state: AccountsState,
    val target: com.dpsmpx.dpai.core.ids.ExecutionTargetId,
    val claudeSlot: com.dpsmpx.dpai.core.ids.AccountId,
    val ids: IdGenerator,
    val now: com.dpsmpx.dpai.core.clock.Timestamp,
)

/**
 * Стенд слоя D-40 (`docs/EFFICIENCY.md`): проект с настоящим Git, одна
 * подписка со слотом, заглушка Claude ([wrap] — обёртка поверх неё, например
 * модель расхода) и проверка по правилу [verify]: номер проверки задачи и
 * последний prompt задачи → строки упавшего вывода или `null` — прошла.
 * [scenario] — сценарий хода по номеру попытки задачи.
 */
class EfficiencyHarness(
    root: Path,
    efficiency: EfficiencyPolicy,
    sources: (ProjectId) -> List<ContextSource> = { emptyList() },
    private val verify: (attempt: Int, lastPrompt: String) -> List<String>? = { _, _ -> null },
    private val scenario: (attempt: Int) -> MockScenario = { MockScenario.SUCCESS },
    sessionsPerTarget: Int = 2,
    /** Аккаунты сверх слота Claude (D-100): подписки Codex и Gemini, пределы — до первого такта. */
    accountsHook: (AccountsHook) -> AccountsState = { it.state },
    automationGate: com.dpsmpx.dpai.automation.AutomationGate = com.dpsmpx.dpai.automation.AutomationGate.NONE,
    // Последним: вызовы с замыкающей лямбдой оборачивают исполнителя.
    wrap: (ClaudeRunner) -> ClaudeRunner = { it },
) {
    val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(301))
    private val host = LocalProcessHost()
    val starts = mutableListOf<Start>()
    private val verifications = HashMap<TaskId, Int>()
    val orchestrator: Orchestrator
    private val runner: MockRunner

    private fun sh(vararg argv: String) {
        val r = host.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    init {
        val dir = Files.createDirectories(root)
        val repoDir = dir.resolve("repo").toFile().apply { mkdirs() }.path
        sh("init", "-q", "-b", "main", repoDir)
        sh("-C", repoDir, "config", "user.email", "dpai-test@example.org")
        sh("-C", repoDir, "config", "user.name", "dpai test")
        File(repoDir, "README.md").writeText("проект\n")
        sh("-C", repoDir, "add", "README.md")
        sh("-C", repoDir, "commit", "-q", "-m", "начало")
        val repo = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), repoDir)
        val logic = Accounts()
        var accounts = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 16).valueOrNull()!!)
        val target = ids.next(IdKinds.TARGET)
        val slot = ids.next(IdKinds.ACCOUNT)
        accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, target, "/home/dev/.claude", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
        accounts = accountsHook(AccountsHook(logic, accounts, target, slot, ids, clock.now()))
        val project = ids.next(IdKinds.PROJECT)
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("тесты", listOf("gradle", "test")))))
        val steps = StepRunner { _, worktree, _ ->
            val task = IdKinds.TASK.parse(File(worktree).name).valueOrNull()!!
            val n = verifications.merge(task, 1, Int::plus)!!
            val failed = verify(n, lastPrompt(task))
            Outcome.success(if (failed == null) Completed(0, listOf("BUILD SUCCESSFUL"), emptyList()) else Completed(1, failed, emptyList()))
        }
        runner = MockRunner.create(clock, RandomSource.seeded(302)) { session, _ -> scenario(attemptOf(session)) }
        val journal = (EventJournal.open(NioVfs(dir), "data/journal.log") as Outcome.Success).value
        orchestrator = Orchestrator(
            clock, ids, RandomSource.seeded(303), journal, Recorder(wrap(runner), starts),
            ProjectConfig(project, repo, dir.resolve("trees").toString(), profile, sources = { sources(project) }),
            listOf(TargetSlot(target, emptySet(), sessionsPerTarget)), GitService(host, GitCommands(ApprovalLedger()), clock), Verifier(steps, clock), accounts,
            efficiency = efficiency, automationGate = automationGate,
        )
    }

    /** Номер попытки задачи для сессии: первая сессия задачи — 1, следующая — 2… */
    private fun attemptOf(session: SessionId): Int {
        val task = orchestrator.sessions.sessions.getValue(session).task
        return orchestrator.sessions.sessions.values.filter { it.task == task }.sortedBy { it.id.value }.indexOfFirst { it.id == session } + 1
    }

    private fun lastPrompt(task: TaskId): String =
        starts.lastOrNull { orchestrator.sessions.sessions[it.session]?.task == task }?.request?.prompt.orEmpty()

    /** Такты до тишины: все задачи конечны или [maxTicks]; число тактов — мера «времени до готовности». */
    fun run(maxTicks: Int = 30): Int {
        var ticks = 0
        while (ticks < maxTicks) {
            ticks++
            orchestrator.tick()
            orchestrator.sessions.sessions.keys.forEach { s -> while (runner.advance(s)) continue }
            orchestrator.tick()
            assertEquals(emptyList(), orchestrator.invariants())
            clock.advance(2.minutes)
            if (orchestrator.tasks.tasks.values.all { it.terminal || it.state == com.dpsmpx.dpai.core.state.TaskState.BLOCKED }) break
        }
        return ticks
    }

    /** Prompt задач по порядку ходов (оценка оркестратора, `SESSION_PROMPT_PREPARED`). */
    fun prompts(task: TaskId) = orchestrator.sessions.sessions.values.filter { it.task == task }
        .sortedBy { it.createdAt.epochMillis * 1000 + it.turn }.mapNotNull { it.usage.lastPrompt }

    private class Recorder(private val inner: ClaudeRunner, private val starts: MutableList<Start>) : ClaudeRunner by inner {
        override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
            starts += Start(session, request, provider, resume)
            return inner.start(session, request, provider, resume)
        }
    }
}
