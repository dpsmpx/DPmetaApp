package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.contexts.ContextAssembler
import com.dpsmpx.dpai.contexts.ContextPolicy
import com.dpsmpx.dpai.contexts.SourceLevel
import com.dpsmpx.dpai.orchestrator.efficiency.BudgetMode
import com.dpsmpx.dpai.orchestrator.efficiency.Continuation
import com.dpsmpx.dpai.orchestrator.efficiency.EfficiencyPolicy
import com.dpsmpx.dpai.orchestrator.efficiency.ModelRouter
import com.dpsmpx.dpai.orchestrator.efficiency.WorkingSet
import com.dpsmpx.dpai.security.Origin
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.sessions.PromptFacts
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.verification.VerificationReport
import com.dpsmpx.dpai.contexts.ContextBundle
import com.dpsmpx.dpai.contexts.ContextSource
import com.dpsmpx.dpai.contexts.Handoff
import com.dpsmpx.dpai.contexts.HandoffReason
import com.dpsmpx.dpai.contexts.Handoffs
import com.dpsmpx.dpai.contexts.PromptRenderer
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.ids.ContextId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.ids.WorktreeId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ClaudeCodeCli
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.git.OverlapPolicy
import com.dpsmpx.dpai.git.OverlapVerdict
import com.dpsmpx.dpai.git.WorktreeEvent
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.monitoring.Dashboard
import com.dpsmpx.dpai.monitoring.DashboardView
import com.dpsmpx.dpai.notifications.ApprovalAction
import com.dpsmpx.dpai.notifications.DeepLink
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.NotificationEngine
import com.dpsmpx.dpai.notifications.NotificationRequest
import com.dpsmpx.dpai.orchestrator.accounts.accountEnvelopes
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.orchestrator.sessions.sessionEnvelopes
import com.dpsmpx.dpai.orchestrator.tasks.taskEnvelopes
import com.dpsmpx.dpai.orchestrator.verification.CompletionFlow
import com.dpsmpx.dpai.security.Proposal
import com.dpsmpx.dpai.sessions.AccountFact
import com.dpsmpx.dpai.orchestrator.accounts.AccountsProjection
import com.dpsmpx.dpai.orchestrator.recovery.OrphanScan
import com.dpsmpx.dpai.orchestrator.recovery.Reconciler
import com.dpsmpx.dpai.orchestrator.recovery.Resolution
import com.dpsmpx.dpai.orchestrator.sessions.SessionsProjection
import com.dpsmpx.dpai.orchestrator.tasks.TasksProjection
import com.dpsmpx.dpai.orchestrator.git.WorktreesProjection
import com.dpsmpx.dpai.orchestrator.git.worktreeEnvelopes
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.contexts.ContextCodec
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.snapshot.Projection
import com.dpsmpx.dpai.storage.snapshot.SnapshotStore
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.sessions.SessionDriver
import com.dpsmpx.dpai.sessions.SessionEvent
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskEvent
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.Verifier
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.core.ids.RequestId
import com.dpsmpx.dpai.core.hashing.ContentHash
import com.dpsmpx.dpai.core.operation.Actor
import com.dpsmpx.dpai.core.operation.ActorKind
import com.dpsmpx.dpai.core.operation.OperationResult
import com.dpsmpx.dpai.core.operation.Provenance
import com.dpsmpx.dpai.notifications.AnswerCheck
import com.dpsmpx.dpai.notifications.OptionRole
import com.dpsmpx.dpai.notifications.RequestAction
import com.dpsmpx.dpai.notifications.RequestAnswer
import com.dpsmpx.dpai.notifications.RequestEvent
import com.dpsmpx.dpai.notifications.RequestKind
import com.dpsmpx.dpai.notifications.RequestNotifications
import com.dpsmpx.dpai.notifications.RequestOption
import com.dpsmpx.dpai.notifications.UserRequest
import com.dpsmpx.dpai.notifications.UserRequests
import com.dpsmpx.dpai.notifications.UserRequestsState
import com.dpsmpx.dpai.orchestrator.requests.RequestPurpose
import com.dpsmpx.dpai.orchestrator.requests.UserRequestsProjection
import com.dpsmpx.dpai.orchestrator.requests.requestEnvelopes
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** Target для планировщика: его возможности (ТЗ, §51) и предел одновременных сессий. */
data class TargetSlot(val id: ExecutionTargetId, val capabilities: Set<String>, val maxSessions: Int = 4)

/**
 * Проект под оркестратором: репозиторий (необязателен), каталог рабочих
 * деревьев, профиль проверки, ветка интеграции — слияние в неё
 * чувствительно и ждёт одобрения (ТЗ, §68).
 */
data class ProjectConfig(
    val project: ProjectId,
    val repository: Repository?,
    val worktreeRoot: String?,
    val profile: VerificationProfile?,
    val level: VerifyLevel = VerifyLevel.NORMAL,
    val integrationBranch: String? = "main",
    val sources: (TaskId) -> List<ContextSource> = { emptyList() },
)

/**
 * Полуавтономный контур (ТЗ, §19, §28, §139, Phase 14; `docs/ARCHITECTURE.md`,
 * §3.1): такт [tick] продвигает очередь, назначает готовые задачи на слоты
 * и targets, запускает сессии с контекстом, опрашивает их и разбирает
 * исходы:
 *
 * - ход завершён → проверка (D-23) → коммит → `DONE` → запрос одобрения
 *   на слияние в ветку интеграции;
 * - ход прерван (предел, тайм-аут, предел ходов) → handoff → задача снова
 *   `READY` → продолжение той же сессии провайдера в новой сессии;
 * - ход упал → повтор по классу причины (D-05), петли останавливаются;
 * - нужен вход пользователя → `BLOCKED` и уведомление.
 *
 * Каждое изменение — событие в журнале (D-12, D-24). Решения
 * детерминированы при одинаковом входе (D-22).
 */
