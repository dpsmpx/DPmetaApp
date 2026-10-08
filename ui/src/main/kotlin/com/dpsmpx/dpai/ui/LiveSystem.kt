package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.accounts.Accounts
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
import com.dpsmpx.dpai.notifications.Notification
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.orchestrator.ProjectConfig
import com.dpsmpx.dpai.orchestrator.TargetSlot
import com.dpsmpx.dpai.orchestrator.accounts.SlotAuthProbe
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.orchestrator.verification.AgentStepRunner
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
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
    /**
     * Проект с репозиторием на одном из targets: задачи идут туда, в свои
     * рабочие деревья, проверка — через агента target, слияние — по
     * одобрению. `null` — задачи в каталоге агента, без Git и проверки.
     */
    private val project: ProjectEntry? = null,
) : ControlView {
    override val mode: String = "контроллер"
    private val ids = IdGenerator(clock, random)
    // Сегменты и снимки (D-37): контроллер живёт неделями, память журнала не растёт с историей.
    private val journal: EventJournal = EventJournal.open(vfs, "live.journal", quarantineCorruption = true, segmentSize = SEGMENT).valueOrNull()
        ?: error("журнал контроллера не открылся")
    private val snapshots = SnapshotStore(DocumentStore(vfs, "snapshots"))
    private val usable = entries.filter { it.configDir != null }

    /** Targets, на которых идут сессии: при проекте — только его target (деревья — на его диске). */
    private val working = project?.let { p -> usable.filter { it.id == p.target } } ?: usable
    private val manager = ExecutionTargetManager(clock, sshHost = sshHost, controllerVersion = controllerVersion)
    private val runners = HashMap<ExecutionTargetId, ClaudeRunner>()
    private val probes = HashMap<ExecutionTargetId, Pair<SlotAuthProbe, SessionId>>()

    /** Targets, где Claude ещё не вошёл: слота нет, задачи туда не идут. */
    private val waitingLogin = HashSet<ExecutionTargetId>()
    private val slotOf = HashMap<ExecutionTargetId, AccountId>()
    private val accountsLogic = Accounts()
    val orchestrator: Orchestrator
    val notes: List<String> = entries.filter { it.configDir == null }.map { "${it.label}: нет каталога Claude — только проверка агента" } +
        (project?.takeIf { p -> usable.none { it.id == p.target } }?.let { listOf("проект: его target без каталога Claude — задачи не запускаются") }.orEmpty())
    val problems = mutableListOf<Diagnostic>()

    init {
        for (e in usable) {
            manager.register(e.toTarget(knownHostsFile))
            runners[e.id] = ClaudeCodeCliRunner(manager.client(e.id).valueOrNull()!!, { ProviderSessionId.newUuid(random) })
        }
        lateinit var o: Orchestrator
        val router = RoutingRunner({ o.sessions.sessions[it]?.target }, { runners[it] })
        // Состояние — из журнала (перезапуск приложения); подписка и слоты
        // регистрируются событиями, чтобы журнал знал их после перезапуска.
        val projectTarget = project?.let { p -> usable.firstOrNull { it.id == p.target } }
        val config = if (project != null) {
            ProjectConfig(project.project, project.repository(), project.worktreeRoot, project.profile(), integrationBranch = project.integrationBranch)
        } else {
            ProjectConfig(ids.next(IdKinds.PROJECT), null, null, null)
        }
        // Git на target — по SSH: команды `git -C <путь>` рабочего каталога не требуют (GitService).
        val git = projectTarget?.let { t -> GitService(sshHost(t.toTarget(knownHostsFile).ssh!!, t.platform), GitCommands(ApprovalLedger()), clock) }
        // Шаги проверки — через агента target: по SSH рабочий каталог задаёт он (AgentStepRunner).
        val verifier = projectTarget?.let { t -> Verifier(AgentStepRunner(manager.client(t.id).valueOrNull()!!, ids, clock), clock) }
            ?: Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock)
        o = Orchestrator(
            clock, ids, random, journal, router, config,
            working.map { TargetSlot(it.id, emptySet(), maxSessions = 1) }, git, verifier,
            snapshots = snapshots, snapshotEvery = SEGMENT.toLong(), documents = DocumentStore(vfs, "docs"),
        )
        orchestrator = o
        // Слот target — после первой пробы входа: подписка выбирается по аккаунту (см. [placeSlot]).
        for (e in usable) {
            val dir = e.configDir?.let(Accounts::normalizeDir)
            o.accounts.slots.values.firstOrNull { it.targetId == e.id && it.configDir == dir }?.let { slotOf[e.id] = it.id }
        }
        for (r in o.recover { t -> manager.client(t).valueOrNull() }) problems += r.diagnostics.filter { it.isError }
        for (e in usable) probeSlot(e.id)
    }

    /** Проверить вход слота target заново — после того как автор вошёл в Claude. */
    fun probeSlot(target: ExecutionTargetId) {
        val entry = usable.firstOrNull { it.id == target } ?: return
        val client = when (val c = manager.client(target)) {
            is Outcome.Failure -> {
                problems += c.diagnostics
                return
            }
            is Outcome.Success -> c.value
        }
        val probe = SlotAuthProbe(client)
        when (val s = probe.start(ids.next(IdKinds.SESSION), entry.configDir!!)) {
            is Outcome.Failure -> problems += s.diagnostics
            is Outcome.Success -> probes[target] = probe to s.value
        }
    }

    /**
     * Слот для target по итогу первой пробы (D-29, D-30): вошли аккаунтом,
     * чей отпечаток уже у подписки, — слот в ней (несколько targets одной
     * подписки); иначе — новая подписка. Отпечаток неизвестен — своя
     * подписка: смешать два разных аккаунта в одной нельзя. Не вошли —
     * слота нет, target ждёт входа.
     */
    private fun placeSlot(e: TargetEntry, status: com.dpsmpx.dpai.accounts.AuthStatus) {
        if (!status.loggedIn) {
            waitingLogin += e.id
            return
        }
        waitingLogin -= e.id
        val o = orchestrator
        val sub = status.identity?.let { id -> o.accounts.subscriptions.values.firstOrNull { it.identity == id }?.id }
            ?: ids.next(IdKinds.SUBSCRIPTION).also { s ->
                val label = status.plan?.let { "подписка $it (${e.label})" } ?: "подписка ${e.label}"
                o.commitAccounts(accountsLogic.registerSubscription(o.accounts, s, label, clock.now(), maxParallelSessions = MAX_PARALLEL).valueOrNull()!!)
            }
        val slot = ids.next(IdKinds.ACCOUNT)
        when (val r = accountsLogic.registerSlot(o.accounts, slot, sub, e.id, e.configDir!!, clock.now())) {
            is Outcome.Failure -> {
                problems += r.diagnostics
                return
            }
            is Outcome.Success -> o.commitAccounts(r.value)
        }
        slotOf[e.id] = slot
        when (val ev = accountsLogic.observeAuthStatus(o.accounts, slot, status, clock.now())) {
            is Outcome.Failure -> problems += ev.diagnostics
            is Outcome.Success -> o.commitAccounts(ev.value)
        }
    }

    fun addTask(title: String, objective: String): Outcome<TaskId> = orchestrator.addTask(title, objective, Priority.NORMAL)

    private var delivered = 0

    /** Уведомления, которые оболочка ещё не показала: новые и обновлённые (ТЗ, §36). */
    fun drainNotifications(): List<Notification> {
        val fresh = orchestrator.posted.drop(delivered)
        delivered = orchestrator.posted.size
        return fresh.mapNotNull {
            when (it) {
                is NotificationDecision.Post -> it.notification
                is NotificationDecision.Update -> it.notification
                is NotificationDecision.Held -> null
            }
        }
    }

    /** Кнопка уведомления (`dpai://approve|deny/…`): одобрение — только с хешем текущего предложения (D-10). */
    fun respond(uri: String): Outcome<Boolean> = orchestrator.respond(uri, "автор")

    override fun step() {
        for ((target, p) in probes.entries.toList()) {
            when (val r = p.first.poll(p.second)) {
                is Outcome.Failure -> {
                    problems += r.diagnostics
                    probes.remove(target)
                }
                is Outcome.Success -> r.value?.let { status ->
                    probes.remove(target)
                    val slot = slotOf[target]
                    if (slot == null) {
                        usable.firstOrNull { it.id == target }?.let { placeSlot(it, status) }
                    } else {
                        when (val ev = accountsLogic.observeAuthStatus(orchestrator.accounts, slot, status, clock.now())) {
                            is Outcome.Failure -> problems += ev.diagnostics
                            is Outcome.Success -> orchestrator.commitAccounts(ev.value)
                        }
                    }
                }
            }
        }
        orchestrator.tick()
        if (problems.size > MAX_PROBLEMS) problems.subList(0, problems.size - MAX_PROBLEMS).clear()
    }

    /** События активного сегмента журнала — для временной шкалы (D-37). */
    fun events(): List<EventEnvelope> = journal.records(journal.activeFrom).map { it.event }

    fun diagnosis(): SelfDiagnosis = SelfDiagnostics.evaluate(
        SystemSignals(
            storageOk = true, recoveryInProgress = false, gitAvailable = null, notificationsOk = null,
            targetsOnline = usable.size, targetsTotal = usable.size, securityAlert = false,
        ),
    )

    override fun dashboard(): DashboardScreen {
        val base = Screens.dashboard(orchestrator.dashboard(), diagnosis())
        val login = usable.filter { it.id in waitingLogin }.map { "${it.label}: войдите в Claude в каталоге ${it.configDir}, затем «Вход Claude»" }
        val extra = notes + login + (problems + orchestrator.problems).takeLast(5).map { "${it.code}: ${it.message}" }
        return base.copy(attention = base.attention + extra)
    }

    override fun board(): TaskBoard = Screens.board(orchestrator.tasks)

    override fun session(id: SessionId): SessionScreen = Screens.session(orchestrator.dashboard(), id, events())

    companion object {
        private const val MAX_PROBLEMS = 50

        /** Предел одновременных сессий подписки контроллера; на target — по одной (`TargetSlot`). */
        private const val MAX_PARALLEL = 8

        /** Записей в сегменте журнала контроллера и между снимками. */
        private const val SEGMENT = 20_000
    }
}
