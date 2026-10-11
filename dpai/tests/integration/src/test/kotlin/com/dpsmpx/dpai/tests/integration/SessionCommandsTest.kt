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
import com.dpsmpx.dpai.sessions.PromptMode
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
 * Действия человека с сессиями (D-42, п. 3): остановить ход; запрос —
 * новый ход той же сессии провайдера с текстом, моделью и усилием;
 * свежая сессия с handoff; живой вид хода; в идущий ход не пишут.
 */
class SessionCommandsTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(342))
    private val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(343)))
    private val targets = List(1) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 2) }
    private val accounts: AccountsState = run {
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = 8).valueOrNull()!!)
        val slot = ids.next(IdKinds.ACCOUNT)
        acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, targets[0].id, "/c/0", clock.now()).valueOrNull()!!)
        val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
        Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
    }
    private val project = ids.next(IdKinds.PROJECT)
    private var scenario: MockScenario = MockScenario.SUCCESS

    private fun boot(): Pair<Orchestrator, MockRunner> {
        val vfs = NioVfs(dir)
        val journal = (EventJournal.open(vfs, "journal.log") as Outcome.Success).value
        val runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { _, _ -> scenario }
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("ок", listOf("ok")))))
        val o = Orchestrator(
            clock, ids, RandomSource.seeded(344), journal, runner, ProjectConfig(project, null, dir.toString(), profile), targets, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), accounts,
            documents = DocumentStore(vfs, "docs"),
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

    private fun sessionsOf(o: Orchestrator, t: TaskId) = o.sessions.sessions.values.filter { it.task == t }.sortedBy { it.createdAt.epochMillis }

    @Test
    fun sendContinuesSameProviderSessionWithTextModelAndEffort() {
        val (o, runner) = boot()
        val t = o.addTask("задача", "цель").valueOrNull()!!
        advance(o, runner, 3)
        assertEquals(TaskState.DONE, o.tasks.tasks.getValue(t).state)
        val first = sessionsOf(o, t).last()
        assertEquals(DiagCode.VALUE_INVALID, o.run(Command.SendToSession(first.id, "  ")).diagnostics.single().code, "запрос без текста")
        val r = o.run(Command.SendToSession(first.id, "добавь ещё одну проверку", model = "haiku", effort = "low"))
        assertEquals(OperationStatus.SUCCEEDED, r.status, r.diagnostics.toString())
        advance(o, runner, 3)
        val second = sessionsOf(o, t).last()
        assertTrue(second.id != first.id)
        assertEquals(first.provider, second.provider, "новый ход той же сессии провайдера (--resume)")
        assertEquals("haiku", second.usage.lastPrompt?.model)
        assertEquals("low", second.usage.lastPrompt?.effort)
        assertTrue(o.lastContext(t)!!.sources.any { "добавь ещё одну проверку" in it.content }, "текст запроса — в контексте хода")
        assertEquals(TaskState.DONE, o.tasks.tasks.getValue(t).state)
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, o.run(Command.SendToSession(first.id, "в старую")).diagnostics.single().code, "запрос — в последнюю сессию")
    }

    @Test
    fun freshSessionUsesHandoffInsteadOfResume() {
        val (o, runner) = boot()
        val t = o.addTask("задача", "цель").valueOrNull()!!
        advance(o, runner, 3)
        val first = sessionsOf(o, t).last()
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SendToSession(first.id, null, fresh = true)).status)
        advance(o, runner, 3)
        val second = sessionsOf(o, t).last()
        assertTrue(first.provider != second.provider, "свежая сессия провайдера")
        assertEquals(PromptMode.CHECKPOINT, second.usage.lastPrompt?.mode)
    }

    @Test
    fun runningTurnIsNotWrittenToButCanBeStopped() {
        scenario = MockScenario.SLOW_OUTPUT
        val (o, runner) = boot()
        val t = o.addTask("долгая", "цель").valueOrNull()!!
        o.tick()
        val s = sessionsOf(o, t).last()
        val run = host.runIds().single()
        repeat(2) { runner.advance(run) }
        o.tick()
        assertTrue(o.live(s.id) != null, "живой вид хода")
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, o.run(Command.SendToSession(s.id, "ещё")).diagnostics.single().code, "в идущий ход не пишут (D-04)")
        val stop = o.run(Command.StopSession(s.id))
        assertEquals(OperationStatus.SUCCEEDED, stop.status, stop.diagnostics.toString())
        assertTrue(o.tasks.tasks.getValue(t).held)
        assertEquals(SessionState.CANCELLED, o.sessions.sessions.getValue(s.id).state)
        // После остановки — запрос: та же сессия провайдера, задача снова в плане и не удержана.
        scenario = MockScenario.SUCCESS
        assertEquals(OperationStatus.SUCCEEDED, o.run(Command.SendToSession(s.id, "продолжай короче")).status)
        assertTrue(!o.tasks.tasks.getValue(t).held)
        advance(o, runner, 3)
        assertEquals(s.provider, sessionsOf(o, t).last().provider)
        assertEquals(TaskState.DONE, o.tasks.tasks.getValue(t).state)
    }

    /** Итоги ходов задачи (D-42) — в документе задачи: целиком и после перезапуска. */
    @Test
    fun turnMessagesSurviveRestart() {
        val (o, runner) = boot()
        val t = o.addTask("задача", "цель").valueOrNull()!!
        advance(o, runner, 3)
        val first = o.turnMessages(t)
        assertEquals(1, first.size, first.toString())
        assertTrue(first.single().text.isNotBlank())
        assertEquals("COMPLETED", first.single().status)
        val (second, _) = boot()
        assertEquals(first, second.turnMessages(t), "итоги ходов пережили перезапуск")
    }
}