class Orchestrator(
    private val clock: Clock,
    private val ids: IdGenerator,
    private val random: RandomSource,
    private val journal: EventJournal,
    private val runner: ClaudeRunner,
    private val config: ProjectConfig,
    private val targets: List<TargetSlot>,
    private val git: GitService?,
    private val verifier: Verifier,
    initialAccounts: AccountsState = AccountsState.EMPTY,
    /**
     * Снимки проекций (D-37): после перезапуска состояние — из снимка и
     * хвоста журнала, а не из всей истории. `null` — без снимков.
     */
    private val snapshots: SnapshotStore? = null,
    private val snapshotEvery: Long = 20_000,
    /**
     * Документы задач: последняя версия контекста и последний handoff
     * (`dpai.context`, `dpai.handoff`) — чтобы продолжение после перезапуска
     * шло с тем же контекстом и следующим действием. `null` — только в памяти.
     */
    private val documents: DocumentStore? = null,
    /** Слой экономии контекста и токенов (D-40); [EfficiencyPolicy.LEGACY] — как до него. */
    private val efficiency: EfficiencyPolicy = EfficiencyPolicy.DEFAULT,
    /** Срок запроса человеку (D-44): не ответили — `EXPIRED`, предмет остаётся видным на экране. */
    val requestTtl: Duration = 24.hours,
) {
    private val recorder = EventRecorder(ids, clock)
    private var lastSnapshotAt = 0L
    private val accountsLogic = Accounts()
    private val sessionsLogic = Sessions()
    private val tasksLogic = Tasks()
    private val driver = SessionDriver(sessionsLogic, runner, clock)
    private val completion = CompletionFlow(tasksLogic, verifier, git, clock)
    private val router = ModelRouter(efficiency.routing)
    val handoffs = Handoffs()
    private val overlap = OverlapPolicy()
    val approvals = ApprovalDesk()
    val notifications = NotificationEngine()
    val posted = mutableListOf<NotificationDecision>()

    var accounts: AccountsState = initialAccounts
        private set
    var sessions: SessionsState = SessionsState.EMPTY
        private set
    var tasks: TasksState = TasksState.EMPTY
        private set
    var worktrees: WorktreesState = WorktreesState.EMPTY
        private set
    private val treeOf = HashMap<TaskId, WorktreeId>()
    private val contextOf = HashMap<TaskId, ContextBundle>()
    /** Выжимка проваленной проверки задачи — источник `TOOL` следующей попытки (D-40); восстанавливается из версии контекста. */
    private val toolOf = HashMap<TaskId, ContextSource>()
    /** Флаги, которых не знает Claude Code target (D-40): следующие ходы идут без них. */
    private val unsupportedFlags = HashMap<ExecutionTargetId, MutableSet<String>>()

    /** Рабочий набор задачи: файлы её сессий и итог хода — для handoff свежей сессии (D-41); только в памяти. */
    private val workingOf = HashMap<TaskId, WorkingSet>()

    /** Версия контекста, ушедшая в последний ход задачи, — основа разницы при `--resume` (D-40). */
    private val sentOf = HashMap<TaskId, ContextBundle>()
    /** Ожидающие и выполненные слияния — из журнала (`MergesProjection`). */
    var mergesState: MergesState = MergesState.EMPTY
        private set

    /** Слитые задачи по порядку. */
    val merged: List<TaskId> get() = mergesState.merged.keys.toList()

    /** Одобрение каждого слияния — запись для инварианта «разрушительное — с одобрением» (ТЗ, §118). */
    val mergeApprovals: Map<TaskId, ApprovalId> get() = mergesState.merged
    val problems = mutableListOf<Diagnostic>()

    /** Запросы человеку (D-44) — из журнала. */
    var requests: UserRequestsState = UserRequestsState.EMPTY
        private set

    init {
        // Перезапуск (ТЗ, §54; D-24): состояние — из журнала. [initialAccounts] —
        // исходное состояние до журнала (его регистрация в журнал не пишется),
        // поэтому события аккаунтов проигрываются поверх него, а не поверх пустого.
        if (journal.position > 0) {
            accounts = restore(AccountsProjection, initialAccounts)
            sessions = restore(SessionsProjection, SessionsState.EMPTY)
            tasks = restore(TasksProjection, TasksState.EMPTY)
            worktrees = restore(WorktreesProjection, WorktreesState.EMPTY)
            recorder.resumeFromLast(restore(StreamsProjection, emptyMap()))
            mergesState = restore(MergesProjection, MergesState.EMPTY)
            requests = restore(UserRequestsProjection, UserRequestsState.EMPTY)
            // Неистёкшие запросы одобрения снова ждут ответа; истёкшие отвергнет сам ApprovalDesk.
            for (m in mergesState.pending.values) approvals.restore(m.approval, m.proposal, m.expiresAt)
            lastSnapshotAt = journal.position
            // Дерево задачи узнаётся по её ветке (`prepareWorktree`): владельцы и пути — из журнала.
            for (w in worktrees.worktrees.values.filter { !it.removed }) {
                w.branch.removePrefix(TASK_BRANCH).takeIf { it != w.branch }?.let { IdKinds.TASK.parse(it).valueOrNull() }?.let { treeOf[it] = w.id }
            }
        }
        documents?.let(::loadTaskDocuments)
        config.repository?.let { repo -> commitWorktrees(Worktrees.register(worktrees, repo, clock.now()).valueOrNull().orEmpty()) }
    }

    /** Ключ документа задачи: id в нижнем регистре — ключи документов строчные. */
    private fun docKey(kind: String, task: TaskId) = "$kind-${task.value.lowercase()}"

    /** Контексты и handoff незавершённых задач — из документов (после перезапуска). */
    private fun loadTaskDocuments(store: DocumentStore) {
        for (t in tasks.tasks.values.filter { !it.terminal }) {
            store.read(docKey("context", t.id)).valueOrNull()?.let { d ->
                when (val c = ContextCodec.readContext(d.content)) {
                    is Outcome.Success -> {
                        contextOf[t.id] = c.value
                        c.value.sources.firstOrNull { it.key == VERIFICATION_SOURCE }?.let { s ->
                            toolOf[t.id] = ContextSource(s.key, s.level, config.project, s.sensitivity, s.origin, s.content)
                        }
                    }
                    is Outcome.Failure -> problems += Diagnostic.of(DiagCode.STORAGE_CORRUPTED, "контекст задачи ${t.id} не читается", causes = c.errors)
                }
            }
            store.read(docKey("handoff", t.id)).valueOrNull()?.let { d ->
                when (val h = ContextCodec.readHandoff(d.content)) {
                    is Outcome.Success -> handoffs.create(h.value)
                    is Outcome.Failure -> problems += Diagnostic.of(DiagCode.STORAGE_CORRUPTED, "handoff задачи ${t.id} не читается", causes = h.errors)
                }
            }
        }
    }

    /**
     * Прежний контекст задачи из её документа — когда в памяти его нет: задача
     * была завершена и открыта снова явной операцией (`commitTasks`) или
     * приложение перезапускалось. Так у повтора — новая версия того же
     * контекста, а не новый контекст (`docs/CONTEXT_MODEL.md`).
     */
    private fun storedContext(task: TaskId, report: Boolean = true): ContextBundle? {
        val d = documents?.read(docKey("context", task))?.valueOrNull() ?: return null
        return when (val c = ContextCodec.readContext(d.content)) {
            is Outcome.Success -> c.value
            is Outcome.Failure -> null.also {
                if (report) problems += Diagnostic.of(DiagCode.STORAGE_CORRUPTED, "контекст задачи $task не читается", causes = c.errors)
            }
        }
    }

    /** Документ задачи — до того, как журнал запишет шаг, который на него опирается. */
    private fun saveTaskDocument(kind: String, task: TaskId, json: com.dpsmpx.dpai.core.serialization.JsonObject) {
        val r = documents?.write(docKey(kind, task), json) ?: return
        if (r is Outcome.Failure) problems += Diagnostic.of(DiagCode.STORAGE_IO_FAILED, "документ $kind задачи $task не записан", causes = r.errors)
    }

    /** Проекция из снимка и хвоста журнала, а без снимка — из всей истории потоком (D-37). */
    private fun <S> restore(p: Projection<S>, initial: S): S =
        snapshots?.let { StateRecovery(p).recover(journal, it, initial).valueOrNull()?.state } ?: StateRecovery(p).replayAll(journal, initial)

    /**
     * Снимки всех проекций, если с прошлого прошло [snapshotEvery] записей.
     * Зовётся между шагами, когда память совпадает с журналом (пачек нет).
     */
    private fun maybeSnapshot() {
        val store = snapshots ?: return
        if (pending != null || journal.position - lastSnapshotAt < snapshotEvery) return
        fun <S> save(p: Projection<S>, state: S) {
            val r = store.save(StateRecovery(p).snapshotOf(journal, state))
            if (r is Outcome.Failure) problems += Diagnostic.of(DiagCode.STORAGE_IO_FAILED, "снимок ${p.name} не записан", causes = r.errors)
        }
        save(AccountsProjection, accounts)
        save(SessionsProjection, sessions)
        save(TasksProjection, tasks)
        save(WorktreesProjection, worktrees)
        save(StreamsProjection, recorder.lastSequences())
        save(MergesProjection, mergesState)
        save(UserRequestsProjection, requests)
        lastSnapshotAt = journal.position
    }

    // ---- журнал ----

    /** Конверты, ждущие записи внутри [batch]; вне пачки — `null`, запись сразу. */
    private var pending: MutableList<EventEnvelope>? = null

    /** Пачка событий одного шага — одна групповая запись: один fsync (этап 17, `docs/PERFORMANCE.md`). */
    private fun write(envs: List<EventEnvelope>) {
        if (envs.isEmpty()) return
        pending?.let { it += envs; return }
        val r = journal.appendAll(envs)
        check(r.isSuccess) { "журнал не принял пачку: ${r.diagnostics}" }
    }

    /**
     * Все записи [body] — одной пачкой в конце. Внешних действий внутри
     * пачки нет: всё, что должно лечь в журнал раньше вызова target (id
     * сессии провайдера, аренда), пишется отдельной пачкой до него.
     */
    private fun <T> batch(body: () -> T): T {
        check(pending == null) { "пачки не вкладываются" }
        val acc = mutableListOf<EventEnvelope>()
        pending = acc
        try {
            return body()
        } finally {
            pending = null
            write(acc)
        }
    }

    fun commitAccounts(es: List<AccountEvent>) {
        write(recorder.accountEnvelopes(es))
        accounts = Accounts.applyAll(accounts, es)
    }

    private fun commitSessions(es: List<SessionEvent>) {
        write(recorder.sessionEnvelopes(es))
        sessions = Sessions.applyAll(sessions, es)
    }

    private fun commitWorktrees(es: List<WorktreeEvent>) {
        write(recorder.worktreeEnvelopes(es))
        worktrees = Worktrees.applyAll(worktrees, es)
    }

    private fun commitMerges(es: List<MergeEvent>) {
        write(recorder.mergeEnvelopes(es))
        mergesState = es.fold(mergesState, MergesProjection::applyEvent)
    }

    private fun commitRequests(es: List<RequestEvent>) {
        write(recorder.requestEnvelopes(es))
        requests = UserRequests.applyAll(requests, es)
        // Закрытый запрос: следующий по тому же предмету — новое уведомление, а не обновление старого.
        for (e in es) if (requests.requests[e.request]?.terminal == true) requests.requests[e.request]?.let { notifications.resolve(RequestNotifications.key(it)) }
    }

    private fun commitTasks(es: List<TaskEvent>) {
        write(recorder.taskEnvelopes(es))
        tasks = Tasks.applyAll(tasks, es)
        // Завершённая задача не держит контекст в памяти (ТЗ, §116: память не
        // растёт с числом выполненных задач — EnduranceTest), если он есть в её
        // документе: явный повтор продолжит его версии (`context`). Без хранилища
        // документов контекст живёт только в памяти — и остаётся там.
        for (id in es.map { it.task }.toSet()) {
            if (tasks.tasks[id]?.terminal == true) {
                if (documents != null) contextOf.remove(id)
                lastOverlap.remove(id)
                toolOf.remove(id)
                sentOf.remove(id)
                workingOf.remove(id)
            }
        }
    }

    private fun <T> ok(o: Outcome<T>, what: String): T? = when (o) {
        is Outcome.Success -> o.value
        is Outcome.Failure -> null.also { problems += Diagnostic.of(DiagCode.INTERNAL_ERROR, what, causes = o.errors) }
    }

    // ---- команды ----

    /**
     * [scope] — заявленные пути, которые задача затронет (ТЗ, §31); `null` —
     * неизвестно, пересечения с другими задачами не проверяются.
     */
    fun addTask(
        title: String,
        objective: String,
        priority: Priority = Priority.NORMAL,
        dependencies: Set<TaskId> = emptySet(),
        capabilities: Set<String> = emptySet(),
        scope: Set<String>? = null,
        policy: TaskPolicy = TaskPolicy(),
    ): Outcome<TaskId> {
        // Та же задача уже в очереди — второй ход с той же работой (D-40; аудит, W10).
        if (efficiency.rejectDuplicateTasks) {
            tasksLogic.duplicateOf(tasks, title, objective)?.let { other ->
                return Outcome.failure(DiagCode.TASK_DUPLICATE, "та же задача уже в очереди: $other", "task $other")
            }
        }
        val id = ids.next(IdKinds.TASK)
        val created = tasksLogic.create(tasks, id, title, objective, priority, clock.now(), dependencies, capabilities, policy)
        if (created is Outcome.Failure) return created
        val withScope = scope?.let { s ->
            when (val r = Tasks.setScope(Tasks.applyAll(tasks, created.valueOrNull()!!), id, s, clock.now())) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
        }.orEmpty()
        commitTasks(created.valueOrNull()!! + withScope)
        commitTasks(tasksLogic.enqueue(tasks, id, clock.now()).valueOrNull()!!)
        return Outcome.success(id)
    }

    /** Такт контура. */
    fun tick() {
        commitTasks(tasksLogic.promote(tasks, clock.now()))
        val offers = targets.map { t ->
            val active = sessions.sessions.values.count { !it.terminal && it.target == t.id }
            Tasks.TargetOffer(t.id, t.capabilities, (t.maxSessions - active).coerceAtLeast(0))
        }
        for (a in tasksLogic.plan(tasks, clock.now(), offers)) if (overlapAllows(a.task)) launch(a.task, a.target)
        for (s in sessions.sessions.values.filter { it.state !in WAIT_STATES && !it.terminal }.map { it.id }.sorted()) poll(s)
        settleRequests()
        // Придержанное пределом частоты — сводкой, когда предел позволяет (ТЗ, §36).
        notifications.flush(clock.now())?.let { posted += it }
        maybeSnapshot()
    }

    private fun List<SessionId>.sorted() = sortedBy { it.value }

    /**
     * Пересечение области задачи с идущими (ТЗ, §31; `OverlapPolicy`): общий
     * файл — задача ждёт (`SERIALIZE`; критичный файл — `REJECT`, тоже
     * ждёт, но с предупреждением о риске), общий каталог — запуск с
     * предупреждением (`WARN`). Неизвестная область — без проверки.
     */
    private fun overlapAllows(task: TaskId): Boolean {
        val touched = tasks.tasks[task]?.scope ?: return true
        val others = sessions.sessions.values.filter { !it.terminal && it.state !in WAIT_STATES && it.task != task }
            .mapNotNull { s -> tasks.tasks[s.task]?.scope?.let { s.id to it } }.toMap()
        val r = overlap.evaluate(touched, others)
        // Уведомление — при смене вердикта, а не на каждом такте ожидания.
        if (lastOverlap.put(task, r.verdict) != r.verdict) {
            val files = r.with.values.flatten().toSortedSet().take(5).joinToString()
            val key = "task:${task.value}:overlap"
            when (r.verdict) {
                OverlapVerdict.ALLOW -> Unit
                OverlapVerdict.WARN -> notify(key, NotificationCategory.WARNING, "Возможный конфликт", "общий каталог с идущей задачей: $files")
                OverlapVerdict.SERIALIZE -> notify(key, NotificationCategory.INFO, "Задача ждёт", "те же файлы правит идущая задача: $files")
                OverlapVerdict.REJECT -> notify(key, NotificationCategory.WARNING, "Задача ждёт: критичный файл", "критичный файл правит идущая задача: $files")
            }
        }
        return r.verdict == OverlapVerdict.ALLOW || r.verdict == OverlapVerdict.WARN
    }

    private val lastOverlap = HashMap<TaskId, OverlapVerdict>()

    private fun launch(task: TaskId, target: ExecutionTargetId) {
        val t = tasks.tasks.getValue(task)
        val session = ids.next(IdKinds.SESSION)
        val lease = accountsLogic.lease(accounts, session, task, clock.now(), target)
        if (lease is Outcome.Failure) return // свободного слота нет: задача ждёт следующего такта
        val slot = lease.valueOrNull()!!.slot
        val budgetMode = budgetMode(slot)
        if (budgetMode == BudgetMode.FRUGAL && efficiency.budget.deferBelow?.let { t.priority.ordinal > it.ordinal } == true) {
            // Подписка у предела (D-30): несрочное ждёт сброса окна (D-40, п. 5). Аренда не записана — снимать нечего.
            notify("task:${task.value}:deferred", NotificationCategory.INFO, "Задача ждёт", "подписка у предела: приоритет ${t.priority} ждёт сброса окна")
            return
        }
        // Продолжение — прерванная сессия той же задачи или сессия, чья работа не прошла проверку (D-40).
        // Последняя сессия задачи: продолжение создаётся в ту же миллисекунду, что и handoff, — при равном
        // времени решает id (монотонен), а не порядок обхода (разница при `--resume` теряла бы версию).
        val previous = sessions.sessions.values.filter { it.task == task }
            .maxWithOrNull(compareBy<com.dpsmpx.dpai.sessions.SessionRecord>({ it.createdAt.epochMillis }, { it.turn }, { it.id.value }))
        val handoff = handoffs.latest(task)
        val continues = previous?.takeIf {
            (it.state == SessionState.CANCELLED && it.lastReason == DiagCode.SESSION_HANDED_OFF) ||
                (efficiency.feedVerification && it.state == SessionState.COMPLETED && handoff?.session == it.id && handoff.reason == HandoffReason.VERIFICATION_FAILED)
        }
        val plan = Continuation.plan(
            continues, handoff?.takeIf { it.session == continues?.id }, sessions, slot, target, efficiency.resume, efficiency.context.defaultWindow,
        )
        val provider = continues?.provider?.takeIf { plan.reuseProvider } ?: ProviderSessionId.newUuid(random)
        val attempt = sessions.sessions.values.count { it.task == task } + 1
        // Пачка 1 — до вызова target: аренда и сессия с id провайдера (сверка после сбоя узнаёт поток).
        batch {
            commitAccounts(lease.valueOrNull()!!.events)
            ok(sessionsLogic.create(sessions, accounts, session, "task:${task.value}:attempt:$attempt", task, slot, target, provider, clock.now(), continues?.id), "сессия задачи $task не создана")
                ?.also { commitSessions(it.events) }
        } ?: return releaseLease(session)
        val workDir = prepareWorktree(task, session) ?: return failLaunch(task, session, DiagCode.WORKTREE_MISSING)
        val window = sessions.sessions.values.filter { it.task == task }.mapNotNull { it.usage.contextWindow }.maxOrNull()
        // Версия, которую видела продолжаемая сессия (а не последняя собранная); неизвестна — полный prompt.
        val seen = if (plan.mode == PromptMode.DELTA) continues?.let { sentContext(task, it) } else null
        val promptMode = if (plan.mode == PromptMode.DELTA && seen == null) PromptMode.FULL else plan.mode
        val why = if (promptMode != plan.mode) "версия, которую видела сессия, неизвестна — полный prompt" else plan.why
        // Сессия провайдера продолжается — рабочий набор и итог хода она помнит сама (D-41).
        val bundle = when (val c = context(task, t.objective, efficiency.context.policy(window, budgetMode), workingSet = !plan.reuseProvider)) {
            is Outcome.Success -> c.value
            is Outcome.Failure -> return contextFailed(task, session, c)
        }
        val composed = PromptRenderer.compose(bundle, seen?.sources?.associate { it.key to it.hash }.orEmpty())
        sentOf[task] = bundle
        val route = router.route(t, budgetMode)
        val budget = t.policy.maxBudgetUsdCents?.let { BigDecimal(it).movePointLeft(2) }
        val unknown = unsupportedFlags[target].orEmpty()
        val request = ClaudeRequest(
            composed.text, model = route.model,
            effort = route.effort.takeIf { "--effort" !in unknown },
            fallbackModel = route.fallbackModel.takeIf { "--fallback-model" !in unknown },
            shareSystemPromptCache = efficiency.shareSystemPromptCache && "--exclude-dynamic-system-prompt-sections" !in unknown,
            permissionMode = ClaudeCodeCli.TURN_PERMISSION_MODE,
            disallowedTools = if (route.subagents) emptyList() else listOf(ClaudeCodeCli.SUBAGENT_TOOL),
            workDir = workDir, timeout = t.policy.turnTimeout, maxBudgetUsd = budget,
        )
        val facts = PromptFacts(
            promptMode, composed.text.length.toLong(), composed.estimatedTokens, composed.sources, composed.omitted, composed.omittedTokens,
            composed.reusedTokens, tag(bundle), t.policy.taskClass.name, route.model, route.effort, route.route, why,
        )
        commitSessions(listOf(SessionEvent.PromptPrepared(session, facts, clock.now())))
        if (route.downgraded) {
            notify("task:${task.value}:budget", NotificationCategory.WARNING, "Задача удешевлена", "подписка у предела: ${t.policy.taskClass} — ${route.model ?: "модель аккаунта"}, усилие ${route.effort ?: "по умолчанию"}")
        }
        val step = ok(driver.launch(sessions, accounts, session, request), "сессия $session не запущена") ?: return failLaunch(task, session, DiagCode.SESSION_FAILED)
        // Пачка 2 — после вызова: состояние сессии, факты аккаунтов, задача в работе.
        batch {
            commitSessions(step.events)
            applyFacts(step.facts)
            commitTasks(tasksLogic.start(tasks, task, clock.now()).valueOrNull().orEmpty())
        }
        // Сессия могла умереть уже при старте (target недоступен): задача не должна остаться
        // RUNNING без живой сессии — реакция как при опросе (прогон выносливости 7 октября 2026).
        val after = sessions.sessions.getValue(session)
        if (after.state != SessionState.RUNNING && after.state != SessionState.STARTING) react(task, session, after.state)
        else progress(task, NotificationCategory.PROGRESS, "Задача начата", "попытка $attempt")
    }

    /** Метка версии контекста в `SESSION_PROMPT_PREPARED`: по ней узнаётся, что видела сессия. */
    private fun tag(b: ContextBundle) = "${b.id.value}@v${b.version}"

    /**
     * Версия контекста, которую получила сессия [prev]: последняя посланная
     * задачей или записанная в документе — если её метка совпала с меткой
     * prompt сессии. Иначе `null`: что видела сессия, неизвестно.
     */
    private fun sentContext(task: TaskId, prev: com.dpsmpx.dpai.sessions.SessionRecord): ContextBundle? {
        val want = prev.usage.lastPrompt?.context ?: return null
        return sentOf[task]?.takeIf { tag(it) == want } ?: (contextOf[task] ?: storedContext(task, report = false))?.takeIf { tag(it) == want }
    }

    /** Режим бюджета (D-40, п. 5): подписка слота у предела — `FRUGAL`. */
    private fun budgetMode(slot: com.dpsmpx.dpai.core.ids.AccountId): BudgetMode {
        if (!efficiency.budget.enabled) return BudgetMode.NORMAL
        val sub = accounts.slots[slot]?.subscriptionId?.let { accounts.subscriptions[it] } ?: return BudgetMode.NORMAL
        return if (sub.nearAt(clock.now())) BudgetMode.FRUGAL else BudgetMode.NORMAL
    }

    /**
     * Контекст не собрался. Критичное не влезает в бюджет — задача
     * `BLOCKED` с уведомлением: повтор того же отказ не изменит (D-40);
     * иначе — как прежде, запуск не идёт.
     */
    private fun contextFailed(task: TaskId, session: SessionId, failure: Outcome.Failure) {
        problems += Diagnostic.of(DiagCode.INTERNAL_ERROR, "контекст задачи $task не собран", causes = failure.errors)
        if (failure.errors.none { it.code == DiagCode.CONTEXT_BUDGET_EXCEEDED }) return failLaunch(task, session, DiagCode.VALUE_INVALID)
        failLaunch(task, session, DiagCode.CONTEXT_BUDGET_EXCEEDED)
        commitTasks(tasksLogic.move(tasks, task, TaskState.BLOCKED, clock.now(), DiagCode.CONTEXT_BUDGET_EXCEEDED).valueOrNull().orEmpty())
        askBlocked(task, null, "правила или требования задачи не влезают в бюджет контекста")
    }

    private fun failLaunch(task: TaskId, session: SessionId, code: DiagCode) {
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), code).valueOrNull().orEmpty())
        releaseLease(session)
        // Дерево могло быть уже захвачено этой сессией: завершённая сессия его не держит (инвариант worktree-owner).
        releaseTree(task, session)
        problems += Diagnostic.error(code, "запуск задачи $task не удался", "task $task")
    }

    private fun releaseLease(session: SessionId) = commitAccounts(accountsLogic.release(accounts, session, clock.now()).valueOrNull().orEmpty())

    /** Дерево задачи: своё, одно на задачу, захвачено сессией (ТЗ, §29, §118). Без репозитория — каталог проекта не задан. */
    private fun prepareWorktree(task: TaskId, session: SessionId): String? {
        val repo = config.repository ?: return config.worktreeRoot ?: "."
        val g = git ?: return null
        val tree = treeOf[task] ?: run {
            val id = ids.next(IdKinds.WORKTREE)
            val path = "${config.worktreeRoot}/${task.value}"
            val branch = "$TASK_BRANCH${task.value}"
            val ev = ok(Worktrees.create(worktrees, id, repo.id, path, branch, clock.now()), "дерево задачи не зарегистрировано") ?: return null
            if (ok(g.run(GitOp.WORKTREE_ADD, repo.root, listOf(path, branch)), "git worktree add") == null) return null
            commitWorktrees(ev)
            treeOf[task] = id
            id
        }
        commitWorktrees(ok(Worktrees.claim(worktrees, tree, session, clock.now()), "дерево занято") ?: return null)
        return worktrees.worktrees.getValue(tree).path
    }

    /** Контекст хода (`docs/CONTEXT_MODEL.md`, §2; `docs/EFFICIENCY.md`, §3): источники проекта, handoff, итог проверки. */
    private fun context(task: TaskId, objective: String, policy: ContextPolicy, workingSet: Boolean = true): Outcome<ContextBundle> {
        val previous = contextOf[task] ?: storedContext(task)
        val handoff = handoffs.latest(task)
        val sources = config.sources(task) + listOfNotNull(environment(), handoff?.asSource(workingSet), toolOf[task])
        val out = ContextAssembler.assemble(
            previous?.id ?: ids.next(IdKinds.CONTEXT), task, config.project, objective, handoff?.nextAction, sources, clock.now(), previous, policy,
        )
        val bundle = out.valueOrNull() ?: return out
        if (contextOf[task] !== bundle) saveTaskDocument("context", task, bundle.toJson())
        contextOf[task] = bundle
        return out
    }

    private fun poll(session: SessionId) {
        val before = sessions.sessions.getValue(session).state
        val step = driver.poll(sessions, session).valueOrNull() ?: return
        if (efficiency.workingSet.enabled && step.turns.isNotEmpty()) {
            val task = sessions.sessions.getValue(session).task
            val ws = workingOf.getOrPut(task) {
                // После перезапуска — с рабочего набора последнего handoff: он не теряется при следующем.
                WorkingSet(treePath(task) ?: config.worktreeRoot ?: ".").apply { handoffs.latest(task)?.let { seed(it.relevantFiles, it.summary) } }
            }
            step.turns.forEach { ws.add(it, efficiency.workingSet.maxSummaryChars) }
        }
        if (step.unsupportedFlags.isNotEmpty()) {
            val target = sessions.sessions.getValue(session).target
            unsupportedFlags.getOrPut(target) { linkedSetOf() } += step.unsupportedFlags
            problems += Diagnostic.warning(DiagCode.CLAUDE_FLAG_UNSUPPORTED, "Claude Code на target не знает ${step.unsupportedFlags.joinToString()}: дальше без них", "target $target")
        }
        batch {
            commitSessions(step.events)
            applyFacts(step.facts)
        }
        val after = sessions.sessions.getValue(session)
        if (after.state != before) react(after.task, session, after.state)
    }

    private fun react(task: TaskId, session: SessionId, state: SessionState) {
        when (state) {
            SessionState.COMPLETED -> finish(task, session)
            SessionState.PAUSED -> handOff(task, session)
            SessionState.FAILED -> {
                releaseTree(task, session)
                val code = sessions.sessions.getValue(session).lastReason ?: DiagCode.SESSION_FAILED
                commitTasks(ok(tasksLogic.attemptFailed(tasks, task, code, code.retry, "${task.value}:${code.name}", clock.now()), "повтор задачи").orEmpty())
                when (tasks.tasks[task]?.state) {
                    TaskState.BLOCKED -> askBlocked(task, session, "попытка не удалась: ${tasks.tasks[task]?.lastReason ?: code}")
                    TaskState.FAILED -> progress(task, NotificationCategory.FAILURE, "Задача не выполнена", "попытки кончились: $code")
                    else -> Unit
                }
            }
            SessionState.BLOCKED -> {
                val reason = sessions.sessions.getValue(session).lastReason
                commitTasks(tasksLogic.move(tasks, task, TaskState.BLOCKED, clock.now(), reason).valueOrNull().orEmpty())
                askBlocked(task, session, if (reason == DiagCode.CLAUDE_AUTH_REQUIRED) "нужен вход в Claude на target" else "нужно вмешательство: ${reason ?: "без причины"}")
            }
            else -> Unit
        }
    }

    /** Ход завершён: проверка, коммит, `DONE`, запрос слияния (сценарий V1 4). */
    private fun finish(task: TaskId, session: SessionId) {
        val tree = treeOf[task]?.let { worktrees.worktrees.getValue(it) }
        val path = tree?.path ?: config.worktreeRoot ?: "."
        val flow = ok(completion.onClaimedDone(tasks, task, config.profile, config.level, path), "проверка задачи") ?: return
        commitTasks(flow.events)
        when (tasks.tasks.getValue(task).state) {
            TaskState.VERIFIED -> {
                if (tree != null && git != null) commitWork(task, session, path)
                commitTasks(ok(tasksLogic.complete(tasks, task, clock.now()), "завершение задачи").orEmpty())
                releaseTree(task, session)
                progress(task, NotificationCategory.SUCCESS, "Задача выполнена", "проверка прошла")
                if (tree != null) requestMerge(task, tree.branch)
            }
            TaskState.COMPLETION_UNVERIFIED -> {
                releaseTree(task, session)
                askUnverified(task, session)
            }
            else -> {
                // READY: проверка не прошла — следующий исполнитель с тем же деревом; что упало — ему (D-40).
                if (efficiency.feedVerification) verificationFailed(task, session, flow.report, path)
                releaseTree(task, session)
            }
        }
    }

    /**
     * Проверка не прошла (D-40, п. 3): выжимка — источник `TOOL` следующей
     * попытки, handoff — что упало, что изменено, что делать; версия
     * контекста записывается сразу — перезапуск между попытками выжимку не
     * теряет. Следующая попытка продолжает ту же сессию провайдера, если
     * это разумно (`Continuation`).
     */
    private fun verificationFailed(task: TaskId, session: SessionId, report: VerificationReport, path: String) {
        val digest = report.failureDigest() ?: return
        toolOf[task] = ContextSource(VERIFICATION_SOURCE, SourceLevel.TOOL, config.project, Sensitivity.INTERNAL, Origin.REPOSITORY, digest)
        val failed = report.steps.filter { it.required && it.result == com.dpsmpx.dpai.verification.StepResult.FAILED }
        val passed = report.steps.filter { it.result == com.dpsmpx.dpai.verification.StepResult.PASSED }
        val bundle = contextOf[task]
        val modified = changedFiles(path)
        val handoff = Handoff(
            id = ids.next(IdKinds.HANDOFF),
            key = "${session.value}:verification",
            reason = HandoffReason.VERIFICATION_FAILED,
            session = session,
            task = task,
            project = config.project,
            context = bundle?.id ?: ids.next(IdKinds.CONTEXT),
            contextHash = bundle?.hash ?: com.dpsmpx.dpai.core.hashing.Hashing.sha256(""),
            state = "ход заявил «готово», проверка ${report.level} не прошла",
            completed = emptyList(), verified = passed.map { "шаг ${it.name}" },
            failed = failed.map { "шаг ${it.name}: код ${it.exitCode ?: "?"}" },
            warnings = emptyList(), openProblems = emptyList(), currentAction = null,
            nextAction = "исправить то, что не прошло в проверке (выжимка — источник $VERIFICATION_SOURCE), и снова проверить",
            modifiedFiles = modified, commit = null, branch = treeOf[task]?.let { worktrees.worktrees[it]?.branch },
            workspace = treeOf[task]?.let { worktrees.worktrees[it]?.path }, createdAt = clock.now(),
            relevantFiles = relevantFiles(task, modified), summary = workingOf[task]?.summary,
        )
        handoffs.create(handoff)
        saveTaskDocument("handoff", task, handoff.toJson())
        context(task, tasks.tasks.getValue(task).objective, efficiency.context.policy(null))
    }

    /**
     * Что среда делает после хода сама (D-40, п. 1; `docs/EFFICIENCY.md`,
     * §1): модель принимает решения, а механическую работу — проверку,
     * список изменений, коммит, handoff — делает оркестратор. Источник
     * одинаков для всех задач проекта — устойчивое начало prompt.
     */
    private fun environment(): ContextSource? {
        if (!efficiency.environmentFacts) return null
        val steps = config.profile?.levels?.get(config.level).orEmpty()
        val text = buildString {
            appendLine("После хода оркестратор сам:")
            if (steps.isNotEmpty()) {
                appendLine("- запустит проверку (${config.level}): ${steps.joinToString("; ") { "${it.name} — `${it.command.joinToString(" ")}`" }};")
                appendLine("  не прошла — следующий ход получит выжимку того, что упало;")
            }
            if (config.repository != null) appendLine("- узнает изменённые файлы из `git status` и закоммитит их после проверки;")
            appendLine("- сохранит handoff, если ход прервётся.")
            appendLine("Поэтому полный прогон проверки ради подтверждения и коммит не нужны: запускай то, что нужно для решения.")
            append("Итог хода — коротко: что сделано, что нет и почему.")
        }
        return ContextSource(ENVIRONMENT_SOURCE, SourceLevel.PROJECT, config.project, Sensitivity.INTERNAL, Origin.ORCHESTRATOR, text)
    }

    /** Путь рабочего дерева задачи; дерева нет — `null`. */
    private fun treePath(task: TaskId): String? = treeOf[task]?.let { worktrees.worktrees[it]?.path }

    /** Рабочий набор для handoff (D-41): без файлов, уже названных изменёнными. */
    private fun relevantFiles(task: TaskId, modified: List<String>): List<String> =
        if (!efficiency.workingSet.enabled) emptyList() else workingOf[task]?.files(efficiency.workingSet.maxFiles, modified).orEmpty()

    /** Изменённые и новые файлы рабочего дерева — факт `git status`, а не заявление хода (D-23). */
    private fun changedFiles(path: String): List<String> {
        if (!efficiency.enrichHandoff) return emptyList()
        val st = git?.status(path)?.valueOrNull() ?: return emptyList()
        return (st.changed + st.untracked).distinct().sorted().take(MAX_HANDOFF_FILES)
    }

    private fun commitWork(task: TaskId, session: SessionId, path: String) {
        val g = git ?: return
        val status = g.status(path).valueOrNull() ?: return
        val files = (status.changed + status.untracked).distinct().sorted()
        if (files.isEmpty()) return
        val tree = treeOf.getValue(task)
        ok(g.write(worktrees, tree, session, GitOp.ADD, files), "git add")
        ok(g.write(worktrees, tree, session, GitOp.COMMIT, listOf("dpai: ${tasks.tasks.getValue(task).title}")), "git commit")
    }

    private fun requestMerge(task: TaskId, branch: String) {
        val repo = config.repository ?: return
        val target = config.integrationBranch ?: return
        val proposal = Proposal("git.merge", "${repo.root}#$target", listOf(branch))
        val id = ids.next(IdKinds.APPROVAL)
        // Сначала журнал: после перезапуска запрос снова ждёт ответа.
        commitMerges(listOf(MergeEvent.Requested(PendingMerge(task, id, proposal, clock.now() + approvals.ttl), clock.now())))
        notify(approvals.request(id, proposal, clock.now()))
    }

    /**
     * Ответ на кнопку одобрения (ТЗ, §37, §68): слияние ветки задачи в ветку
     * интеграции — только по действительному одобрению своего предложения.
     */
    fun respond(uri: String, by: String): Outcome<Boolean> {
        val approval = when (val r = approvals.respond(uri, by, clock.now())) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (approval == null) {
            // Отказ: запрос снят — и в журнале, чтобы после перезапуска он не ждал ответа снова.
            ApprovalAction.parse(uri).valueOrNull()?.let { a -> mergesState.pending[a.request] }?.let { m ->
                commitMerges(listOf(MergeEvent.Declined(m.task, m.approval, clock.now())))
            }
            return Outcome.success(false)
        }
        val pending = mergesState.pending[approval.id] ?: return Outcome.failure(DiagCode.APPROVAL_INVALID, "одобрение не к слиянию")
        if (pending.proposal.hash != approval.proposalHash) return Outcome.failure(DiagCode.APPROVAL_INVALID, "одобрено другое предложение")
        val repo = config.repository!!
        val g = git ?: return Outcome.failure(DiagCode.UNSUPPORTED, "нет Git")
        return when (val m = g.run(GitOp.MERGE, repo.root, pending.proposal.arguments)) {
            is Outcome.Failure -> m
            is Outcome.Success -> Outcome.success(true).also { commitMerges(listOf(MergeEvent.Merged(pending.task, approval.id, clock.now()))) }
        }
    }

    /** Ход прерван: handoff, прежняя сессия снята, задача ждёт продолжения (сценарий V1 2). */
    private fun handOff(task: TaskId, session: SessionId, by: HandoffReason? = null, next: String? = null) {
        val s = sessions.sessions.getValue(session)
        val bundle = contextOf[task]
        val modified = treePath(task)?.let(::changedFiles).orEmpty()
        val handoff = Handoff(
            id = ids.next(IdKinds.HANDOFF),
            key = "${session.value}:${if (by == null) "handoff" else "user"}",
            reason = by ?: when (s.lastReason) {
                DiagCode.CLAUDE_RATE_LIMITED -> HandoffReason.ACCOUNT_EXHAUSTED
                DiagCode.CLAUDE_CONTEXT_OVERFLOW -> if (efficiency.resume.enabled) HandoffReason.CONTEXT_OVERFLOW else HandoffReason.NEAR_LIMIT
                else -> HandoffReason.NEAR_LIMIT
            },
            session = session,
            task = task,
            project = config.project,
            context = bundle?.id ?: ids.next(IdKinds.CONTEXT),
            contextHash = bundle?.hash ?: com.dpsmpx.dpai.core.hashing.Hashing.sha256(""),
            state = "ход ${s.turn} прерван: ${s.lastReason ?: "без причины"}",
            completed = emptyList(), verified = emptyList(), failed = emptyList(),
            warnings = listOfNotNull(s.lastReason?.name), openProblems = emptyList(), currentAction = null,
            nextAction = next ?: "продолжить с места остановки в той же сессии",
            modifiedFiles = modified,
            commit = null, branch = treeOf[task]?.let { worktrees.worktrees[it]?.branch },
            workspace = treePath(task), createdAt = clock.now(),
            relevantFiles = relevantFiles(task, modified), summary = workingOf[task]?.summary,
        )
        handoffs.create(handoff)
        saveTaskDocument("handoff", task, handoff.toJson())
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), DiagCode.SESSION_HANDED_OFF).valueOrNull().orEmpty())
        releaseLease(session)
        releaseTree(task, session)
        commitTasks(ok(tasksLogic.continueLater(tasks, task, clock.now()), "продолжение задачи").orEmpty())
    }

    private fun releaseTree(task: TaskId, session: SessionId) {
        val tree = treeOf[task] ?: return
        commitWorktrees(Worktrees.release(worktrees, tree, session, clock.now()).valueOrNull().orEmpty())
    }

    private fun applyFacts(facts: List<AccountFact>) {
        for (f in facts) {
            val slot = accounts.slotOf(f.session)?.id ?: continue
            val out = when (f) {
                is AccountFact.Release -> accountsLogic.release(accounts, f.session, clock.now())
                is AccountFact.Success -> accountsLogic.observeSuccess(accounts, slot, clock.now())
                is AccountFact.AuthFailure -> accountsLogic.observeAuthFailure(accounts, slot, clock.now())
                is AccountFact.Error -> accountsLogic.observeError(accounts, slot, f.code, clock.now())
                is AccountFact.Limit -> accountsLogic.observeLimit(accounts, slot, f.window, f.level, f.resetAt, clock.now())
            }
            commitAccounts(out.valueOrNull().orEmpty())
        }
    }

    private fun notify(key: String, category: NotificationCategory, title: String, body: String) =
        notify(NotificationRequest(key, category, title, body, null, clock.now()))

    private fun notify(r: NotificationRequest) {
        posted += notifications.submit(r)
    }

    /**
     * Сверка после перезапуска (ТЗ, §54, §55; `docs/RECOVERY.md`, §1):
     * сессии, которые по журналу работали, — в `RECOVERY_PENDING`, затем
     * проба агента их target. Подтверждённо идущий ход подхватывает раннер
     * с записанной позиции; завершённые освобождают слот, и задача
     * реагирует на исход как при опросе. Target без агента ([agentFor] —
     * `null`) или не ответивший — сессия ждёт следующей сверки.
     */
    fun recover(agentFor: (ExecutionTargetId) -> AgentClient?): List<Reconciler.Result> {
        val reconciler = Reconciler(sessionsLogic, clock)
        commitSessions(reconciler.markPending(sessions))
        val out = mutableListOf<Reconciler.Result>()
        for (r in sessions.sessions.values.filter { it.state == SessionState.RECOVERY_PENDING }.sortedBy { it.id.value }) {
            val agent = agentFor(r.target) ?: continue
            val res = reconciler.reconcile(sessions, r.id, agent)
            out += res
            commitSessions(res.events)
            val after = sessions.sessions.getValue(r.id)
            if (res.resolution == Resolution.RESOLVED_RUNNING) runner.adopt(r.id, r.provider, r.agentSequence, turnRunning = true)
            if (after.terminal) applyFacts(listOf(AccountFact.Release(r.id)))
            if (after.state != SessionState.RECOVERY_PENDING) react(after.task, r.id, after.state)
        }
        // Сироты (ТЗ, §119; `docs/RECOVERY.md`, §5): аренды и деревья завершённых сессий, задачи
        // RUNNING без живой сессии — безопасные починки сразу, остальное — вопросом.
        val orphans = OrphanScan(accountsLogic, tasksLogic).scan(sessions, accounts, tasks, worktrees, emptySet(), clock.now())
        batch {
            commitAccounts(orphans.accountFixes)
            commitWorktrees(orphans.worktreeFixes)
            commitTasks(orphans.taskFixes)
        }
        problems += orphans.questions
        for (q in orphans.questions) askRecovery(q)
        maybeSnapshot()
        return out
    }

    // ---- запросы человеку (D-44) ----

    /**
     * Сведения о ходе задачи — одно уведомление на задачу: «начата»,
     * «выполнена», «не выполнена» заменяют друг друга, а не копятся (ТЗ, §36).
     */
    private fun progress(task: TaskId, category: NotificationCategory, title: String, body: String) {
        val t = tasks.tasks[task] ?: return
        notify(NotificationRequest("task:${task.value}:progress", category, "$title: ${t.title}", body, DeepLink.of(DeepLink.Kind.TASK, task), clock.now()))
    }

    /**
     * Запрос человеку: в журнал, затем уведомлением. По предмету уже ждёт
     * такой же — нового нет. Отказ сборки — в [problems], контур идёт дальше.
     */
    private fun ask(
        purpose: RequestPurpose,
        kind: RequestKind,
        subject: String,
        title: String,
        body: String,
        options: List<RequestOption>,
        task: TaskId?,
        session: SessionId?,
        ttl: Duration? = requestTtl,
    ): Outcome<RequestId> {
        val now = clock.now()
        val r = when (val c = UserRequest.create(ids.next(IdKinds.REQUEST), kind, purpose.code, subject, title, body, options, task, session, now, ttl?.let { now + it })) {
            is Outcome.Failure -> return c.also { problems += Diagnostic.of(DiagCode.INTERNAL_ERROR, "запрос $subject не создан", causes = c.errors) }
            is Outcome.Success -> c.value
        }
        val es = when (val c = UserRequests.create(requests, r)) {
            is Outcome.Failure -> return c
            is Outcome.Success -> c.value
        }
        if (es.isEmpty()) return Outcome.success(requests.pendingFor(subject)!!.id)
        commitRequests(es)
        notify(RequestNotifications.of(r))
        return Outcome.success(r.id)
    }

    private fun taskTitle(task: TaskId) = tasks.tasks[task]?.title ?: task.value

    private fun askBlocked(task: TaskId, session: SessionId?, why: String) {
        ask(
            RequestPurpose.TASK_BLOCKED, RequestKind.USER_RESOLUTION, "task:${task.value}:blocked",
            "Задача остановлена: ${taskTitle(task)}", "$why. Что делать?",
            listOf(
                RequestOption(RETRY, "Повторить", OptionRole.ACCEPT),
                RequestOption(REPLY, "Ответить", OptionRole.NEUTRAL, needsText = true),
                RequestOption(CANCEL, "Отменить задачу", OptionRole.DESTRUCTIVE),
            ),
            task, session,
        )
    }

    private fun askUnverified(task: TaskId, session: SessionId?) {
        ask(
            RequestPurpose.TASK_UNVERIFIED, RequestKind.USER_RESOLUTION, "task:${task.value}:unverified",
            "Задача без проверки: ${taskTitle(task)}", "Ход заявил «готово», а проверки нет (D-23). Принять без проверки — записать исключение.",
            listOf(
                RequestOption(WAIVE, "Принять без проверки", OptionRole.DESTRUCTIVE),
                RequestOption(CANCEL, "Отменить задачу", OptionRole.DESTRUCTIVE),
                RequestOption(LATER, "Решу позже", OptionRole.NEUTRAL),
            ),
            task, session,
        )
    }

    private fun askRecovery(q: Diagnostic) {
        ask(
            RequestPurpose.RECOVERY, RequestKind.ACKNOWLEDGEMENT, "recovery:${q.code.name}:${q.subject ?: "-"}",
            "Сверка после перезапуска: нужен человек", "${q.code}: ${q.message}",
            listOf(RequestOption(ACK, "Понятно", OptionRole.ACCEPT)), null, null,
        )
    }

    /**
     * Проверочный запрос (экран «Проверка запросов», `docs/DEVICE_CHECK.md`):
     * любой вид с вариантами по шаблону; ответ только записывается в журнал.
     * Каждый — свой предмет: одновременные проверочные запросы не смешиваются.
     */
    fun probe(kind: RequestKind, ttl: Duration? = requestTtl): Outcome<RequestId> {
        val options = when (kind) {
            RequestKind.QUESTION -> listOf(RequestOption("a", "Вариант A"), RequestOption("b", "Вариант B"), RequestOption("c", "Вариант C"))
            RequestKind.CHOICE -> listOf(RequestOption("fast", "Быстро"), RequestOption("normal", "Обычно"), RequestOption("full", "Полностью"))
            RequestKind.CONFIRMATION -> listOf(RequestOption("yes", "Подтвердить", OptionRole.ACCEPT), RequestOption("no", "Отклонить", OptionRole.REJECT))
            RequestKind.DESTRUCTIVE_CONFIRMATION -> listOf(RequestOption("do", "Удалить", OptionRole.DESTRUCTIVE), RequestOption("no", "Отмена", OptionRole.REJECT))
            RequestKind.TEXT_INPUT -> listOf(RequestOption("send", "Ответить", OptionRole.ACCEPT, needsText = true), RequestOption("no", "Отказаться", OptionRole.REJECT))
            RequestKind.ACKNOWLEDGEMENT -> listOf(RequestOption(ACK, "Понятно", OptionRole.ACCEPT))
            RequestKind.USER_RESOLUTION -> listOf(RequestOption("keep", "Оставить", OptionRole.NEUTRAL), RequestOption("drop", "Сбросить", OptionRole.DESTRUCTIVE))
        }
        val n = requests.requests.values.count { it.purpose == RequestPurpose.PROBE.code } + 1
        return ask(RequestPurpose.PROBE, kind, "probe:$n", "Проверка запросов №$n: $kind", "Проверочный запрос: ответ только записывается в журнал.", options, null, null, ttl)
    }

    /** Канал показал запрос (`notification`, `overlay`, `app`): факт в журнал; закрытый — без записи. */
    fun delivered(id: RequestId, channel: String): Outcome<Unit> = when (val r = UserRequests.delivered(requests, id, channel, clock.now())) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Unit).also { commitRequests(r.value) }
    }

    /** Ждущие запросы — для экрана и окна поверх приложений. */
    fun pendingRequests(): List<UserRequest> = requests.pending

    /** Кнопка уведомления `dpai://answer/…` и текст поля ввода, если он есть (D-44). */
    fun answer(uri: String, text: String?, by: String): OperationResult<RequestAnswer> = when (val a = RequestAction.parse(uri)) {
        is Outcome.Failure -> OperationResult.record("request.answer", Provenance(Actor(ActorKind.USER, by)), ids, clock, a)
        is Outcome.Success -> answer(a.value.request, a.value.option, text, a.value.hash, by)
    }

    /**
     * Ответ на запрос (D-44, D-06): проверка — запрос ждёт, не истёк, хеш и
     * вариант его; затем действие по назначению запроса и запись исхода.
     * Повтор тем же вариантом — прежний ответ, без второго действия.
     */
    fun answer(id: RequestId, option: String, text: String?, hash: ContentHash, by: String): OperationResult<RequestAnswer> {
        val r = requests.requests[id]
        val provenance = Provenance(Actor(ActorKind.USER, by), taskId = r?.task, sessionId = r?.session)
        val outcome: Outcome<RequestAnswer> = when (val c = UserRequests.check(requests, id, option, text, hash, clock.now())) {
            is Outcome.Failure -> c.also { if (r != null && !r.terminal && r.expired(clock.now())) commitRequests(listOf(RequestEvent.Expired(id, clock.now()))) }
            is Outcome.Success -> when (val v = c.value) {
                is AnswerCheck.Repeat -> Outcome.success(v.request.answer!!)
                is AnswerCheck.Apply -> applyAnswer(v, by)
            }
        }
        return OperationResult.record("request.answer", provenance, ids, clock, outcome)
    }

    private fun applyAnswer(a: AnswerCheck.Apply, by: String): Outcome<RequestAnswer> {
        val r = a.request
        val effect: Outcome<String> = when (RequestPurpose.of(r.purpose)) {
            RequestPurpose.PROBE, RequestPurpose.RECOVERY -> Outcome.success("записано")
            RequestPurpose.TASK_BLOCKED -> resolveBlocked(r.task!!, a.option.id, a.text)
            RequestPurpose.TASK_UNVERIFIED -> resolveUnverified(r.task!!, a.option.id, by)
            null -> Outcome.failure(DiagCode.UNSUPPORTED, "назначение запроса неизвестно: ${r.purpose}")
        }
        return when (effect) {
            is Outcome.Failure -> effect.also {
                commitRequests(listOf(RequestEvent.Failed(r.id, a.option.id, effect.errors.first().code, clock.now())))
            }
            is Outcome.Success -> {
                val answer = RequestAnswer(a.option.id, a.text, by, clock.now(), effect.value)
                commitRequests(listOf(RequestEvent.Answered(r.id, answer)))
                Outcome.success(answer)
            }
        }
    }

    /** Ждущие (не конечные) сессии задачи — их снимает решение человека. */
    private fun openSessions(task: TaskId) = sessions.sessions.values.filter { it.task == task && !it.terminal }.sortedBy { it.id.value }

    private fun resolveBlocked(task: TaskId, option: String, text: String?): Outcome<String> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.state != TaskState.BLOCKED) return Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "задача уже не BLOCKED: ${t.state}", "task $task")
        val open = openSessions(task)
        return when (option) {
            RETRY, REPLY -> {
                // Сессия остановлена — продолжение той же сессии провайдера с ответом человека (handoff MANUAL).
                val next = text?.let { "ответ пользователя: $it" } ?: "пользователь снял блок: продолжить с места остановки"
                for (s in open) if (s.state in WAIT_STATES) handOff(task, s.id, HandoffReason.MANUAL, next) else closeSession(task, s.id)
                if (tasks.tasks.getValue(task).state == TaskState.BLOCKED) {
                    commitTasks(ok(tasksLogic.move(tasks, task, TaskState.READY, clock.now(), DiagCode.CANCELLED_BY_USER), "задача снова в очереди").orEmpty())
                }
                Outcome.success(if (text != null) "задача снова в очереди с ответом" else "задача снова в очереди")
            }
            CANCEL -> {
                for (s in open) closeSession(task, s.id)
                commitTasks(ok(tasksLogic.move(tasks, task, TaskState.CANCELLED, clock.now(), DiagCode.CANCELLED_BY_USER), "отмена задачи").orEmpty())
                Outcome.success("задача отменена")
            }
            else -> Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "вариант «$option» не к этому запросу")
        }
    }

    private fun resolveUnverified(task: TaskId, option: String, by: String): Outcome<String> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.state != TaskState.COMPLETION_UNVERIFIED) return Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "задача уже не COMPLETION_UNVERIFIED: ${t.state}", "task $task")
        return when (option) {
            WAIVE -> when (val c = tasksLogic.complete(tasks, task, clock.now(), by to "принято без проверки ответом на запрос")) {
                is Outcome.Failure -> c
                is Outcome.Success -> Outcome.success("задача принята без проверки").also { commitTasks(c.value) }
            }
            CANCEL -> when (val c = tasksLogic.move(tasks, task, TaskState.CANCELLED, clock.now(), DiagCode.CANCELLED_BY_USER)) {
                is Outcome.Failure -> c
                is Outcome.Success -> Outcome.success("задача отменена").also { commitTasks(c.value) }
            }
            LATER -> Outcome.success("решение отложено")
            else -> Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "вариант «$option» не к этому запросу")
        }
    }

    /** Сессия задачи снята человеком: `CANCELLED`, аренда и дерево свободны. */
    private fun closeSession(task: TaskId, session: SessionId) {
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), DiagCode.CANCELLED_BY_USER).valueOrNull().orEmpty())
        releaseLease(session)
        releaseTree(task, session)
    }

    /**
     * Такт запросов: истёкшие — `EXPIRED`; запросы о задаче, которая ушла из
     * того состояния, о котором спрашивали, — сняты (ответ больше ничего
     * не решает, кнопка не должна действовать).
     */
    private fun settleRequests() {
        val now = clock.now()
        val stale = requests.pending.filter { r ->
            val state = r.task?.let { tasks.tasks[it]?.state }
            when (RequestPurpose.of(r.purpose)) {
                RequestPurpose.TASK_BLOCKED -> state != TaskState.BLOCKED
                RequestPurpose.TASK_UNVERIFIED -> state != TaskState.COMPLETION_UNVERIFIED
                else -> false
            }
        }
        val es = UserRequests.expire(requests, now) + stale.filter { !it.expired(now) }.map { RequestEvent.Cancelled(it.id, DiagCode.REQUEST_NOT_PENDING, now) }
        if (es.isNotEmpty()) commitRequests(es)
    }

    /** Сколько записей в журнале контура (рост хранилища, ТЗ, §116). */
    fun eventCount(): Long = journal.position

    /** Инварианты ТЗ, §118 над текущим состоянием и журналом; пусто — держатся. */
    fun invariants(): List<Diagnostic> = Invariants.check(
        InvariantInput(
            accounts, sessions, tasks, worktrees, targets.map { it.id }.toSet(), journal.records(journal.activeFrom).map { it.event }, journal.activeFrom == 1L,
            merged, mergeApprovals.toMap(), problems.toList(),
        ),
    )

    /** Последняя версия контекста задачи — что видел Claude в последнем ходе. */
    fun lastContext(task: TaskId): ContextBundle? = contextOf[task] ?: storedContext(task, report = false)

    /** Отчёт расхода (D-40; `docs/EFFICIENCY.md`, §9). */
    fun usage(): com.dpsmpx.dpai.monitoring.UsageReport = com.dpsmpx.dpai.monitoring.UsageReport.build(sessions, tasks)

    fun dashboard(): DashboardView =
        Dashboard.build(sessions, tasks, accounts, emptyMap(), Measure.observed(approvals.pending()), sessionsLogic, clock.now())

    companion object {
        /** Сессии, которые не опрашиваются: ждут человека или сверки. */
        /** Ветка дерева задачи: по ней дерево узнаётся после перезапуска. */
        private const val TASK_BRANCH = "task/"

        /** Ключ источника «что среда делает сама» (D-40). */
        const val ENVIRONMENT_SOURCE = "orchestrator:environment"

        /** Ключ источника с выжимкой проваленной проверки (D-40). */
        const val VERIFICATION_SOURCE = "tool:verification"

        /** Изменённых файлов в handoff — не больше: список, а не дамп рабочего дерева. */
        private const val MAX_HANDOFF_FILES = 200

        private val WAIT_STATES = setOf(SessionState.PAUSED, SessionState.BLOCKED, SessionState.RECOVERY_PENDING)

        /** Варианты ответов на запросы оркестратора (D-44). */
        const val RETRY = "retry"
        const val REPLY = "reply"
        const val CANCEL = "cancel"
        const val WAIVE = "waive"
        const val LATER = "later"
        const val ACK = "ack"
    }
}
