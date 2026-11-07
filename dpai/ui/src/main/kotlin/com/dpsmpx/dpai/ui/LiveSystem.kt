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
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.execution.agent.FollowingEventSource
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
import com.dpsmpx.dpai.orchestrator.RemoteWorkspace
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

    /** Контур под оболочкой: запросы человеку и ответы на них (D-44) — его команды. */
    val orchestrator: Orchestrator
}

/**
 * Настоящая система контроллера (D-35): [Orchestrator] над targets,
 * заведёнными в приложении, — по слоту аккаунта на target с его каталогом
 * Claude; сессии уходят раннеру своего target ([RoutingRunner]); вход
 * слотов проверяет [SlotAuthProbe], пока слот не `AVAILABLE` — задачи на
 * нём ждут. Targets без каталога Claude (формат v1) в сессиях не
 * участвуют — это видно в причинах.
 *
 * Без проекта задача идёт в домашнем каталоге агента, профиля проверки
 * нет — итог честно `COMPLETION_UNVERIFIED` (D-23). С проектом — в своём
 * дереве на target проекта; ход Claude — там же или на другом target, со
 * входом его слота и деревом по MCP (D-50).
 * [sshHost] — хост SSH-targets: в приложении JSch с ключом Keystore, в
 * тестах — mock. События идущих ходов — потоком `follow` агента (D-76), без
 * него — опросом; [close] закрывает потоки, когда систему сменяет новая.
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
    /** События журнала контура — в журнал приложения (D-43). */
    onEvents: (List<EventEnvelope>) -> Unit = {},
    /** Проблемы контура — в журнал приложения с причинами (D-43). */
    onProblem: (Diagnostic) -> Unit = {},
    /** Разрешены ли уведомления (у оболочки — Android); неизвестно — `null`. */
    private val notificationsOk: () -> Boolean? = { null },
    /**
     * Человек подтвердил карантин хвоста после порчи в середине журнала
     * (D-24). Без этого порча в середине — [JournalNeedsConfirmation], а не
     * молча отрезанные подтверждённые записи (аудит надёжности, A-1).
     */
    quarantineConfirmed: Boolean = false,
    /**
     * Агент target «в приложении» (`InAppAgentHost`, D-75, шаг 5): процессы
     * хода — в Alpine приложения; `null` — таких targets оболочка не даёт.
     */
    inAppHost: com.dpsmpx.dpai.execution.process.ProcessHost? = null,
    /**
     * Процессы гостя Linux приложения — для Git проекта на target «в
     * приложении» (D-75, шаг 5): как SSH у обычного target, только без
     * сети. `null` — Linux не установлен.
     */
    private val inAppGuest: com.dpsmpx.dpai.execution.process.ProcessHost? = null,
) : ControlView, AutoCloseable {
    override val mode: String = "контроллер"
    private val ids = IdGenerator(clock, random)

    /**
     * Диагностика открытия журнала (D-87): отрезанный хвост и карантин —
     * это потеря подтверждённых записей, о которой человек обязан узнать.
     * Собирается до [problems], потому что журнал нужен оркестратору;
     * переносится туда в `init`.
     */
    private val journalDiagnostics = mutableListOf<com.dpsmpx.dpai.core.diagnostics.Diagnostic>()

    // Сегменты и снимки (D-37): контроллер живёт неделями, память журнала не растёт с историей.
    private val journal: EventJournal = when (
        val opened = EventJournal.open(vfs, "live.journal", quarantineCorruption = quarantineConfirmed, segmentSize = SEGMENT, keepCopies = true)
    ) {
        is Outcome.Success -> {
            // Предупреждения открытия (`JOURNAL_TAIL_TRUNCATED`: что именно потеряно и где карантин)
            // не выбрасываются: без этого состояние молча откатывается на границу целой записи (D-87).
            journalDiagnostics += opened.diagnostics
            opened.value
        }
        is Outcome.Failure -> {
            val corrupted = opened.errors.firstOrNull { it.code == com.dpsmpx.dpai.core.diagnostics.DiagCode.JOURNAL_CORRUPTED }
            // Порча в активном файле лечится карантином хвоста — решением человека (D-24); в запечатанном
            // сегменте — нет: хвост там ни при чём (A-6).
            if (corrupted != null && corrupted.subject == "live.journal") throw JournalNeedsConfirmation(corrupted.message)
            if (corrupted != null) throw com.dpsmpx.dpai.storage.journal.JournalSegmentUnreadable("${corrupted.message}: ${corrupted.subject}")
            error("журнал контроллера не открылся: ${opened.diagnostics.joinToString { "${it.code}: ${it.message}" }}")
        }
    }
    private val snapshots = SnapshotStore(DocumentStore(vfs, "snapshots"))
    /**
     * Targets с каталогом Claude; «в приложении» — только когда агент
     * приложения есть ([inAppHost]): без Linux такой target ходов не
     * получает, а контроллер с остальными работает (D-75, шаг 5).
     */
    private val usable = entries.filter { it.configDir != null && (!it.inApp || inAppHost != null) }

    /** Target проекта: деревья, Git и проверка — на нём; каталог Claude ему нужен, только если ход тоже на нём. */
    private val projectEntry = project?.let { p -> entries.firstOrNull { it.id == p.target }?.takeIf { !it.inApp || inAppHost != null } }

    /**
     * Дерево на target проекта, ход — на другом (D-50): Claude Code там
     * ходит к дереву по SSH своим ключом. Target проекта удалён — `null`.
     */
    private val remote = project?.claude?.let { c ->
        projectEntry?.let { t -> SshEndpoint.of(t.host, t.user, t.port, c.identityFile, c.knownHostsFile).valueOrNull()?.let { RemoteWorkspace(it, t.platform, t.agent) } }
    }

    /**
     * Targets, на которых идут сессии: при проекте — target хода (его же
     * target или выбранный в маршруте D-50) и все слоты той же машины —
     * тот же хост и порт, то есть те же файлы, деревья и ключ связки (D-56):
     * задача, чья подписка упёрлась в предел, продолжает на другой;
     * маршрут без target проекта — ни одного.
     */
    private val working = when {
        project == null -> usable
        project.claude != null && remote == null -> emptyList()
        else -> (project.claude?.target ?: project.target).let { id ->
            val run = entries.firstOrNull { it.id == id }
            usable.filter { it.id == id || (run != null && it.host == run.host && it.port == run.port && it.platform == run.platform) }
        }
    }
    private val manager = ExecutionTargetManager(clock, sshHost = sshHost, controllerVersion = controllerVersion, inAppHost = inAppHost)
    private val runners = HashMap<ExecutionTargetId, ClaudeRunner>()
    private val streams = mutableListOf<FollowingEventSource>()
    private val probes = HashMap<ExecutionTargetId, Pair<SlotAuthProbe, SessionId>>()
    private val usageProbes = HashMap<ExecutionTargetId, Pair<com.dpsmpx.dpai.orchestrator.accounts.UsageProbe, SessionId>>()

    /** Когда загрузка окон последний раз пришла из `/usage` (D-48); не было — `null`. */
    @Volatile
    var usageRefreshedAt: com.dpsmpx.dpai.core.clock.Timestamp? = null
        private set

    /** Идёт ли обновление загрузки окон. */
    val usageRefreshing: Boolean get() = synchronized(usageProbes) { usageProbes.isNotEmpty() }

    /** Targets, где Claude ещё не вошёл: слота нет, задачи туда не идут. */
    private val waitingLogin = HashSet<ExecutionTargetId>()
    private val slotOf = HashMap<ExecutionTargetId, AccountId>()

    /**
     * Когда не удалась проба входа target (ревью A-79): нет связи, SSH до
     * добавления ключа, сон ПК. Target без слота пробуется снова раз в
     * [PROBE_RETRY_MS], а не ждёт «Проверить вход». Ответ «не вошли» — не
     * отказ: такой target ждёт входа и кнопки, без проб каждую минуту.
     */
    private val probeFailedAt = HashMap<ExecutionTargetId, Long>()
    private val accountsLogic = Accounts()
    override val orchestrator: Orchestrator
    val notes: List<String> = entries.filter { it.configDir == null && it.id != project?.target }.map { "${it.label}: нет каталога Claude — только проверка агента" } +
        entries.filter { it.inApp && inAppHost == null }.map { "${it.label}: Linux в приложении не установлен — ходов нет («Targets» → «Linux в приложении»)" } +
        listOfNotNull(
            "проект: его target удалён — задачи не запускаются".takeIf { project != null && projectEntry == null },
            "проект: у target хода нет каталога Claude — задачи не запускаются".takeIf { project != null && projectEntry != null && working.isEmpty() },
        )
    val problems = mutableListOf<Diagnostic>()

    init {
        // Потеря хвоста журнала и карантин — в те же «проблемы», что и остальные отказы (D-87),
        // и в журнал приложения (D-43): список проблем на сводке короткий, а потеря записей — навсегда.
        problems += journalDiagnostics
        journalDiagnostics.forEach { d -> runCatching { onProblem(d) } }
        // Сегмент восстановлен из копии или копия не записалась (D-95) — тоже в проблемы и журнал приложения.
        journal.onRepair = { d ->
            synchronized(problems) { problems += d }
            runCatching { onProblem(d) }
        }
        for (e in (usable + listOfNotNull(projectEntry)).distinctBy { it.id }) manager.register(e.toTarget(knownHostsFile))
        for (e in usable) {
            val agent = manager.client(e.id).valueOrNull()!!
            val events = FollowingEventSource(agent, clock).also { streams += it }
            runners[e.id] = ClaudeCodeCliRunner(agent, { ProviderSessionId.newUuid(random) }, eventSource = events)
        }
        lateinit var o: Orchestrator
        val router = RoutingRunner({ o.sessions.sessions[it]?.target }, { runners[it] })
        // Состояние — из журнала (перезапуск приложения); подписка и слоты
        // регистрируются событиями, чтобы журнал знал их после перезапуска.
        val projectTarget = projectEntry
        val config = if (project != null) {
            ProjectConfig(project.project, project.repository(), project.worktreeRoot, project.profile(), integrationBranch = project.integrationBranch, remote = remote)
        } else {
            ProjectConfig(HOME_PROJECT, null, null, null)
        }
        // Git на target — по SSH: команды `git -C <путь>` рабочего каталога не требуют (GitService);
        // «в приложении» — процессами гостя, пути — гостя (D-75, шаг 5).
        val git = projectTarget?.let { t ->
            if (t.inApp) inAppGuest?.let { GitService(it, GitCommands(ApprovalLedger()), clock) } else GitService(sshHost(t.toTarget(knownHostsFile).ssh!!, t.platform), GitCommands(ApprovalLedger()), clock)
        }
        // Шаги проверки — через агента target: по SSH рабочий каталог задаёт он (AgentStepRunner).
        val verifier = projectTarget?.let { t -> Verifier(AgentStepRunner(manager.client(t.id).valueOrNull()!!, ids, clock, onRetry = { d -> onProblem(com.dpsmpx.dpai.core.diagnostics.Diagnostic.of(d.code, "вызов агента повторён: ${d.message}", d.subject, causes = d.causes)) }), clock) }
            ?: Verifier({ _, _, _ -> Outcome.success(Completed(0, emptyList(), emptyList())) }, clock)
        o = Orchestrator(
            clock, ids, random, journal, router, config,
            working.map { TargetSlot(it.id, emptySet(), maxSessions = 1, label = it.label) }, git, verifier,
            snapshots = snapshots, snapshotEvery = SEGMENT.toLong(), documents = DocumentStore(vfs, "docs"),
            onEvents = onEvents, onProblem = onProblem,
        )
        orchestrator = o
        // Слот target — после первой пробы входа: подписка выбирается по аккаунту (см. [placeSlot]).
        for (e in usable) {
            val dir = e.configDir?.let(Accounts::normalizeDir)
            o.accounts.slots.values.firstOrNull { it.targetId == e.id && it.configDir == dir }?.let { slotOf[e.id] = it.id }
        }
        for (r in o.recover { t -> manager.client(t).valueOrNull() }) problems += r.diagnostics.filter { it.isError }
        // Подписки, заведённые до D-56, ротацию не принимали (так регистрировались) — теперь принимают,
        // пока человек не выключит: задача у предела продолжает на другой подписке.
        for (sub in o.accounts.subscriptions.values.filter { !it.rotationExplicit && !it.rotationAllowed }.sortedBy { it.id.value }) {
            accountsLogic.setRotation(o.accounts, sub.id, true, clock.now()).valueOrNull()?.let(o::commitAccounts)
        }
        // Пробы входа слотов (SSH к каждому target) — в первом такте, а не здесь (D-63): контроллер
        // строится из журнала быстро, и экран показывает состояние, не дожидаясь сети.
    }

    /** Пробы входа слотов ещё не начинались — их начнёт первый такт (D-63). */
    private var probesStarted = false

    /**
     * Слот «нужен вход» после отказа хода (D-58): без сети Claude Code не
     * обновляет токен и отвечает `authentication_failed`, хотя вход цел —
     * слот перепроверяется сам (`claude auth status`, локально, без хода и
     * лимита) через 2, 4, 8 … 60 минут; вход цел — слот снова доступен, и
     * задачи, остановленные из‑за входа, продолжаются сами. Пауза растёт,
     * чтобы настоящий отзыв входа не гонял задачу по кругу; сбрасывается
     * после [AUTH_STABLE_MS] доступности слота.
     */
    private fun recheckLogins(now: Long) {
        for (e in usable) {
            val slot = slotOf[e.id] ?: continue
            val st = orchestrator.accounts.slots[slot] ?: continue
            if (st.state != com.dpsmpx.dpai.core.state.AccountState.AUTH_REQUIRED || st.disabled) {
                if (st.state == com.dpsmpx.dpai.core.state.AccountState.AVAILABLE) {
                    authRecheck[e.id]?.let { (n, at) -> if (now - at >= AUTH_STABLE_MS) authRecheck.remove(e.id) else authRecheck[e.id] = n to at }
                }
                continue
            }
            if (e.id in probes) continue
            val (n, at) = authRecheck[e.id] ?: run {
                authRecheck[e.id] = 0 to now
                continue
            }
            val wait = (AUTH_RECHECK_FIRST_MS shl n.coerceAtMost(5)).coerceAtMost(AUTH_RECHECK_MAX_MS)
            if (now - at >= wait) {
                authRecheck[e.id] = (n + 1) to now
                probeSlot(e.id)
            }
        }
    }

    /** Перепроверки входа слота (D-58): сколько было подряд и когда последняя. */
    private val authRecheck = HashMap<ExecutionTargetId, Pair<Int, Long>>()

    /** Когда перепроверить вход слота снова (D-58) — для экрана; не ждёт — `null`. */
    fun loginRecheckAt(target: ExecutionTargetId): Long? = authRecheck[target]?.let { (n, at) ->
        at + (AUTH_RECHECK_FIRST_MS shl n.coerceAtMost(5)).coerceAtMost(AUTH_RECHECK_MAX_MS)
    }?.takeIf { slotOf[target]?.let { orchestrator.accounts.slots[it]?.state } == com.dpsmpx.dpai.core.state.AccountState.AUTH_REQUIRED }

    /**
     * Обновить загрузку окон подписок (D-48): `/usage` на каждом target со
     * слотом — без хода и без траты лимита. Уже идёт — ничего.
     */
    fun refreshUsage(only: Set<ExecutionTargetId>? = null) {
        // Загрузка окон (`/usage`, D-48) — только у Claude: Codex и Gemini окон не сообщают (D-100).
        for (e in usable.filter { (only == null || it.id in only) && it.executor == com.dpsmpx.dpai.core.ids.Executor.CLAUDE }) {
            val slot = slotOf[e.id] ?: continue
            if (synchronized(usageProbes) { e.id in usageProbes }) continue
            val dir = orchestrator.accounts.slots[slot]?.configDir ?: continue
            val client = manager.client(e.id).valueOrNull() ?: continue
            val probe = com.dpsmpx.dpai.orchestrator.accounts.UsageProbe(client)
            when (val s = probe.start(ids.next(IdKinds.SESSION), dir)) {
                is Outcome.Failure -> problems += s.diagnostics
                is Outcome.Success -> synchronized(usageProbes) { usageProbes[e.id] = probe to s.value }
            }
        }
    }

    private fun pollUsage() {
        for ((target, p) in synchronized(usageProbes) { usageProbes.entries.toList() }) {
            val r = p.first.poll(p.second)
            if (r is Outcome.Success && r.value == null) continue
            synchronized(usageProbes) { usageProbes.remove(target) }
            when (r) {
                is Outcome.Failure -> problems += r.diagnostics
                is Outcome.Success -> {
                    val slot = slotOf[target] ?: continue
                    for (w in r.value.orEmpty()) {
                        val window = if (w.window == "session") com.dpsmpx.dpai.accounts.LimitWindow.SESSION else com.dpsmpx.dpai.accounts.LimitWindow.WEEKLY
                        when (val ev = accountsLogic.observeUsage(orchestrator.accounts, slot, window, w.utilization, w.resetsAt, clock.now())) {
                            is Outcome.Failure -> problems += ev.diagnostics
                            is Outcome.Success -> orchestrator.commitAccounts(ev.value)
                        }
                    }
                    usageRefreshedAt = clock.now()
                }
            }
        }
    }

    /** Проверить вход слота target заново — после того как автор вошёл в Claude. */
    fun probeSlot(target: ExecutionTargetId) {
        val entry = usable.firstOrNull { it.id == target } ?: return
        val client = when (val c = manager.client(target)) {
            is Outcome.Failure -> {
                problems += c.diagnostics
                probeFailedAt[target] = clock.now().epochMillis
                return
            }
            is Outcome.Success -> c.value
        }
        val probe = SlotAuthProbe(client)
        when (val s = probe.start(ids.next(IdKinds.SESSION), entry.configDir!!, entry.executor)) {
            is Outcome.Failure -> {
                problems += s.diagnostics
                probeFailedAt[target] = clock.now().epochMillis
            }
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
                // Codex и Gemini аккаунт не называют (D-100): подписка — своя у каждого слота.
                val kind = if (e.executor == com.dpsmpx.dpai.core.ids.Executor.CLAUDE) "подписка" else e.executor.title
                val label = status.plan?.let { "$kind $it (${e.label})" } ?: "$kind ${e.label}"
                o.commitAccounts(
                    accountsLogic.registerSubscription(o.accounts, s, label, clock.now(), maxParallelSessions = MAX_PARALLEL, rotationAllowed = true, executor = e.executor).valueOrNull()!!,
                )
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

    private var delivered = 0L

    /** Уведомления, которые оболочка ещё не показала: новые и обновлённые (ТЗ, §36). */
    fun drainNotifications(): List<Notification> {
        val (fresh, next) = orchestrator.postedSince(delivered)
        delivered = next
        return fresh.mapNotNull {
            when (it) {
                is NotificationDecision.Post -> it.notification
                is NotificationDecision.Update -> it.notification
                is NotificationDecision.Held -> null
            }
        }
    }

    override fun step() {
        if (!probesStarted) {
            probesStarted = true
            for (e in usable) probeSlot(e.id)
        }
        for ((target, p) in probes.entries.toList()) {
            when (val r = p.first.poll(p.second)) {
                is Outcome.Failure -> {
                    problems += r.diagnostics
                    probes.remove(target)
                    probeFailedAt[target] = clock.now().epochMillis
                }
                is Outcome.Success -> r.value?.let { status ->
                    probes.remove(target)
                    probeFailedAt.remove(target)
                    // Target ответил: прежние сетевые отказы (SSH до добавления ключа и т. п.) — уже не правда, на «Внимание» их не держать.
                    problems.removeAll { it.category == com.dpsmpx.dpai.core.diagnostics.Category.NETWORK }
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
        // Target без слота, чья проба не удалась, — проба снова раз в минуту (A-79).
        val now = clock.now().epochMillis
        for ((target, at) in probeFailedAt.entries.toList()) {
            if (target !in slotOf && target !in probes && now - at >= PROBE_RETRY_MS) probeSlot(target)
        }
        recheckLogins(now)
        pollUsage()
        // Git проекта — проверка один раз (дальше — по итогам операций контура) и снова, пока не работает, раз в минуту.
        if (project != null && orchestrator.gitOk != true && clock.now().epochMillis - lastGitCheck >= GIT_RECHECK_MS) {
            lastGitCheck = clock.now().epochMillis
            orchestrator.checkGit()
        }
        orchestrator.tick()
        if (problems.size > MAX_PROBLEMS) problems.subList(0, problems.size - MAX_PROBLEMS).clear()
    }

    /**
     * Потоки событий ходов (D-76) — прочь: систему сменила новая (другие
     * targets или проект), а поток прежней держал бы канал SSH и JVM на target.
     */
    /**
     * Контур уходит (пересборка, D-94): потоки событий закрываются, журнал —
     * на запись. Новый контур открыл тот же файл заново, и прежний экземпляр
     * со своим номером и хешем не должен дописать ни строки.
     */
    override fun close() {
        journal.close()
        streams.forEach { it.close() }
    }

    /** События активного сегмента журнала — для временной шкалы (D-37). */
    fun events(): List<EventEnvelope> = journal.records(journal.activeFrom).map { it.event }

    /**
     * События одной сессии из активного сегмента (D-63): обход журнала с
     * фильтром по потоку — без копии всех записей сегмента ради одной
     * сессии (экран чата берёт снимок раз в 1–2 с).
     */
    fun sessionEvents(id: SessionId): List<EventEnvelope> {
        val stream = com.dpsmpx.dpai.core.events.StreamRef.of(id)
        val out = ArrayList<EventEnvelope>()
        journal.forEachRecord(journal.activeFrom) { r ->
            if (r.event.stream == stream) out += r.event
            true
        }
        return out
    }

    fun diagnosis(): SelfDiagnosis = SelfDiagnostics.evaluate(signals())

    /**
     * Сигналы самодиагностики — из состояния контура, а не «всё в порядке»
     * (ревью A-84, D-13): сверка идёт, пока есть сессии `RECOVERY_PENDING`;
     * в сети — target без неудавшейся пробы входа (A-79) и без сессии, ждущей
     * сверки (target не ответил). Хранилище — по факту (D-87): журнал закрыт
     * после отказа записи или в проблемах есть отказ хранилища, значит
     * разрушительные записи остановлены, а не «всё хорошо».
     */
    internal fun signals(): SystemSignals {
        val waiting = orchestrator.sessions.sessions.values.filter { it.state == SessionState.RECOVERY_PENDING }
        val silent = waiting.map { it.target }.toSet()
        return SystemSignals(
            storageOk = storageOk(), recoveryInProgress = waiting.isNotEmpty(), gitAvailable = orchestrator.gitOk, notificationsOk = notificationsOk(),
            targetsOnline = usable.count { it.id !in probeFailedAt && it.id !in silent }, targetsTotal = usable.size,
            securityAlert = false, gitRequired = project != null,
        )
    }

    /**
     * Хранилище в порядке? `false` — журнал закрыт после отказа записи или
     * последний отказ — отказ хранилища (D-87, ТЗ §80): тогда разрушительные
     * записи останавливаются. `null` — неизвестно (D-13), а не «да».
     */
    internal fun storageOk(): Boolean? = when {
        journal.isBroken -> false
        (problems + orchestrator.problems).any { it.isError && it.code.name.startsWith("STORAGE_") } -> false
        else -> true
    }

    private var lastGitCheck = Long.MIN_VALUE / 2

    override fun dashboard(): DashboardScreen {
        val base = Screens.dashboard(orchestrator.dashboard(), diagnosis(), orchestrator.usage())
        val login = usable.filter { it.id in waitingLogin }.map { "${it.label}: войдите в Claude в каталоге ${it.configDir}, затем «Проверить вход»" } +
            usable.mapNotNull { e ->
                loginRecheckAt(e.id)?.let { at ->
                    val min = ((at - clock.now().epochMillis).coerceAtLeast(0) + 59_999) / 60_000
                    "${e.label}: Claude ответил «нужен вход» — после сбоя сети так бывает; проверю вход сам через $min мин, «Проверить вход» — сразу"
                }
            }
        val retry = usable.filter { it.id in probeFailedAt && it.id !in slotOf }
            .map { "${it.label}: проба входа не удалась — повтор раз в минуту; «Проверить вход» — сразу" }
        val extra = notes + login + retry + (problems + orchestrator.problems).takeLast(5).map { "${Words.reason(it.code.name)}: ${it.message}" }
        return base.copy(attention = base.attention + extra, limits = Screens.limits(orchestrator.accounts, clock.now(), labelOf = ::subscriptionName))
    }

    /**
     * Имя подписки для человека: тариф и нынешние имена её targets — «pro —
     * Мой слот»; имя при регистрации хранило имя target на тот момент и
     * после переименования слота устаревало бы.
     */
    fun subscriptionName(sub: com.dpsmpx.dpai.accounts.SubscriptionState): String {
        val names = orchestrator.accounts.slotsOf(sub.id).mapNotNull { s -> usable.firstOrNull { it.id == s.targetId }?.label }.distinct()
        return if (names.isEmpty()) sub.label else (sub.plan?.let { "$it — " } ?: "") + names.joinToString(", ")
    }

    override fun board(): TaskBoard = Screens.board(orchestrator.tasks)

    override fun session(id: SessionId): SessionScreen {
        val own = sessionEvents(id)
        val base = Screens.session(
            orchestrator.dashboard(), id, own, orchestrator.live(id), clock.now(), Screens.sessionActions(id, orchestrator.sessions, orchestrator.tasks),
            orchestrator.sessions.sessions[id]?.usage, orchestrator.sessions.sessions[id]?.task,
        )
        // Чат задачи сессии (D-59): все её сессии в одной ленте.
        val task = orchestrator.sessions.sessions[id]?.task ?: return base
        val record = orchestrator.sessions.sessions[id]
        val running = orchestrator.sessions.sessions.values.any {
            it.task == task && !it.terminal && it.state in setOf(com.dpsmpx.dpai.core.state.SessionState.STARTING, com.dpsmpx.dpai.core.state.SessionState.RUNNING, com.dpsmpx.dpai.core.state.SessionState.NEAR_LIMIT)
        }
        // Сколько идёт ход: от последнего начала хода этой сессии в журнале.
        val turnStart = own.lastOrNull { it.type == com.dpsmpx.dpai.core.events.EventType.SESSION_TURN_STARTED }?.occurredAt
        val working = turnStart?.takeIf { record?.state == com.dpsmpx.dpai.core.state.SessionState.RUNNING || record?.state == com.dpsmpx.dpai.core.state.SessionState.NEAR_LIMIT }
            ?.let { Screens.duration(clock.now().epochMillis - it.epochMillis) }
        return base.copy(
            title = orchestrator.tasks.tasks[task]?.title,
            chat = Screens.chat(Screens.chatEntries(orchestrator.chat(task), orchestrator.turnMessages(task), orchestrator.tasks.tasks[task]), id, clock.now()),
            status = Screens.sessionStatus(record, orchestrator.tasks.tasks[task]?.state, running), workingFor = working,
            noSend = Screens.noSend(id, orchestrator.sessions, orchestrator.tasks),
            executor = record?.let { orchestrator.accounts.slots[it.slot]?.executor } ?: com.dpsmpx.dpai.core.ids.Executor.CLAUDE,
        )
    }

    companion object {
        /**
         * Проект «без проекта» — задачи в каталоге агента. Id постоянный:
         * сохранённый контекст задачи привязан к проекту, и с новым id после
         * перезапуска контекст не собирался (найдено при исправлении A-42).
         */
        val HOME_PROJECT: com.dpsmpx.dpai.core.ids.ProjectId = IdKinds.PROJECT.trusted("prj_" + "0".repeat(26))

        private const val MAX_PROBLEMS = 50

        /** Как часто перепроверять Git, пока он не работает. */
        private const val GIT_RECHECK_MS = 60_000L

        /** Как часто повторять неудавшуюся пробу входа target без слота (A-79). */
        const val PROBE_RETRY_MS = 60_000L

        /** Первая перепроверка входа после отказа хода (D-58); дальше — вдвое, до [AUTH_RECHECK_MAX_MS]. */
        const val AUTH_RECHECK_FIRST_MS = 120_000L
        const val AUTH_RECHECK_MAX_MS = 3_600_000L

        /** Сколько слот должен быть доступен, чтобы пауза перепроверок сбросилась (D-58). */
        const val AUTH_STABLE_MS = 1_800_000L

        /** Предел одновременных сессий подписки контроллера; на target — по одной (`TargetSlot`). */
        private const val MAX_PARALLEL = 8

        /** Записей в сегменте журнала контроллера и между снимками. */
        private const val SEGMENT = 20_000
    }
}

/**
 * Журнал контроллера испорчен в середине (D-24): открыть можно, только
 * отрезав всё после испорченной записи в карантин, — а это решает человек.
 * Оболочка спрашивает его и строит контур заново с `quarantineConfirmed`.
 */
class JournalNeedsConfirmation(message: String) : IllegalStateException(message)
