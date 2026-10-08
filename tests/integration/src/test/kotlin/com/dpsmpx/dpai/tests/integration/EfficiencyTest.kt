package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.HandoffReason
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCli
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.MockRunner
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
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Слой экономии D-40 в контуре (`docs/EFFICIENCY.md`): проваленная
 * проверка, продолжение, маршрут модели, повтор постановки, бюджет — с
 * настоящим Git и заглушкой Claude; рядом — то же с
 * [EfficiencyPolicy.LEGACY] (как до D-40).
 */
class EfficiencyTest {
    @TempDir
    lateinit var dir: Path

    /** Старт хода глазами Claude: запрос, сессия провайдера, `--resume` ли. */
    data class Start(val request: ClaudeRequest, val provider: ProviderSessionId?, val resume: Boolean)

    /** Контур с одним слотом; первая проверка падает с длинным выводом, следующие проходят. */
    inner class Harness(efficiency: EfficiencyPolicy, sources: (com.dpsmpx.dpai.core.ids.ProjectId) -> List<ContextSource> = { emptyList() }, failures: Int = 1) {
        val clock = ManualClock()
        private val ids = IdGenerator(clock, RandomSource.seeded(301))
        private val host = LocalProcessHost()
        val starts = mutableListOf<Start>()
        private var failuresLeft = failures
        val orchestrator: Orchestrator
        private val runner: MockRunner = MockRunner.create(clock, RandomSource.seeded(302)) { _, _ -> com.dpsmpx.dpai.execution.claude.MockScenario.SUCCESS }

        private fun sh(vararg argv: String) {
            val r = host.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
            assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
        }

        init {
            val root = dir.resolve("repo-${efficiency.hashCode()}").toFile().apply { mkdirs() }.path
            sh("init", "-q", "-b", "main", root)
            sh("-C", root, "config", "user.email", "dpai-test@example.org")
            sh("-C", root, "config", "user.name", "dpai test")
            File(root, "README.md").writeText("проект\n")
            sh("-C", root, "add", "README.md")
            sh("-C", root, "commit", "-q", "-m", "начало")
            val repo = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), root)
            val logic = Accounts()
            var accounts = AccountsState.EMPTY
            val sub = ids.next(IdKinds.SUBSCRIPTION)
            accounts = Accounts.applyAll(accounts, logic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
            val target = ids.next(IdKinds.TARGET)
            val slot = ids.next(IdKinds.ACCOUNT)
            accounts = Accounts.applyAll(accounts, logic.registerSlot(accounts, slot, sub, target, "/home/dev/.claude", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            accounts = Accounts.applyAll(accounts, logic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
            val project = ids.next(IdKinds.PROJECT)
            val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("тесты", listOf("gradle", "test")))))
            // Шаг проверки: первые [failures] раз — длинный вывод Gradle с одной ошибкой.
            val steps = com.dpsmpx.dpai.verification.StepRunner { _, _, _ ->
                if (failuresLeft-- > 0) {
                    Outcome.success(Completed(1, List(3_000) { "> Task :m$it:compileKotlin UP-TO-DATE" } + listOf("ParserTest > exponent() FAILED", "    AssertionError: ожидалось 1000, получено 1", "40 tests completed, 1 failed", "BUILD FAILED in 9s"), emptyList()))
                } else {
                    Outcome.success(Completed(0, listOf("BUILD SUCCESSFUL"), emptyList()))
                }
            }
            val journal = (EventJournal.open(NioVfs(dir), "data-${efficiency.hashCode()}/journal.log") as Outcome.Success).value
            orchestrator = Orchestrator(
                clock, ids, RandomSource.seeded(303), journal, Recorder(runner, starts),
                ProjectConfig(project, repo, dir.resolve("trees-${efficiency.hashCode()}").toString(), profile, sources = { sources(project) }),
                listOf(TargetSlot(target, emptySet(), 2)), GitService(host, GitCommands(ApprovalLedger()), clock), Verifier(steps, clock), accounts,
                efficiency = efficiency,
            )
        }

        fun run(maxTicks: Int = 20) = repeat(maxTicks) {
            orchestrator.tick()
            orchestrator.sessions.sessions.keys.forEach { s -> while (runner.advance(s)) continue }
            orchestrator.tick()
            assertEquals(emptyList(), orchestrator.invariants())
            clock.advance(2.minutes)
        }

