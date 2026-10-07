package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCliRunner
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.execution.claude.RoutingRunner
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.monitoring.SelfDiagnosis
import com.dpsmpx.dpai.monitoring.SelfDiagnostics
import com.dpsmpx.dpai.monitoring.SystemSignals
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.accounts.SlotAuthProbe
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.Vfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.verification.Verifier

/** Что показывает оболочка: демо или настоящая система — одинаково (D-34). */
interface ControlView {
    /** Подпись режима в заголовке: «демо» не выдаётся за настоящую работу (D-05). */
    val mode: String

    fun step()

    fun dashboard(): DashboardScreen

    fun board(): TaskBoard

    fun session(id: SessionId): SessionScreen
}

/**
 * Настоящая система контроллера (D-35): [Orchestrator] над targets,
 * заведёнными в приложении, — по слоту аккаунта на target с его каталогом
 * Claude; сессии уходят раннеру своего target ([RoutingRunner]); вход
 * слотов проверяет [SlotAuthProbe], пока слот не `AVAILABLE` — задачи на
 * нём ждут. Targets без каталога Claude (формат v1) в сессиях не
 * участвуют — это видно в причинах.
 *
 * Проекта с репозиторием пока нет: задача идёт в домашнем каталоге агента,
 * профиля проверки нет — итог честно `COMPLETION_UNVERIFIED` (D-23).
 * [sshHost] — хост SSH-targets: в приложении JSch с ключом Keystore, в
 * тестах — mock.
 */
class LiveSystem(
    private val clock: Clock,
    vfs: Vfs,
    entries: List<TargetEntry>,
    sshHost: (SshEndpoint, TargetPlatform) -> ProcessHost,
    knownHostsFile: String,
    random: RandomSource = RandomSource.secure(),
    controllerVersion: String? = null,
) : ControlView {
    override val mode: String = "контроллер"
    private val ids = IdGenerator(clock, random)
    private val journal: EventJournal = EventJournal.open(vfs, "live.journal", quarantineCorruption = true).valueOrNull()
        ?: error("журнал контроллера не открылся")
    private val usable = entries.filter { it.configDir != null }
    private val manager = ExecutionTargetManager(clock, sshHost = sshHost, controllerVersion = controllerVersion)
    private val runners = HashMap<ExecutionTargetId, ClaudeRunner>()
    private val probes = HashMap<AccountId, Pair<SlotAuthProbe, SessionId>>()
    private val slotOf = HashMap<ExecutionTargetId, AccountId>()
    private val accountsLogic = Accounts()
    val orchestrator: Orchestrator
    val notes: List<String> = entries.filter { it.configDir == null }.map { "${it.label}: нет каталога Claude — только проверка агента" }
    val problems = mutableListOf<Diagnostic>()

    init {
        var acc = AccountsState.EMPTY
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        acc = Accounts.applyAll(acc, accountsLogic.registerSubscription(acc, sub, "основная", clock.now(), maxParallelSessions = usable.size.coerceAtLeast(1)).valueOrNull()!!)
        for (e in usable) {
            manager.register(e.toTarget(knownHostsFile))
            val client = manager.client(e.id).valueOrNull()!!
            runners[e.id] = ClaudeCodeCliRunner(client, { ProviderSessionId.newUuid(random) })
            val slot = ids.next(IdKinds.ACCOUNT)
            acc = Accounts.applyAll(acc, accountsLogic.registerSlot(acc, slot, sub, e.id, e.configDir!!, clock.now()).valueOrNull()!!)
            slotOf[e.id] = slot
        }
        lateinit var o: Orchestrator
        val router = RoutingRunner({ o.sessions.sessions[it]?.target }, { runners[it] })
        o = Orchestrator(
            clock, ids, random, journal, router,
            ProjectConfig(ids.next(IdKinds.PROJECT), null, null, null),
            usable.map { TargetSlot(it.id, emptySet(), maxSessions = 1) }, null,
            Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock), acc,
        )
        orchestrator = o
        for (e in usable) probeSlot(e.id)
    }

    /** Проверить вход слота target заново — после того как автор вошёл в Claude. */
    fun probeSlot(target: ExecutionTargetId) {
        val slot = slotOf[target] ?: return
        val probe = SlotAuthProbe(manager.client(target).valueOrNull()!!)
        when (val s = probe.start(ids.next(IdKinds.SESSION), orchestrator.accounts.slots.getValue(slot))) {
            is Outcome.Failure -> problems += s.diagnostics
            is Outcome.Success -> probes[slot] = probe to s.value
        }
    }

    fun addTask(title: String, objective: String): Outcome<TaskId> = orchestrator.addTask(title, objective, Priority.NORMAL)

    override fun step() {
        for ((slot, p) in probes.entries.toList()) {
            when (val r = p.first.poll(p.second)) {
                is Outcome.Failure -> {
                    problems += r.diagnostics
                    probes.remove(slot)
                }
                is Outcome.Success -> r.value?.let { status ->
                    probes.remove(slot)
                    when (val ev = accountsLogic.observeAuthStatus(orchestrator.accounts, slot, status, clock.now())) {
                        is Outcome.Failure -> problems += ev.diagnostics
                        is Outcome.Success -> orchestrator.commitAccounts(ev.value)
                    }
                }
            }
        }
        orchestrator.tick()
        if (problems.size > MAX_PROBLEMS) problems.subList(0, problems.size - MAX_PROBLEMS).clear()
    }

    fun events(): List<EventEnvelope> = journal.records().map { it.event }

    fun diagnosis(): SelfDiagnosis = SelfDiagnostics.evaluate(
        SystemSignals(
            storageOk = true, recoveryInProgress = false, gitAvailable = null, notificationsOk = null,
            targetsOnline = usable.size, targetsTotal = usable.size, securityAlert = false,
        ),
    )

    override fun dashboard(): DashboardScreen {
        val base = Screens.dashboard(orchestrator.dashboard(), diagnosis())
        val extra = notes + (problems + orchestrator.problems).takeLast(5).map { "${it.code}: ${it.message}" }
        return base.copy(attention = base.attention + extra)
    }

    override fun board(): TaskBoard = Screens.board(orchestrator.tasks)

    override fun session(id: SessionId): SessionScreen = Screens.session(orchestrator.dashboard(), id, events())

    companion object {
        private const val MAX_PROBLEMS = 50
    }
}
