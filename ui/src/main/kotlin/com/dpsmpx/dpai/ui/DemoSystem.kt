package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.monitoring.SelfDiagnosis
import com.dpsmpx.dpai.monitoring.SelfDiagnostics
import com.dpsmpx.dpai.monitoring.SystemSignals
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.VerifyStep

/**
 * Демонстрационная система: настоящий [Orchestrator] на `MockRunner` —
 * без Claude, Git и сети (ТЗ, §89). Ею оболочка показывает интерфейс,
 * пока на устройстве нет настоящего источника (`TODO.md`), и на ней
 * меряется отрисовка 20+ сессий (критерий этапа 11). Экран называет режим
 * «демо» — ничего не выдаётся за настоящую работу (D-05).
 *
 * Сценарии ходов смешаны: в основном медленный успех, а также отказ,
 * предел подписки и нужный вход — чтобы на сводке было что требует
 * внимания. Случайность — с зерном (D-07).
 */
class DemoSystem(
    private val clock: Clock,
    vfs: Vfs,
    sessions: Int = 25,
    seed: Long = 11,
) : ControlView {
    override val mode: String = "демо"
    private val random = RandomSource.seeded(seed)
    private val ids = IdGenerator(clock, random)
    private val journal: EventJournal = EventJournal.open(vfs, "demo.journal", quarantineCorruption = true).valueOrNull()
        ?: error("журнал демо не открылся")
    private val runner: MockRunner
    override val orchestrator: Orchestrator
    private val targets: Int = (sessions + 4) / 5

    init {
        val slots = List(targets) { TargetSlot(ids.next(IdKinds.TARGET), emptySet(), maxSessions = 5) }
        val logic = Accounts()
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, logic.registerSubscription(acc, sub, "демо", clock.now(), maxParallelSessions = sessions).valueOrNull()!!)
        repeat(sessions) { i ->
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, logic.registerSlot(acc, slot, sub, slots[i % slots.size].id, "/demo/$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("demo", "demo@example.org"), "max")
            acc = Accounts.applyAll(acc, logic.observeAuthStatus(acc, slot, status, clock.now()).valueOrNull()!!)
        }
        val scenarioRandom = RandomSource.seeded(seed + 1)
        runner = MockRunner.create(clock, RandomSource.seeded(seed + 2)) { _, _ ->
            when (scenarioRandom.nextInt(20)) {
                0 -> MockScenario.FAILURE
                1 -> MockScenario.RATE_LIMIT
                2 -> MockScenario.AUTH_ERROR
                else -> MockScenario.SLOW_OUTPUT
            }
        }
        val project = ids.next(IdKinds.PROJECT)
        val profile = VerificationProfile(project, mapOf(VerifyLevel.NORMAL to listOf(VerifyStep("демо", listOf("demo")))))
        orchestrator = Orchestrator(
            clock, ids, RandomSource.seeded(seed + 3), journal, runner,
            ProjectConfig(project, null, null, profile),
            slots, null, Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), acc,
        )
        addTasks(sessions)
    }

    /** Задачи с зависимостями: каждая пятая ждёт предыдущую — на доске виден DAG. */
    private fun addTasks(n: Int) {
        var previous: TaskId? = null
        for (i in 1..n) {
            val deps = if (i % 5 == 0 && previous != null) setOf(previous) else emptySet()
            val priority = Priority.entries[i % 3 + 1]
            previous = orchestrator.addTask("Демо-задача $i", "показать работу контура", priority, deps).valueOrNull()
        }
    }

    /**
     * Шаг демо: часть работающих сессий выдаёт по строке вывода, затем
     * такт контура. Завершённые задачи заменяются новыми — сессий всегда
     * много.
     */
    override fun step() {
        for (run in runner.host.runIds().sortedBy { it.value }) {
            if (runner.host.hasPending(run) && random.nextInt(3) == 0) runner.advance(run)
        }
        orchestrator.tick()
        val open = orchestrator.tasks.tasks.values.count { !it.terminal }
        if (open < targets * 4) addTasks(targets)
    }

    fun events(): List<EventEnvelope> = journal.records().map { it.event }

    fun diagnosis(): SelfDiagnosis = SelfDiagnostics.evaluate(
        SystemSignals(
            storageOk = true, recoveryInProgress = false, gitAvailable = null, notificationsOk = null,
            targetsOnline = targets, targetsTotal = targets, securityAlert = false,
        ),
    )

    override fun dashboard(): DashboardScreen =
        Screens.dashboard(orchestrator.dashboard(), diagnosis(), orchestrator.usage()).copy(limits = Screens.limits(orchestrator.accounts, clock.now()))

    override fun session(id: SessionId): SessionScreen = Screens.session(
        orchestrator.dashboard(), id, events(), orchestrator.live(id), clock.now(), Screens.sessionActions(id, orchestrator.sessions, orchestrator.tasks),
    )

    override fun board(): TaskBoard = Screens.board(orchestrator.tasks)
}