        fun prompts(task: TaskId) = orchestrator.sessions.sessions.values.filter { it.task == task }.sortedBy { it.createdAt.epochMillis * 1000 + it.turn }.mapNotNull { it.usage.lastPrompt }
    }

    private class Recorder(private val inner: ClaudeRunner, private val starts: MutableList<Start>) : ClaudeRunner by inner {
        override fun start(session: SessionId, request: ClaudeRequest, provider: ProviderSessionId?, resume: Boolean): Outcome<RunnerState> {
            starts += Start(request, provider, resume)
            return inner.start(session, request, provider, resume)
        }
    }

    private val guide = "руководство по проекту ".repeat(400)

    private fun guideDoc(project: com.dpsmpx.dpai.core.ids.ProjectId) =
        listOf(ContextSource("doc:guide.md", SourceLevel.DOCUMENT, project, Sensitivity.INTERNAL, Origin.REPOSITORY, guide))

    @Test
    fun failedVerificationContinuesTheSameSessionWithTheDigestOnly() {
        val h = Harness(EfficiencyPolicy.DEFAULT, sources = ::guideDoc)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту", policy = TaskPolicy(taskClass = TaskClass.TRIVIAL)).valueOrNull()!!
        h.run()
        assertEquals(TaskState.DONE, h.orchestrator.tasks.tasks.getValue(t).state)
        assertEquals(2, h.starts.size)
        val (first, second) = h.starts
        // Первая попытка: дешёвый маршрут класса, без субагентов, общий кеш prompt, факты среды.
        assertEquals("haiku", first.request.model)
        assertEquals("low", first.request.effort)
        assertTrue(ClaudeCodeCli.SUBAGENT_TOOL in first.request.disallowedTools)
        assertTrue(first.request.shareSystemPromptCache)
        assertTrue("После хода оркестратор сам" in first.request.prompt && guide.take(200) in first.request.prompt)
        assertFalse(first.resume)
        // Вторая: та же сессия провайдера, ступенью выше, выжимка вместо 3000 строк, документ — не повторно.
        assertTrue(second.resume)
        assertEquals(first.provider, second.provider)
        assertEquals("sonnet", second.request.model)
        assertTrue("AssertionError: ожидалось 1000, получено 1" in second.request.prompt, second.request.prompt)
        assertTrue("40 tests completed, 1 failed" in second.request.prompt)
        // Из 3000 успешных строк — только края вывода; пропуск назван.
        assertTrue(second.request.prompt.lines().count { "UP-TO-DATE" in it } <= 11, "успешные строки не идут в prompt")
        assertTrue("… пропущено строк:" in second.request.prompt)
        assertFalse(guide.take(200) in second.request.prompt)
        assertTrue("# Без изменений с прошлого хода этой сессии" in second.request.prompt)
        val prompts = h.prompts(t)
        assertEquals(listOf(PromptMode.FULL, PromptMode.DELTA), prompts.map { it.mode })
        assertTrue(prompts[1].reusedTokens > 0)
        assertEquals("escalated:1", prompts[1].route)
        assertEquals(HandoffReason.VERIFICATION_FAILED, h.orchestrator.handoffs.latest(t)?.reason)
    }

    @Test
    fun legacyRetriesBlindInAFreshSession() {
        val h = Harness(EfficiencyPolicy.LEGACY, sources = ::guideDoc)
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту", policy = TaskPolicy(taskClass = TaskClass.TRIVIAL)).valueOrNull()!!
        h.run()
        assertEquals(2, h.starts.size)
        val (first, second) = h.starts
        assertNull(first.request.model)
        assertFalse(first.request.shareSystemPromptCache)
        assertFalse(second.resume)
        assertNotEquals(first.provider, second.provider)
        assertFalse("AssertionError" in second.request.prompt, "до D-40 следующая попытка не знает, что упало")
        assertEquals(listOf(PromptMode.FULL, PromptMode.FULL), h.prompts(t).map { it.mode })
    }

    @Test
    fun sameTaskTwiceIsRefusedUnlessLegacy() {
        val h = Harness(EfficiencyPolicy.DEFAULT)
        assertIs<Outcome.Success<TaskId>>(h.orchestrator.addTask("парсер", "поддержать экспоненту"))
        val again = h.orchestrator.addTask(" Парсер ", "поддержать  экспоненту")
        assertIs<Outcome.Failure>(again)
        assertEquals(DiagCode.TASK_DUPLICATE, again.diagnostics.first().code)
        assertIs<Outcome.Success<TaskId>>(h.orchestrator.addTask("парсер 2", "поддержать экспоненту"))
        val legacy = Harness(EfficiencyPolicy.LEGACY)
        legacy.orchestrator.addTask("парсер", "поддержать экспоненту")
        assertIs<Outcome.Success<TaskId>>(legacy.orchestrator.addTask("парсер", "поддержать экспоненту"))
    }

    @Test
    fun rulesThatDoNotFitBlockTheTaskInsteadOfBeingCut() {
        val rules = ContextSource("rules", SourceLevel.GLOBAL, null, Sensitivity.INTERNAL, Origin.ORCHESTRATOR, "правило ".repeat(40_000))
        val h = Harness(EfficiencyPolicy.DEFAULT, sources = { listOf(rules) })
        val t = h.orchestrator.addTask("парсер", "поддержать экспоненту").valueOrNull()!!
        h.run(3)
        val task = h.orchestrator.tasks.tasks.getValue(t)
        assertEquals(TaskState.BLOCKED, task.state)
        assertEquals(DiagCode.CONTEXT_BUDGET_EXCEEDED, task.lastReason)
        assertEquals(0, h.starts.size)
    }
}
