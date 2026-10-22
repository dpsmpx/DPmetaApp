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
import com.dpsmpx.dpai.tasks.FindingSeverity
import com.dpsmpx.dpai.tasks.ReviewRecord
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.ReviewVerdict
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.orchestrator.review.Review
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
import com.dpsmpx.dpai.notifications.DeepLink
import com.dpsmpx.dpai.notifications.NotificationCategory
import com.dpsmpx.dpai.notifications.NotificationDecision
import com.dpsmpx.dpai.notifications.NotificationEngine
import com.dpsmpx.dpai.notifications.NotificationRequest
import com.dpsmpx.dpai.orchestrator.accounts.AccountNotifications
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
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.orchestrator.commands.CommandInbox
import com.dpsmpx.dpai.orchestrator.commands.TaskCommand
import com.dpsmpx.dpai.orchestrator.commands.OperationRecord
import com.dpsmpx.dpai.orchestrator.commands.OperationsProjection
import com.dpsmpx.dpai.orchestrator.commands.OperationsState
import com.dpsmpx.dpai.orchestrator.commands.operationEnvelopes
import com.dpsmpx.dpai.orchestrator.commands.sessionLimitEnvelope
import com.dpsmpx.dpai.orchestrator.commands.targetEnabledEnvelope
import com.dpsmpx.dpai.orchestrator.requests.RequestPurpose
import com.dpsmpx.dpai.orchestrator.requests.UserRequestsProjection
import com.dpsmpx.dpai.orchestrator.requests.requestEnvelopes
import java.math.BigDecimal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    /** Деревья — на другом target, чем ход Claude: ход работает с ними по MCP (D-50). `null` — на том же target. */
    val remote: RemoteWorkspace? = null,
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
    /**
     * Записанные в журнал события — наружу, после записи (D-43: строки
     * журнала приложения через `EventLog`). Сбой получателя контур не
     * останавливает.
     */
    private val onEvents: (List<EventEnvelope>) -> Unit = {},
    /** Новая проблема контура — наружу (D-43: строка журнала приложения с причинами); сбой получателя контур не трогает. */
    private val onProblem: (Diagnostic) -> Unit = {},
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
    val notifications = NotificationEngine()
    /**
     * Решения об уведомлениях — последние [POSTED_KEEP]: раньше список рос
     * с каждым уведомлением без предела (гистограммы кучи `EnduranceTest`,
     * 18 октября 2026), а оболочка читала его из своего потока без замка.
     * Новое оболочка берёт по сквозному номеру ([postedSince]).
     */
    private val postedLog = RecentLog<NotificationDecision>(POSTED_KEEP)

    /** Сколько решений было всего — сквозной номер следующего. */
    val postedTotal: Long get() = postedLog.total

    /** Последние решения (копия): для проверок и экрана. */
    val posted: List<NotificationDecision> get() = postedLog.toList()

    /**
     * Решения после сквозного номера [seen] и новый номер. Отставшая
     * оболочка (больше [POSTED_KEEP] за раз) получает последние — старые
     * уведомления к тому времени уже неактуальны.
     */
    fun postedSince(seen: Long): Pair<List<NotificationDecision>, Long> = postedLog.since(seen)

    private fun post(d: NotificationDecision) = postedLog.add(d)

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

    /** Ответ по схеме последнего хода сессии ревью (D-59) — до её реакции на конец хода. */
    private val reviewOutput = HashMap<SessionId, com.dpsmpx.dpai.core.serialization.JsonValue?>()
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

    /**
     * Последние [KEPT_PROBLEMS] отказов контура — для самодиагностики экрана.
     * Каждый сразу уходит в `onProblem` (журнал приложения, D-43), поэтому
     * старые отбрасываются: при недоступном target отказы шли каждый такт, и
     * список рос без предела (ревью 8 октября 2026, A-33, A-83).
     */
    val problems: MutableList<Diagnostic> = object : ArrayList<Diagnostic>() {
        private fun trim() {
            if (size > KEPT_PROBLEMS) removeRange(0, size - KEPT_PROBLEMS)
        }

        private fun tell(d: Diagnostic) {
            try {
                onProblem(d)
            } catch (_: Exception) {
                // Журнал приложения — наблюдение: его сбой контур не останавливает (D-43).
            }
        }

        override fun add(element: Diagnostic): Boolean = super.add(element).also {
            trim()
            tell(element)
        }

        override fun addAll(elements: Collection<Diagnostic>): Boolean = super.addAll(elements).also {
            trim()
            elements.forEach(::tell)
        }
    }

    /** Запросы человеку (D-44) — из журнала. */
    var requests: UserRequestsState = UserRequestsState.EMPTY
        private set

    /** Настройки контура от человека (D-42, п. 5, 6): выключенные targets, общий предел сессий — из журнала. */
    var control: com.dpsmpx.dpai.orchestrator.commands.ControlState = com.dpsmpx.dpai.orchestrator.commands.ControlState.EMPTY
        private set

    /** Исполненные команды с ключами идемпотентности (D-42) — из журнала. */
    var operations: OperationsState = OperationsState.EMPTY
        private set

    /** Очередь команд (D-42, п. 1): кладёт любой поток, исполняет поток такта ([runCommands]). */
    val inbox = CommandInbox()

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
            operations = restore(OperationsProjection, OperationsState.EMPTY)
            control = restore(com.dpsmpx.dpai.orchestrator.commands.ControlProjection, com.dpsmpx.dpai.orchestrator.commands.ControlState.EMPTY)
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
                        c.value.sources.firstOrNull { it.key == VERIFICATION_SOURCE || it.key == REVIEW_SOURCE }?.let { s ->
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
        save(OperationsProjection, operations)
        save(com.dpsmpx.dpai.orchestrator.commands.ControlProjection, control)
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
        try {
            onEvents(envs)
        } catch (_: Exception) {
            // Журнал приложения — наблюдение, а не состояние: его сбой контур не останавливает (D-43).
        }
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
        // Уведомления аккаунтов (ревью A-62): предел подписки, вход, пересечение аккаунтов — по каждому
        // событию, по состоянию до и после: повторное наблюдение без изменений уведомлением не становится.
        for (e in es) {
            val before = accounts
            accounts = Accounts.applyAll(accounts, listOf(e))
            AccountNotifications.requests(e, before, accounts).forEach { notify(it) }
            AccountNotifications.resolvedKeys(e).forEach { notifications.resolve(it) }
        }
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
                sessions.sessions.values.filter { it.task == id }.forEach { driver.forget(it.id) }
                if (documents != null) contextOf.remove(id)
                lastOverlap.remove(id)
                lastWaiting.remove(id)
                if (documents != null) messagesOf.remove(id)
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

    /** Такт контура: команды — в начале и между вызовами target (D-42, п. 1). */
    fun tick() {
        runCommands()
        commitTasks(tasksLogic.promote(tasks, clock.now()))
        resumeAfterLogin()
        // Ждущие сверки сессии держат аренду и место target: сверить снова, когда target ответит (A-44, A-76).
        agentFor?.let { a ->
            val due = clock.now().epochMillis - lastReconcileAt >= RECONCILE_EVERY.inWholeMilliseconds
            if (due && sessions.sessions.values.any { it.state == SessionState.RECOVERY_PENDING }) {
                for (r in reconcilePending(a)) problems += r.diagnostics.filter { it.isError }
            }
        }
        // Выключенный target новых сессий не получает; идущие на нём доигрывают (D-42, п. 5).
        val offers = targets.map { t ->
            val active = sessions.sessions.values.count { !it.terminal && it.target == t.id }
            val free = if (t.id in control.disabledTargets) 0 else (t.maxSessions - active).coerceAtLeast(0)
            Tasks.TargetOffer(t.id, t.capabilities, free)
        }
        // Общий предел сессий контура (D-42, п. 6) — поверх предела каждого target.
        val room = control.maxSessions?.let { (it - sessions.sessions.values.count { s -> !s.terminal }).coerceAtLeast(0) } ?: Int.MAX_VALUE
        for (a in tasksLogic.plan(tasks, clock.now(), offers).take(room)) {
            runCommands()
            // Команда могла снять задачу с очереди (отмена, ответ человека) или придержать её («Пауза всем»,
            // ТЗ, §73) — план уже не про неё: удержанная остаётся READY, и без проверки held она бы запустилась.
            val now = tasks.tasks[a.task]
            if (now == null || now.state != TaskState.READY || now.held) continue
            if (overlapAllows(a.task)) launch(a.task, a.target)
        }
        for (s in sessions.sessions.values.filter { it.state !in WAIT_STATES && !it.terminal }.map { it.id }.sorted()) {
            runCommands()
            val r = sessions.sessions[s] ?: continue
            if (r.terminal || r.state in WAIT_STATES) continue
            poll(s)
        }
        reviews()
        runCommands()
        settleRequests()
        // Придержанное пределом частоты — сводкой, когда предел позволяет (ТЗ, §36).
        notifications.flush(clock.now())?.let(::post)
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

    /** Почему готовая задача не начинается — последняя сказанная причина; уведомление — при её смене. */
    private val lastWaiting = HashMap<TaskId, String>()

    /** Задача ждёт: причина — уведомлением и на сводке, один раз на смену причины (ТЗ, §36). */
    private fun waiting(task: TaskId, why: String) {
        if (lastWaiting.put(task, why) == why) return
        notify("task:${task.value}:waiting", NotificationCategory.INFO, "Задача ждёт: ${taskTitle(task)}", why)
        problems += Diagnostic.warning(DiagCode.ACCOUNT_NONE_AVAILABLE, "${taskTitle(task)}: $why", "task $task")
    }

    /** Почему задача ждёт, если известно (лист задачи). */
    fun waitingReason(task: TaskId): String? = lastWaiting[task]

    private fun launch(task: TaskId, target: ExecutionTargetId) {
        val t = tasks.tasks.getValue(task)
        val session = ids.next(IdKinds.SESSION)
        val lease = accountsLogic.lease(accounts, session, task, clock.now(), target, urgent = t.priority.ordinal <= Priority.HIGH.ordinal)
        if (lease is Outcome.Failure) {
            // Свободного слота нет: задача ждёт следующего такта — и человек видит почему (раньше — молча).
            waiting(task, lease.errors.first().message)
            return
        }
        lastWaiting.remove(task)
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
        // Сессии ревью (D-59) не продолжаются работой: у них свой prompt и только чтение.
        val previous = lastWork(task)
        val handoff = handoffs.latest(task)
        val continues = previous?.takeIf {
            (it.state == SessionState.CANCELLED && it.lastReason == DiagCode.SESSION_HANDED_OFF) ||
                // Запрос человека в эту сессию (D-42): новый ход той же сессии провайдера или свежая с handoff.
                (handoff?.session == it.id && (handoff.reason == HandoffReason.MANUAL || handoff.reason == HandoffReason.TURN_FINISHED)) ||
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
        val window = sessions.sessions.values.filter { it.task == task && it.review == null }.mapNotNull { it.usage.contextWindow }.maxOrNull()
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
        val local = ClaudeRequest(
            composed.text, model = route.model,
            effort = route.effort.takeIf { "--effort" !in unknown },
            fallbackModel = route.fallbackModel.takeIf { "--fallback-model" !in unknown },
            shareSystemPromptCache = efficiency.shareSystemPromptCache && "--exclude-dynamic-system-prompt-sections" !in unknown,
            permissionMode = ClaudeCodeCli.TURN_PERMISSION_MODE,
            disallowedTools = if (route.subagents) emptyList() else listOf(ClaudeCodeCli.SUBAGENT_TOOL),
            workDir = workDir, timeout = t.policy.turnTimeout, maxBudgetUsd = budget,
        )
        // Дерево на target проекта, ход — на target слота: файлы и команды — через MCP-сервер дерева (D-50).
        val request = config.remote?.let { r -> ok(r.apply(local, workDir), "ход с деревом на другом target") ?: return failLaunch(task, session, DiagCode.SESSION_FAILED) } ?: local
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
        failLaunch(task, session, DiagCode.CONTEXT_BUDGET_EXCEEDED, countAttempt = false)
        commitTasks(tasksLogic.move(tasks, task, TaskState.BLOCKED, clock.now(), DiagCode.CONTEXT_BUDGET_EXCEEDED).valueOrNull().orEmpty())
        askBlocked(task, null, "правила или требования задачи не влезают в бюджет контекста")
    }

    /**
     * Запуск не удался до хода. [countAttempt] — это неудачная попытка
     * задачи (A-47): повтор — с паузой по классу причины, та же причина
     * снова — петля и запрос человеку; иначе такт повторял бы запуск без
     * паузы, а сбои копились бы без предела.
     */
    private fun failLaunch(task: TaskId, session: SessionId, code: DiagCode, countAttempt: Boolean = true) {
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), code).valueOrNull().orEmpty())
        releaseLease(session)
        // Дерево могло быть уже захвачено этой сессией: завершённая сессия его не держит (инвариант worktree-owner).
        releaseTree(task, session)
        problems += Diagnostic.error(code, "запуск задачи $task не удался", "task $task")
        if (!countAttempt || tasks.tasks[task]?.state != TaskState.READY) return
        commitTasks(tasksLogic.start(tasks, task, clock.now()).valueOrNull().orEmpty())
        commitTasks(ok(tasksLogic.attemptFailed(tasks, task, code, code.retry, "${task.value}:launch:${code.name}", clock.now()), "повтор задачи").orEmpty())
        when (tasks.tasks[task]?.state) {
            TaskState.BLOCKED -> askBlocked(task, null, "запуск не удался: ${tasks.tasks[task]?.lastReason ?: code}")
            TaskState.FAILED -> progress(task, NotificationCategory.FAILURE, "Задача не выполнена", "попытки кончились: $code")
            else -> Unit
        }
    }

    /**
     * Вход слота восстановлен (A-45): задача, остановленная из‑за входа,
     * продолжается сама — как ответ «Повторить»: сессия передаёт handoff,
     * аренда, место target и дерево свободны, запрос снимается.
     */
    private fun resumeAfterLogin() {
        for (s in sessions.sessions.values.filter { it.state == SessionState.BLOCKED && it.lastReason == DiagCode.CLAUDE_AUTH_REQUIRED }.sortedBy { it.id.value }) {
            if (accounts.slots[s.slot]?.state.let { it == null || it == com.dpsmpx.dpai.core.state.AccountState.AUTH_REQUIRED }) continue
            if (tasks.tasks[s.task]?.state != TaskState.BLOCKED) continue
            resolveBlocked(s.task, RETRY, null)
            progress(s.task, NotificationCategory.PROGRESS, "Задача продолжается", "вход в Claude восстановлен")
        }
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
            if (ok(g.run(GitOp.WORKTREE_ADD, repo.root, listOf(path, branch)), "git worktree add").also { gitOk = it != null } == null) return null
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
        recordTurnMessages(session, step.turns)
        val after = sessions.sessions.getValue(session)
        if (after.review != null) step.turns.lastOrNull()?.let { reviewOutput[session] = it.structured }
        if (after.state != before) react(after.task, session, after.state)
    }

    private fun react(task: TaskId, session: SessionId, state: SessionState) {
        // Сессия ревью (D-59) — свой итог: находки, а не работа задачи.
        sessions.sessions[session]?.review?.let { return reactReview(task, session, it, state) }
        when (state) {
            SessionState.COMPLETED -> finish(task, session)
            SessionState.PAUSED -> handOff(task, session)
            SessionState.FAILED -> {
                releaseTree(task, session)
                val record = sessions.sessions.getValue(session)
                val code = record.lastReason ?: DiagCode.SESSION_FAILED
                if (record.turn == 0 && code.category == com.dpsmpx.dpai.core.diagnostics.Category.NETWORK && code.retry == com.dpsmpx.dpai.core.diagnostics.Retryability.RETRYABLE) {
                    // Target недоступен до начала хода (сон ПК, нет сети) — не неудачная попытка задачи, а
                    // ожидание target (A-50): иначе за три отказа задача — BLOCKED «петлёй».
                    commitTasks(tasksLogic.move(tasks, task, TaskState.READY, clock.now(), code, notBefore = clock.now() + TARGET_RETRY).valueOrNull().orEmpty())
                    waiting(task, "target недоступен (${code.name}): повтор через $TARGET_RETRY")
                    return
                }
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
        afterVerification(task, session, flow.report, tree, path)
    }

    /**
     * Итог проверки (D-23): прошла — коммит, `DONE`, запрос слияния; нет
     * проверки — запрос человеку; не прошла — выжимка следующей попытке.
     * Что пошло не так у шагов — в [problems] с причинами (журнал
     * приложения, D-43), а не только в хеше отчёта.
     */
    private fun afterVerification(task: TaskId, session: SessionId, report: VerificationReport, tree: com.dpsmpx.dpai.git.Worktree?, path: String) {
        if (report.verdict != com.dpsmpx.dpai.verification.Verdict.PASSED) problems += report.diagnostics.filter { it.isError || it.code == DiagCode.VERIFICATION_STEP_UNAVAILABLE }
        when (tasks.tasks.getValue(task).state) {
            TaskState.VERIFIED -> afterVerified(task, session, tree, path)
            TaskState.COMPLETION_UNVERIFIED -> {
                releaseTree(task, session)
                askUnverified(task, session)
            }
            else -> {
                // READY: проверка не прошла — следующий исполнитель с тем же деревом; что упало — ему (D-40, п. 3).
                if (efficiency.feedVerification) verificationFailed(task, session, report, path)
                releaseTree(task, session)
            }
        }
    }

    /**
     * Проверка прошла: коммит работы, `DONE`, запрос слияния. Вызывается и
     * при восстановлении (ревью A-56): коммит идемпотентен — чистое дерево
     * значит «уже закоммичено».
     */
    private fun afterVerified(task: TaskId, session: SessionId, tree: com.dpsmpx.dpai.git.Worktree?, path: String) {
        val failed = if (tree != null && git != null) commitWork(task, session, path) else null
        if (failed != null) {
            // Работа проверена, но не закоммичена (A-52): не DONE и не слияние ветки без неё — решает человек,
            // работа остаётся в дереве задачи.
            for (to in listOf(TaskState.READY, TaskState.BLOCKED)) {
                commitTasks(tasksLogic.move(tasks, task, to, clock.now(), DiagCode.GIT_COMMAND_FAILED).valueOrNull().orEmpty())
            }
            releaseTree(task, session)
            askBlocked(task, session, "$failed: работа проверена, но не закоммичена — она в дереве задачи")
            return
        }
        releaseTree(task, session)
        // Ревью (D-59) — после коммита: ревьюер читает закоммиченную ветку; DONE — когда ревью кончились.
        val reviews = tasks.tasks.getValue(task).policy.reviews
        if (reviews.isNotEmpty()) {
            progress(task, NotificationCategory.PROGRESS, "Задача на ревью", "проверка прошла; ${reviews.sorted().joinToString { Review.label(it) }}")
            settleReviews(task)
            return
        }
        completeVerified(task, tree, "проверка прошла")
    }

    /** `DONE` и запрос слияния проверенной (и, если назначено, прошедшей ревью) задачи. */
    private fun completeVerified(task: TaskId, tree: com.dpsmpx.dpai.git.Worktree?, why: String) {
        // DONE и запрос слияния — одной пачкой журнала (ревью A-56): сбой между ними оставлял
        // задачу DONE без запроса, и слияния не было бы никогда.
        batch {
            commitTasks(ok(tasksLogic.complete(tasks, task, clock.now()), "завершение задачи").orEmpty())
            if (tree != null && tasks.tasks[task]?.state == TaskState.DONE) recordMergeRequest(task, tree.branch)
        }
        if (tasks.tasks[task]?.state != TaskState.DONE) return
        progress(task, NotificationCategory.SUCCESS, "Задача выполнена", why)
        mergesState.pending.values.firstOrNull { it.task == task }?.let { askMerge(it) }
    }

    /** Последняя рабочая сессия задачи — не ревью (D-59): её продолжают, ей пишут запросы. */
    private fun lastWork(task: TaskId): com.dpsmpx.dpai.sessions.SessionRecord? = sessions.sessions.values.filter { it.task == task && it.review == null }
        .maxWithOrNull(compareBy<com.dpsmpx.dpai.sessions.SessionRecord>({ it.createdAt.epochMillis }, { it.turn }, { it.id.value }))

    /**
     * Ревью проверенных задач (D-59): у задачи одна активная сессия
     * (инвариант `task-single-session`), поэтому роли — по одной за раз,
     * по порядку. Запускать нечего — решить итог: перезапуск между записью
     * итога и решением не оставляет задачу ждать вечно.
     */
    private fun reviews() {
        for (id in tasks.tasks.values.filter { it.state == TaskState.VERIFIED && it.policy.reviews.isNotEmpty() }.map { it.id }.sortedBy { it.value }) {
            runCommands()
            val t = tasks.tasks[id] ?: continue
            if (t.state != TaskState.VERIFIED || openSessions(id).isNotEmpty()) continue
            if (t.reviews.values.any { it.verdict == ReviewVerdict.BLOCKED }) {
                settleReviews(id)
                continue
            }
            val next = nextReview(t)
            if (next == null) settleReviews(id) else launchReview(id, next.first, next.second)
        }
    }

    /**
     * Роль, которую пора ревьюить, и номер попытки ревью; `null` — запускать
     * нечего. Сбой — повтор, пока попыток меньше [Review.MAX_ATTEMPTS];
     * повтор сверх них — только по решению человека (запись с `by`).
     */
    private fun nextReview(t: TaskRecord): Pair<ReviewRole, Int>? = t.policy.reviews.sorted().firstNotNullOfOrNull { role ->
        val r = t.reviews[role]
        when {
            r == null -> role to 1
            r.verdict == ReviewVerdict.FAILED && (r.attempt < Review.MAX_ATTEMPTS || r.by != null) -> role to r.attempt + 1
            else -> null
        }
    }

    /**
     * Итог ревью (D-59, п. 3): блокирующее — повтор с выжимкой; без итога и
     * без повторов — запрос человеку; все прошли или приняты — `DONE`.
     */
    private fun settleReviews(task: TaskId) {
        val t = tasks.tasks[task] ?: return
        if (t.state != TaskState.VERIFIED) return
        when {
            t.reviews.values.any { it.verdict == ReviewVerdict.BLOCKED } -> blockedByReview(task)
            nextReview(t) != null -> Unit
            t.reviewsLeft().isNotEmpty() -> askReview(task, t.reviewsLeft())
            else -> completeVerified(task, treeOf[task]?.let { worktrees.worktrees[it] }, "проверка и ревью прошли")
        }
    }

    /**
     * Ревью нашло блокирующее (D-59, п. 3): выжимка находок — источник
     * следующей попытки и handoff последней рабочей сессии, как у
     * проваленной проверки (D-40, п. 3): следующая попытка продолжает её.
     */
    private fun blockedByReview(task: TaskId) {
        val t = tasks.tasks.getValue(task)
        val digest = Review.digest(t.reviews.values)
        val work = lastWork(task)
        if (digest != null && work != null && efficiency.feedVerification) {
            // Находки написала модель — данные с происхождением MODEL, а не указания (T3).
            toolOf[task] = ContextSource(REVIEW_SOURCE, SourceLevel.TOOL, config.project, Sensitivity.INTERNAL, Origin.MODEL, digest)
            val bundle = contextOf[task]
            val roles = t.reviews.values.filter { it.verdict == ReviewVerdict.BLOCKED }.map { it.role }.sorted()
            val handoff = Handoff(
                id = ids.next(IdKinds.HANDOFF),
                key = "${work.id.value}:review:${t.retries}",
                reason = HandoffReason.VERIFICATION_FAILED,
                session = work.id,
                task = task,
                project = config.project,
                context = bundle?.id ?: ids.next(IdKinds.CONTEXT),
                contextHash = bundle?.hash ?: com.dpsmpx.dpai.core.hashing.Hashing.sha256(""),
                state = "проверка прошла, ${roles.joinToString { Review.label(it) }} нашло блокирующее",
                completed = emptyList(), verified = listOf("проверка профиля"),
                failed = t.reviews.values.flatMap { r -> r.findings.filter { it.severity == FindingSeverity.BLOCKING }.map { "${Review.label(r.role)}: ${it.title}" } },
                warnings = emptyList(), openProblems = emptyList(), currentAction = null,
                nextAction = "исправить то, что нашло ревью (выжимка — источник $REVIEW_SOURCE), и снова проверить",
                modifiedFiles = emptyList(), commit = null, branch = treeOf[task]?.let { worktrees.worktrees[it]?.branch },
                workspace = treeOf[task]?.let { worktrees.worktrees[it]?.path }, createdAt = clock.now(),
                relevantFiles = t.reviews.values.flatMap { r -> r.findings.mapNotNull { it.file } }.distinct().take(50), summary = workingOf[task]?.summary,
            )
            handoffs.create(handoff)
            saveTaskDocument("handoff", task, handoff.toJson())
            context(task, t.objective, efficiency.context.policy(null))
        }
        commitTasks(ok(tasksLogic.reviewBlocked(tasks, task, clock.now()), "ревью задачи").orEmpty())
        val found = t.reviews.values.flatMap { r -> r.findings.filter { it.severity == FindingSeverity.BLOCKING } }.joinToString("; ") { it.title }
        when (tasks.tasks[task]?.state) {
            TaskState.BLOCKED -> askBlocked(task, work?.id, "ревью снова нашло блокирующее: $found")
            else -> progress(task, NotificationCategory.WARNING, "Ревью нашло ошибки", "задача снова в работе: $found")
        }
    }

    /**
     * Сессия ревью роли (D-59, п. 2): на target последней рабочей сессии
     * (там дерево или маршрут к нему, D-50), слот — по правилам аренды
     * (D-30), только чтение, ответ по схеме. Нет target, слота или места —
     * задача ждёт с причиной; изменения не получены — ревью без итога.
     */
    private fun launchReview(task: TaskId, role: ReviewRole, attempt: Int) {
        val t = tasks.tasks.getValue(task)
        val work = lastWork(task) ?: return recordReviewFailure(task, role, attempt, null, "у задачи нет рабочей сессии — неизвестно, где её дерево")
        val target = work.target
        if (target in control.disabledTargets) return waiting(task, "${Review.label(role)} ждёт: target $target не принимает новые задачи")
        val active = sessions.sessions.values.count { !it.terminal && it.target == target }
        if (active >= (targets.firstOrNull { it.id == target }?.maxSessions ?: 0)) return waiting(task, "${Review.label(role)} ждёт места на target $target")
        control.maxSessions?.let { max -> if (sessions.sessions.values.count { !it.terminal } >= max) return waiting(task, "${Review.label(role)} ждёт: предел сессий контура $max") }
        if ("--json-schema" in unsupportedFlags[target].orEmpty()) return recordReviewFailure(task, role, attempt, null, "Claude Code на target $target не знает --json-schema: ответ ревью по схеме невозможен")
        val diff = when (val d = taskDiff(task, Review.MAX_PATCH_CHARS)) {
            is Outcome.Failure -> return recordReviewFailure(task, role, attempt, null, "изменения задачи не получены: ${d.errors.first().message}")
            is Outcome.Success -> d.value
        }
        val session = ids.next(IdKinds.SESSION)
        val lease = accountsLogic.lease(accounts, session, task, clock.now(), target, urgent = t.priority.ordinal <= Priority.HIGH.ordinal)
        if (lease is Outcome.Failure) return waiting(task, "${Review.label(role)} ждёт слот: ${lease.errors.first().message}")
        val slot = lease.valueOrNull()!!.slot
        val route = router.route(t, budgetMode(slot))
        batch {
            commitAccounts(lease.valueOrNull()!!.events)
            ok(sessionsLogic.create(sessions, accounts, session, "task:${task.value}:review:${role.name}:${t.retries}:$attempt", task, slot, target, ProviderSessionId.newUuid(random), clock.now(), review = role.name), "сессия ревью $task не создана")
                ?.also { commitSessions(it.events) }
        } ?: return releaseLease(session)
        val workDir = treePath(task) ?: config.worktreeRoot
        val prompt = Review.prompt(role, t.title, t.objective, diff)
        val unknown = unsupportedFlags[target].orEmpty()
        val local = ClaudeRequest(
            prompt, model = route.model,
            effort = route.effort.takeIf { "--effort" !in unknown },
            fallbackModel = route.fallbackModel.takeIf { "--fallback-model" !in unknown },
            permissionMode = ClaudeCodeCli.TURN_PERMISSION_MODE,
            tools = Review.TOOLS, disallowedTools = listOf(ClaudeCodeCli.SUBAGENT_TOOL), jsonSchema = Review.SCHEMA,
            workDir = workDir, timeout = t.policy.turnTimeout, maxBudgetUsd = t.policy.maxBudgetUsdCents?.let { BigDecimal(it).movePointLeft(2) },
        )
        // Дерево на другом target (D-50): его MCP-сервер умеет и писать — у ревью эти инструменты запрещены.
        val request = config.remote?.let { r ->
            ok(r.apply(local, workDir ?: "."), "ревью с деревом на другом target")?.let { it.copy(disallowedTools = it.disallowedTools + Review.remoteWriteTools()) }
                ?: return failReviewLaunch(task, role, attempt, session, "маршрут к дереву на другом target не собран")
        } ?: local
        val facts = PromptFacts(
            PromptMode.FULL, prompt.length.toLong(), com.dpsmpx.dpai.contexts.TokenEstimate.of(prompt), 1,
            context = "review:${role.name}", taskClass = t.policy.taskClass.name, model = route.model, effort = route.effort, route = route.route,
            why = "${Review.label(role)}, попытка $attempt",
        )
        commitSessions(listOf(SessionEvent.PromptPrepared(session, facts, clock.now())))
        val step = ok(driver.launch(sessions, accounts, session, request), "ревью $session не запущено") ?: return failReviewLaunch(task, role, attempt, session, "сессия ревью не запущена")
        batch {
            commitSessions(step.events)
            applyFacts(step.facts)
        }
        lastWaiting.remove(task)
        val after = sessions.sessions.getValue(session)
        if (after.state != SessionState.RUNNING && after.state != SessionState.STARTING) react(task, session, after.state)
        else progress(task, NotificationCategory.PROGRESS, "Ревью начато", "${Review.label(role)}, попытка $attempt")
    }

    /** Ревью не запустилось после создания сессии: сессия снята, итог — без ревью с причиной. */
    private fun failReviewLaunch(task: TaskId, role: ReviewRole, attempt: Int, session: SessionId, why: String) {
        if (sessions.sessions[session]?.terminal == false) {
            commitSessions(sessionsLogic.transition(sessions, session, SessionState.FAILED, clock.now(), DiagCode.REVIEW_FAILED).valueOrNull().orEmpty())
        }
        releaseLease(session)
        recordReviewFailure(task, role, attempt, session, why)
    }

    /** Ревью роли без итога (D-59, п. 3): записать с причиной — повтор или запрос решит [settleReviews]. */
    private fun recordReviewFailure(task: TaskId, role: ReviewRole, attempt: Int, session: SessionId?, why: String) {
        problems += Diagnostic.warning(DiagCode.REVIEW_FAILED, "${Review.label(role)}: $why", "task $task")
        val record = ReviewRecord(role, ReviewVerdict.FAILED, attempt, session, reason = why.take(ReviewRecord.MAX_DETAIL))
        commitTasks(ok(tasksLogic.recordReview(tasks, task, record, clock.now()), "итог ревью").orEmpty())
        settleReviews(task)
    }

    /**
     * Сессия ревью дошла до конечного или ждущего состояния: итог по схеме
     * из ответа хода — или ревью без итога с причиной. Ждущая (предел,
     * вход) снимается: ревью не продолжается, а запускается заново.
     */
    private fun reactReview(task: TaskId, session: SessionId, roleName: String, state: SessionState) {
        val role = ReviewRole.entries.firstOrNull { it.name == roleName } ?: return
        val attempt = sessions.sessions[session]?.key?.substringAfterLast(':')?.toIntOrNull() ?: 1
        when (state) {
            SessionState.COMPLETED -> {
                val record = Review.parse(role, attempt, session, reviewOutput.remove(session))
                if (record.verdict == ReviewVerdict.FAILED) problems += Diagnostic.warning(DiagCode.REVIEW_FAILED, "${Review.label(role)}: ${record.reason}", "task $task")
                commitTasks(ok(tasksLogic.recordReview(tasks, task, record, clock.now()), "итог ревью").orEmpty())
                settleReviews(task)
            }
            SessionState.FAILED, SessionState.BLOCKED, SessionState.PAUSED -> {
                val reason = sessions.sessions[session]?.lastReason
                if (sessions.sessions[session]?.terminal == false) closeSession(task, session)
                reviewOutput.remove(session)
                recordReviewFailure(task, role, attempt, session, "сессия ревью ${state.name}" + (reason?.let { " ($it)" } ?: ""))
            }
            else -> Unit
        }
    }

    private fun askReview(task: TaskId, roles: List<ReviewRole>) {
        val why = roles.joinToString("; ") { r -> "${Review.label(r)}: ${tasks.tasks[task]?.reviews?.get(r)?.reason ?: "без итога"}" }
        ask(
            RequestPurpose.TASK_REVIEW, RequestKind.USER_RESOLUTION, "task:${task.value}:review",
            "Ревью без итога: ${taskTitle(task)}", "Проверка прошла, а ревью не дало итога и после повтора ($why). Повторить или принять без ревью?",
            listOf(
                RequestOption(RETRY, "Повторить ревью", OptionRole.ACCEPT),
                RequestOption(WAIVE, "Принять без ревью", OptionRole.DESTRUCTIVE),
                RequestOption(LATER, "Решу позже", OptionRole.NEUTRAL),
            ),
            task, null,
        )
    }

    /** Ответ на запрос ревью (D-59, п. 3): повтор — запись с решением человека; принять — исключение по ролям без итога. */
    private fun resolveReview(task: TaskId, option: String, by: String): Outcome<String> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.state != TaskState.VERIFIED) return Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "задача уже не VERIFIED: ${t.state}", "task $task")
        val failed = t.reviewsLeft().filter { t.reviews[it]?.verdict == ReviewVerdict.FAILED }
        return when (option) {
            RETRY -> {
                val es = failed.flatMap { r ->
                    val last = t.reviews.getValue(r)
                    ok(tasksLogic.recordReview(tasks, task, last.copy(by = by.take(ReviewRecord.MAX_TITLE), reason = "повтор по решению человека"), clock.now()), "повтор ревью").orEmpty()
                }
                commitTasks(es)
                Outcome.success("ревью будет повторено")
            }
            WAIVE -> {
                for (r in failed) {
                    when (val w = tasksLogic.waiveReview(tasks, task, r, by, "принято без ревью ответом на запрос", clock.now())) {
                        is Outcome.Failure -> return w
                        is Outcome.Success -> commitTasks(w.value)
                    }
                }
                settleReviews(task)
                Outcome.success("принято без ревью")
            }
            LATER -> Outcome.success("решение отложено")
            else -> Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "вариант «$option» не к этому запросу")
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

    /** Коммит работы задачи; отказ — его причина (A-52: раньше молча), `null` — закоммичено или нечего. */
    private fun commitWork(task: TaskId, session: SessionId, path: String): String? {
        val g = git ?: return null
        val status = ok(g.status(path), "git status") ?: return "git status не удался"
        val all = (status.changed + status.untracked).distinct().sorted()
        // Кеши и артефакты (их создаёт и сама проверка: `python -m unittest` — `__pycache__`) — не работа хода:
        // не коммитятся, и это видно, а не молча (D-05; проверка на Redmi Note 13, 9 октября 2026).
        val files = committable(all)
        (all - files.toSet()).takeIf { it.isNotEmpty() }?.let { skipped ->
            problems += Diagnostic.warning(
                DiagCode.VALUE_INVALID, "не закоммичены артефакты: ${skipped.take(5).joinToString()}${if (skipped.size > 5) " и ещё ${skipped.size - 5}" else ""}", "task $task",
            )
        }
        if (files.isEmpty()) return null
        val tree = treeOf.getValue(task)
        ok(g.write(worktrees, tree, session, GitOp.ADD, files), "git add") ?: return "git add не удался"
        gitOk = ok(g.write(worktrees, tree, session, GitOp.COMMIT, listOf("dpai: ${tasks.tasks.getValue(task).title}")), "git commit") != null
        return if (gitOk == true) null else "git commit не удался"
    }

    /** Слияние ждёт ответа — в журнал; сам запрос задаёт вызывающий после пачки (askMerges в такте — после перезапуска). */
    private fun recordMergeRequest(task: TaskId, branch: String) {
        val repo = config.repository ?: return
        val target = config.integrationBranch ?: return
        if (mergesState.pending.values.any { it.task == task }) return
        val proposal = Proposal("git.merge", "${repo.root}#$target", listOf(branch))
        commitMerges(listOf(MergeEvent.Requested(PendingMerge(task, ids.next(IdKinds.APPROVAL), proposal, clock.now() + requestTtl), clock.now())))
    }

    /**
     * Запрос «Слить?» (D-49) — обычный запрос человеку (D-44): уведомление,
     * окно поверх приложений, вкладка «Запросы», `dpai answer`. Ветка и
     * цель — в тексте, под хешем запроса: ответ на другое предложение не
     * пройдёт. [failure] — почему прошлое слияние не удалось (A-30).
     */
    private fun askMerge(m: PendingMerge, failure: String? = null) {
        val target = m.proposal.subject.substringAfterLast('#')
        val branch = m.proposal.arguments.joinToString(" ")
        val body = (failure?.let { "Слияние не удалось: $it. " } ?: "Проверка прошла. ") + "Ветка задачи $branch → $target."
        ask(
            RequestPurpose.TASK_MERGE, RequestKind.CONFIRMATION, "task:${m.task.value}:merge",
            "Слить в $target: ${taskTitle(m.task)}", body,
            listOf(RequestOption(MERGE, if (failure == null) "Слить" else "Повторить слияние", OptionRole.ACCEPT), RequestOption(DECLINE, "Не сливать", OptionRole.REJECT)),
            m.task, null,
        )
    }

    /** Ждущие слияния без ждущего запроса (истёк, перезапуск, прежний механизм одобрений) — спросить снова (A-51). */
    private fun askMerges() {
        for (m in mergesState.pending.values.sortedBy { it.approval.value }) {
            if (requests.pendingFor("task:${m.task.value}:merge") == null) askMerge(m)
        }
    }

    /** Ответ на запрос слияния: «Слить» — `git merge` предложения из журнала, «Не сливать» — отказ в журнал. */
    private fun resolveMerge(task: TaskId, option: String): Outcome<String> {
        val pending = mergesState.pending.values.firstOrNull { it.task == task }
            ?: return Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "слияние задачи уже не ждёт ответа", "task $task")
        return when (option) {
            DECLINE -> Outcome.success("слияние отклонено").also {
                commitMerges(listOf(MergeEvent.Declined(task, pending.approval, clock.now())))
                retireTree(task)
            }
            MERGE -> {
                val repo = config.repository ?: return Outcome.failure(DiagCode.UNSUPPORTED, "у проекта нет репозитория")
                val g = git ?: return Outcome.failure(DiagCode.UNSUPPORTED, "нет Git")
                val target = pending.proposal.subject.substringAfterLast('#')
                mergeBlocker(g, repo.root, target)?.let { return it }
                when (val m = g.run(GitOp.MERGE, repo.root, pending.proposal.arguments)) {
                    is Outcome.Failure -> m.also {
                        // A-54: конфликт оставил бы рабочую копию автора посреди слияния, и повтор упал бы
                        // на нём же. До слияния копия была чистой (mergeBlocker), так что отмена ничего не теряет.
                        g.run(GitOp.MERGE_ABORT, repo.root)
                    }
                    is Outcome.Success -> Outcome.success("слито в $target").also {
                        commitMerges(listOf(MergeEvent.Merged(task, pending.approval, clock.now())))
                        retireTree(task)
                    }
                }
            }
            else -> Outcome.failure(DiagCode.REQUEST_ANSWER_INVALID, "вариант «$option» не к этому запросу")
        }
    }

    /**
     * Дерево задачи после слияния или отказа от него (ревью A-65): работа —
     * в ветке задачи, её коммиты не трогаются, а дерево больше не нужно и
     * иначе копилось бы на диске target. Только `git worktree remove` без
     * `--force`: в дереве незакоммиченное или неотслеживаемое (кеши проверки
     * и т. п.) — git откажет, дерево останется, и автор увидит, что убрать
     * его — за ним. Разрушительного без одобрения нет (T10).
     */
    private fun retireTree(task: TaskId) {
        val id = treeOf[task] ?: return
        val w = worktrees.worktrees[id]?.takeIf { !it.removed && it.owner == null } ?: return
        val repo = config.repository ?: return
        val g = git ?: return
        when (val r = g.run(GitOp.WORKTREE_REMOVE, repo.root, listOf(w.path))) {
            is Outcome.Failure -> problems += Diagnostic.warning(
                DiagCode.GIT_COMMAND_FAILED,
                "дерево задачи оставлено: ${r.errors.first().message}; ветка ${w.branch} цела, удалить дерево — `git worktree remove --force ${w.path}`",
                "task $task",
            )
            is Outcome.Success -> {
                commitWorktrees(Worktrees.remove(worktrees, id, clock.now()).valueOrNull().orEmpty())
                treeOf.remove(task)
            }
        }
    }

    /**
     * Почему слить в [target] сейчас нельзя, или `null`. `git merge` сливает в
     * выгруженную ветку, а одобрено слияние в [target] (ревью A-68, A-53):
     * переключать ветку в рабочей копии автора молча нельзя — отказ с
     * причиной, и запрос «Повторить слияние» (A-30). Изменения и конфликты
     * в копии — тоже отказ: слияние поверх них нельзя чисто отменить (A-54).
     */
    private fun mergeBlocker(g: GitService, root: String, target: String): Outcome.Failure? {
        val s = when (val st = g.status(root)) {
            is Outcome.Failure -> return st
            is Outcome.Success -> st.value
        }
        return when {
            s.branch != target -> Outcome.failure(
                DiagCode.MERGE_TARGET_NOT_READY,
                "в рабочей копии выгружена ${s.branch?.let { "ветка $it" } ?: "не ветка (detached HEAD)"}, а слияние — в $target: выгрузите $target",
                root,
            )
            s.changed.isNotEmpty() || s.conflicted.isNotEmpty() -> Outcome.failure(
                DiagCode.MERGE_TARGET_NOT_READY,
                "в рабочей копии ${if (s.conflicted.isNotEmpty()) "незавершённое слияние" else "незакоммиченные изменения"}: закоммитьте или уберите их",
                root,
            )
            else -> null
        }
    }

    /** Ход прерван: handoff, прежняя сессия снята, задача ждёт продолжения (сценарий V1 2). */
    private fun handOff(task: TaskId, session: SessionId, by: HandoffReason? = null, next: String? = null) {
        writeHandoff(task, session, by, next)
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), DiagCode.SESSION_HANDED_OFF).valueOrNull().orEmpty())
        releaseLease(session)
        releaseTree(task, session)
        commitTasks(ok(tasksLogic.continueLater(tasks, task, clock.now()), "продолжение задачи").orEmpty())
    }

    /**
     * Handoff сессии: что сделано и изменено, что дальше. Причина по
     * умолчанию — по коду остановки хода; от человека — своя, и ключ
     * уникален: второй запрос в ту же сессию — второй handoff, а не повтор.
     */
    private fun writeHandoff(task: TaskId, session: SessionId, by: HandoffReason?, next: String?): Handoff {
        val s = sessions.sessions.getValue(session)
        val bundle = contextOf[task]
        val modified = treePath(task)?.let(::changedFiles).orEmpty()
        val id = ids.next(IdKinds.HANDOFF)
        val handoff = Handoff(
            id = id,
            key = if (by == null) "${session.value}:handoff" else "${session.value}:user:${id.value}",
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
        return handoff
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
                is AccountFact.Usage -> accountsLogic.observeUsage(accounts, slot, f.window, f.utilization, f.resetAt, clock.now())
            }
            commitAccounts(out.valueOrNull().orEmpty())
        }
    }

    private fun notify(key: String, category: NotificationCategory, title: String, body: String) =
        notify(NotificationRequest(key, category, title, body, null, clock.now()))

    private fun notify(r: NotificationRequest) {
        post(notifications.submit(r))
    }

    /**
     * Сверка после перезапуска (ТЗ, §54, §55; `docs/RECOVERY.md`, §1):
     * сессии, которые по журналу работали, — в `RECOVERY_PENDING`, затем
     * проба агента их target. Подтверждённо идущий ход подхватывает раннер
     * с записанной позиции; завершённые освобождают слот, и задача
     * реагирует на исход как при опросе. Target без агента ([agentFor] —
     * `null`) или не ответивший — сессия ждёт следующей сверки.
     */
    /** Агенты targets для сверки — запоминаются в [recover]: ждущие сессии сверяются и потом (A-44, A-76). */
    private var agentFor: ((ExecutionTargetId) -> AgentClient?)? = null
    private var lastReconcileAt = Long.MIN_VALUE / 2

    fun recover(agentFor: (ExecutionTargetId) -> AgentClient?): List<Reconciler.Result> {
        this.agentFor = agentFor
        commitSessions(Reconciler(sessionsLogic, clock).markPending(sessions))
        val out = reconcilePending(agentFor)
        // PAUSED без handoff: процесс умер между итогом хода и передачей (A-42) — передать сейчас.
        for (r in sessions.sessions.values.filter { it.state == SessionState.PAUSED }.sortedBy { it.id.value }) react(r.task, r.id, SessionState.PAUSED)
        resumeFinish()
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

    /**
     * Недоделанный итог хода (ревью A-56): процесс умер посреди `finish`. Ход
     * завершён (последняя сессия COMPLETED), живой сессии нет: задача RUNNING
     * — проверка не дошла до итога, проверить снова, а не запускать Claude
     * заново (так сделал бы OrphanScan); VERIFIED — коммит, `DONE`, слияние.
     */
    private fun resumeFinish() {
        val live = sessions.sessions.values.filter { !it.terminal }.map { it.task }.toSet()
        val stuck = tasks.tasks.values.filter { (it.state == TaskState.RUNNING || it.state == TaskState.VERIFIED) && it.id !in live }
        for (t in stuck.sortedBy { it.id.value }) {
            val last = sessions.sessions.values.filter { it.task == t.id }
                .maxWithOrNull(compareBy<com.dpsmpx.dpai.sessions.SessionRecord>({ it.createdAt.epochMillis }, { it.turn }, { it.id.value }))
            if (last?.state != SessionState.COMPLETED) continue
            if (t.state == TaskState.RUNNING) {
                finish(t.id, last.id)
            } else {
                val tree = treeOf[t.id]?.let { worktrees.worktrees[it] }
                afterVerified(t.id, last.id, tree, tree?.path ?: config.worktreeRoot ?: ".")
            }
        }
    }

    /**
     * Сверка сессий в `RECOVERY_PENDING` (ТЗ, §55): после старта и затем в
     * такте, пока target не ответит (A-44, A-76), — и для хода, потерянного
     * на target во время работы (A-43). Сессия, чей запуск не был записан до
     * сбоя, но ход был, — задача в работе, как при обычном запуске (A-42).
     */
    private fun reconcilePending(agentFor: (ExecutionTargetId) -> AgentClient?): List<Reconciler.Result> {
        lastReconcileAt = clock.now().epochMillis
        val reconciler = Reconciler(sessionsLogic, clock)
        val out = mutableListOf<Reconciler.Result>()
        for (r in sessions.sessions.values.filter { it.state == SessionState.RECOVERY_PENDING }.sortedBy { it.id.value }) {
            val agent = agentFor(r.target) ?: continue
            val res = reconciler.reconcile(sessions, r.id, agent)
            out += res
            commitSessions(res.events)
            val after = sessions.sessions.getValue(r.id)
            if (res.resolution == Resolution.RESOLVED_RUNNING) runner.adopt(r.id, r.provider, r.agentSequence, turnRunning = true)
            if (after.terminal) applyFacts(listOf(AccountFact.Release(r.id)))
            when {
                after.state == SessionState.RECOVERY_PENDING -> Unit
                // Хода не было: дерево и аренда свободны, задача ждёт запуска, попытка не считается.
                res.resolution == Resolution.RESOLVED_NOT_STARTED -> {
                    releaseTree(after.task, r.id)
                    // Запуск в такте с потерянным ответом (A-12, A-55): задача уже RUNNING — снова в очередь.
                    // После сбоя процесса её так же чинит OrphanScan в recover().
                    if (tasks.tasks[after.task]?.state == TaskState.RUNNING) {
                        commitTasks(tasksLogic.move(tasks, after.task, TaskState.READY, clock.now(), DiagCode.RECOVERY_START_UNCONFIRMED).valueOrNull().orEmpty())
                    }
                }
                else -> {
                    if (tasks.tasks[after.task]?.state == TaskState.READY) commitTasks(tasksLogic.start(tasks, after.task, clock.now()).valueOrNull().orEmpty())
                    react(after.task, r.id, after.state)
                }
            }
        }
        return out
    }

    // ---- команды (D-42) ----

    /** Положить команду в очередь; исполнит поток такта. Любой поток. */
    fun submit(command: Command): java.util.concurrent.CompletableFuture<OperationResult<*>> = inbox.submit(command)

    /**
     * Исполнить пришедшие команды — только поток такта и только в
     * безопасной точке: вне пачки, когда память совпадает с журналом.
     */
    fun runCommands(): Int = if (pending != null) 0 else inbox.drain(::execute)

    /**
     * Команда (D-06): с ключом — сначала по журналу ключей; уже была —
     * записанный итог без второго действия (ТЗ, §5.4).
     */
    fun execute(command: Command): OperationResult<*> {
        val key = command.key
        val provenance = Provenance(Actor(ActorKind.USER, (command as? Command.AnswerRequest)?.by ?: "автор"))
        if (key != null && !Command.KEY.matches(key)) {
            return OperationResult.record(command.operation, provenance, ids, clock, Outcome.failure(DiagCode.VALUE_INVALID, "ключ команды — [A-Za-z0-9_.:-]{8,80}"))
        }
        key?.let { operations.byKey[it] }?.let { prior ->
            val outcome: Outcome<String?> = if (prior.status == com.dpsmpx.dpai.core.operation.OperationStatus.FAILED) {
                Outcome.failure(prior.code ?: DiagCode.INTERNAL_ERROR, "команда с этим ключом уже исполнена: отказ")
            } else {
                Outcome.success(prior.value)
            }
            return OperationResult.record(prior.operation, provenance, ids, clock, outcome)
        }
        val result: OperationResult<*> = when (command) {
            is Command.AnswerRequest -> answer(command.uri, command.text, command.by, command.channel)
            is Command.Delivered -> OperationResult.record(command.operation, provenance, ids, clock, delivered(command.request, command.channel))
            is Command.Probe -> OperationResult.record(command.operation, provenance, ids, clock, probe(command.kind, command.ttl ?: requestTtl))
            is Command.AddTask -> OperationResult.record(
                command.operation, provenance, ids, clock,
                addTask(command.title, command.objective, command.priority, command.dependencies, scope = command.scope.ifEmpty { null }, policy = command.policy),
            )
            is Command.SetScope -> record(command, provenance) { taskEdit(command.task) { Tasks.setScope(tasks, command.task, command.scope, clock.now()) } }
            is Command.SetDependencies -> record(command, provenance) { taskEdit(command.task) { tasksLogic.setDependencies(tasks, command.task, command.dependencies, clock.now()) } }
            is Command.SetPriority -> record(command, provenance) { taskEdit(command.task) { tasksLogic.setPriority(tasks, command.task, command.priority, null, clock.now()) } }
            is Command.SetPolicy -> record(command, provenance) { setPolicy(command) }
            is Command.Hold -> record(command, provenance) { holdTask(command.task) }
            is Command.Resume -> record(command, provenance) { taskEdit(command.task) { tasksLogic.hold(tasks, command.task, false, clock.now()) } }
            is Command.Cancel -> record(command, provenance) { cancelTask(command.task) }
            is Command.Retry -> record(command, provenance) { retryTask(command.task, command.text) }
            is Command.StopSession -> record(command, provenance) {
                sessions.sessions[command.session]?.let { holdTask(it.task) } ?: Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session ${command.session}")
            }
            is Command.SendToSession -> record(command, provenance) { sendToSession(command.session, command.text, command.model, command.effort, command.fresh) }
            is Command.SetNearPercent -> record(command, provenance) {
                when (val r = accountsLogic.setNearPercent(accounts, command.subscription, command.percent, clock.now())) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> Outcome.success(command.percent).also { commitAccounts(r.value) }
                }
            }
            is Command.Verify -> record(command, provenance) { verifyTask(command.task) }
            is Command.SetTargetEnabled -> record(command, provenance) {
                when {
                    targets.none { it.id == command.target } -> Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого target у контура", "target ${command.target}")
                    (command.target !in control.disabledTargets) == command.enabled -> Outcome.success(command.enabled)
                    else -> {
                        write(listOf(recorder.targetEnabledEnvelope(command.target, command.enabled, clock.now())))
                        control = com.dpsmpx.dpai.orchestrator.commands.ControlProjection.setTarget(control, command.target, command.enabled)
                        Outcome.success(command.enabled)
                    }
                }
            }
            is Command.SetSessionLimit -> record(command, provenance) {
                when {
                    command.limit != null && command.limit !in com.dpsmpx.dpai.orchestrator.commands.SESSION_LIMIT_RANGE ->
                        Outcome.failure(DiagCode.VALUE_INVALID, "предел сессий — от 1 до 64 или без предела")
                    control.maxSessions == command.limit -> Outcome.success(command.limit)
                    else -> {
                        write(listOf(recorder.sessionLimitEnvelope(command.limit, clock.now())))
                        control = control.copy(maxSessions = command.limit)
                        Outcome.success(command.limit)
                    }
                }
            }
            is Command.SetSlotEnabled -> record(command, provenance) {
                val r = if (command.enabled) accountsLogic.enable(accounts, command.slot, clock.now()) else accountsLogic.disable(accounts, command.slot, clock.now())
                when (r) {
                    is Outcome.Failure -> r
                    is Outcome.Success -> Outcome.success(command.enabled).also { commitAccounts(r.value) }
                }
            }
            is Command.Archive -> record(command, provenance) { taskEdit(command.task) { tasksLogic.archive(tasks, command.task, command.archived, clock.now()) } }
            is Command.ArchiveFinished -> record(command, provenance) {
                val es = tasks.tasks.values.filter { it.terminal && !it.archived }.sortedBy { it.id.value }.flatMap { tasksLogic.archive(tasks, it.id, true, clock.now()).valueOrNull().orEmpty() }
                commitTasks(es)
                Outcome.success(es.size)
            }
            is Command.Waive -> record(command, provenance) {
                if (command.reason.isBlank()) Outcome.failure(DiagCode.VALUE_INVALID, "исключение — с причиной") else resolveUnverified(command.task, WAIVE, command.by, command.reason.trim())
            }
        }
        // Команда могла решить предмет запроса (задача ушла из состояния) — запрос снимается сразу, а не в такте.
        settleRequests()
        if (key != null) {
            val value = result.value?.let { v -> (v as? com.dpsmpx.dpai.core.ids.TypedId)?.value ?: v.toString() }
            val rec = OperationRecord(key, command.operation, result.status, value, result.diagnostics.firstOrNull { it.isError }?.code, clock.now())
            write(recorder.operationEnvelopes(listOf(rec)))
            operations = OperationsProjection.applyRecord(operations, rec)
        }
        return result
    }

    // ---- действия с задачами (D-42, п. 2) ----

    private fun record(command: Command, provenance: Provenance, body: () -> Outcome<*>): OperationResult<*> =
        OperationResult.record(command.operation, provenance.copy(taskId = (command as? TaskCommand)?.task), ids, clock, body())

    /** Правка задачи событиями логики задач; итог — состояние задачи после правки. */
    private fun taskEdit(task: TaskId, edit: () -> Outcome<List<TaskEvent>>): Outcome<TaskState> = when (val r = edit()) {
        is Outcome.Failure -> r
        is Outcome.Success -> {
            // Итог — состояние после правки: сначала журнал, потом ответ (в журнале ключей — то же).
            commitTasks(r.value)
            Outcome.success(tasks.tasks.getValue(task).state)
        }
    }

    private fun setPolicy(c: Command.SetPolicy): Outcome<TaskState> {
        val t = tasks.tasks[c.task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task ${c.task}")
        val policy = try {
            t.policy.copy(taskClass = c.taskClass, model = c.model?.trim()?.ifEmpty { null }, effort = c.effort?.trim()?.ifEmpty { null }, maxBudgetUsdCents = c.maxBudgetUsdCents)
        } catch (e: IllegalArgumentException) {
            return Outcome.failure(DiagCode.VALUE_INVALID, e.message ?: "политика недопустима", "task ${c.task}")
        }
        return taskEdit(c.task) { tasksLogic.setPolicy(tasks, c.task, policy, clock.now()) }
    }

    /**
     * Приостановить (D-42): задача удержана — планировщик её не берёт;
     * идущий ход остановлен, handoff `PAUSED` записан, сессия провайдера
     * сохранена для продолжения. Не удалось остановить ход (target
     * недоступен) — задача всё равно удержана, отказ — в итоге.
     */
    private fun holdTask(task: TaskId): Outcome<TaskState> {
        val held = taskEdit(task) { tasksLogic.hold(tasks, task, true, clock.now()) }
        if (held is Outcome.Failure) return held
        val failures = mutableListOf<Diagnostic>()
        for (s in openSessions(task).filter { it.state !in WAIT_STATES }) {
            when (val step = driver.pause(sessions, s.id)) {
                is Outcome.Failure -> failures += step.errors
                is Outcome.Success -> {
                    commitSessions(step.value.events)
                    handOff(task, s.id, HandoffReason.PAUSED, "продолжить с места остановки: ход приостановлен человеком")
                }
            }
        }
        return if (failures.isEmpty()) Outcome.success(tasks.tasks.getValue(task).state) else Outcome.Failure(failures)
    }

    /**
     * Отменить (D-42): идущий ход — остановка с уборкой процессов, ждущие
     * сессии сняты, аренда и дерево свободны, задача `CANCELLED`.
     */
    private fun cancelTask(task: TaskId): Outcome<TaskState> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.terminal) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "задача уже завершена: ${t.state}", "task $task")
        for (s in openSessions(task)) {
            if (s.state in WAIT_STATES) {
                closeSession(task, s.id)
                continue
            }
            when (val step = driver.cancel(sessions, s.id)) {
                is Outcome.Failure -> return step
                is Outcome.Success -> {
                    batch {
                        commitSessions(step.value.events)
                        applyFacts(step.value.facts)
                    }
                    releaseTree(task, s.id)
                }
            }
        }
        return taskEdit(task) { tasksLogic.move(tasks, task, TaskState.CANCELLED, clock.now(), DiagCode.CANCELLED_BY_USER) }
    }

    /**
     * Повтор (D-42): заблокированная — как ответ на её запрос (продолжение
     * той же сессии с ответом человека); упавшая и отменённая — снова в
     * очередь; счётчики сброшены.
     */
    private fun retryTask(task: TaskId, text: String?): Outcome<TaskState> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.state == TaskState.BLOCKED) {
            val clean = text?.trim()?.takeIf { it.isNotEmpty() }?.take(UserRequest.MAX_TEXT)
            val r = resolveBlocked(task, if (clean != null) REPLY else RETRY, clean)
            if (r is Outcome.Failure) return r
            commitTasks(listOf(TaskEvent.CountersReset(task, clock.now())))
            return Outcome.success(tasks.tasks.getValue(task).state)
        }
        if (text != null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "ответ к ходу — у заблокированной задачи", "task $task")
        return taskEdit(task) { tasksLogic.retry(tasks, task, clock.now()) }
    }

    /**
     * Проверить вручную (D-42, п. 3 раздела «Проверка и Git»): задача без
     * проверки — проверка в её дереве заново, без хода Claude. Дерево на
     * время коммита — у последней сессии задачи и сразу освобождается
     * (завершённая сессия дерево не держит, ТЗ, §118).
     */
    private fun verifyTask(task: TaskId): Outcome<TaskState> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.state != TaskState.COMPLETION_UNVERIFIED) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "проверяют задачу без проверки: задача ${t.state}", "task $task")
        val session = lastWork(task)?.id
            ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "у задачи не было сессии", "task $task")
        commitTasks(ok(tasksLogic.move(tasks, task, TaskState.VERIFYING, clock.now()), "проверка вручную") ?: return Outcome.failure(DiagCode.INVALID_TRANSITION, "не перейти в VERIFYING", "task $task"))
        val tree = treeOf[task]?.let { worktrees.worktrees[it] }
        val path = tree?.path ?: config.worktreeRoot ?: "."
        val flow = when (val f = completion.onClaimedDone(tasks, task, config.profile, config.level, path)) {
            is Outcome.Failure -> return f
            is Outcome.Success -> f.value
        }
        commitTasks(flow.events)
        if (tree != null) commitWorktrees(Worktrees.claim(worktrees, tree.id, session, clock.now()).valueOrNull().orEmpty())
        afterVerification(task, session, flow.report, tree, path)
        if (tree != null) releaseTree(task, session)
        return Outcome.success(tasks.tasks.getValue(task).state)
    }

    // ---- действия с сессиями (D-42, п. 3) ----

    /** Ход идёт (не ждёт человека и не завершён): в него не вмешиваются (D-04). */
    private fun turnRunning(task: TaskId) = openSessions(task).any { it.state !in WAIT_STATES }

    /**
     * Запрос в сессию (D-42; ТЗ, §12 `send`): новый ход той же сессии
     * провайдера с текстом человека — handoff `MANUAL` к последней сессии
     * задачи, задача снова в плане, счётчики сброшены, удержание снято.
     * [fresh] — свежая сессия с handoff (контрольная точка, D-40), а не
     * `--resume`. Модель и усилие запроса — в политику задачи (видно на
     * листе задачи). Пока ход идёт — отказ: в идущий процесс не пишут.
     */
    fun sendToSession(session: SessionId, text: String?, model: String?, effort: String?, fresh: Boolean): Outcome<TaskState> {
        val s = sessions.sessions[session] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой сессии", "session $session")
        val task = s.task
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }?.take(UserRequest.MAX_TEXT)
        if (clean == null && !fresh) return Outcome.failure(DiagCode.VALUE_INVALID, "запрос без текста")
        if (s.review != null) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "сессия ревью только читает (D-59): запрос — в рабочую сессию задачи", "session $session")
        if (turnRunning(task)) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "ход идёт: остановите его или дождитесь конца", "session $session")
        val last = lastWork(task)!!
        if (last.id != session) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "запрос — в последнюю сессию задачи ${last.id}", "session $session")
        reopenForTurn(task)?.let { return it }
        val reason = if (fresh) HandoffReason.TURN_FINISHED else HandoffReason.MANUAL
        val next = clean?.let { "запрос пользователя: $it" } ?: "продолжить работу в свежей сессии с этого места"
        if (!last.terminal) handOff(task, last.id, reason, next) else writeHandoff(task, last.id, reason, next)
        if (model != null || effort != null) {
            val t = tasks.tasks.getValue(task)
            val policy = try {
                t.policy.copy(model = model?.trim()?.ifEmpty { null } ?: t.policy.model, effort = effort?.trim()?.ifEmpty { null } ?: t.policy.effort)
            } catch (e: IllegalArgumentException) {
                return Outcome.failure(DiagCode.VALUE_INVALID, e.message ?: "модель или усилие недопустимы", "task $task")
            }
            commitTasks(ok(tasksLogic.setPolicy(tasks, task, policy, clock.now()), "модель запроса").orEmpty())
        }
        return Outcome.success(tasks.tasks.getValue(task).state)
    }

    /**
     * Задача снова в плане для хода по запросу человека: из любого
     * незанятого состояния — в `READY` или очередь; счётчики сброшены,
     * удержание снято. `null` — получилось, иначе отказ.
     */
    private fun reopenForTurn(task: TaskId): Outcome.Failure? {
        val t = tasks.tasks.getValue(task)
        val now = clock.now()
        val route: List<Pair<TaskState, com.dpsmpx.dpai.core.state.TransitionMode>> = when (t.state) {
            TaskState.READY, TaskState.QUEUED, TaskState.BACKLOG -> emptyList()
            TaskState.BLOCKED, TaskState.WAITING -> listOf(TaskState.READY to com.dpsmpx.dpai.core.state.TransitionMode.ORDINARY)
            TaskState.FAILED -> listOf(TaskState.QUEUED to com.dpsmpx.dpai.core.state.TransitionMode.EXPLICIT)
            TaskState.CANCELLED, TaskState.DONE -> listOf(
                TaskState.BACKLOG to com.dpsmpx.dpai.core.state.TransitionMode.EXPLICIT, TaskState.QUEUED to com.dpsmpx.dpai.core.state.TransitionMode.ORDINARY,
            )
            TaskState.COMPLETION_UNVERIFIED -> listOf(
                TaskState.VERIFYING to com.dpsmpx.dpai.core.state.TransitionMode.ORDINARY, TaskState.READY to com.dpsmpx.dpai.core.state.TransitionMode.ORDINARY,
            )
            else -> return Outcome.Failure(listOf(Diagnostic.error(DiagCode.OPERATION_NOT_ALLOWED, "задача ${t.state}: дождитесь конца проверки", "task $task")))
        }
        val es = mutableListOf<TaskEvent>(TaskEvent.CountersReset(task, now))
        if (t.held) es += TaskEvent.HoldChanged(task, false, now)
        var st = Tasks.applyAll(tasks, es)
        for ((to, mode) in route) {
            when (val r = tasksLogic.move(st, task, to, now, DiagCode.CANCELLED_BY_USER.takeIf { to == TaskState.READY && t.state == TaskState.BLOCKED }, mode = mode)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> {
                    es += r.value
                    st = Tasks.applyAll(st, r.value)
                }
            }
        }
        commitTasks(es)
        return null
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
                RequestOption(VERIFY, "Проверить снова", OptionRole.ACCEPT),
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
        return ask(RequestPurpose.PROBE, kind, "probe:$n", "Проверка запросов №$n: ${kind.label}", "Проверочный запрос: ответ только записывается в журнал.", options, null, null, ttl)
    }

    /** Канал показал запрос (`notification`, `overlay`, `app`): факт в журнал; закрытый — без записи. */
    fun delivered(id: RequestId, channel: String): Outcome<Unit> = when (val r = UserRequests.delivered(requests, id, channel, clock.now())) {
        is Outcome.Failure -> r
        is Outcome.Success -> Outcome.success(Unit).also { commitRequests(r.value) }
    }

    /** Ждущие запросы — для экрана и окна поверх приложений. */
    fun pendingRequests(): List<UserRequest> = requests.pending

    /** Кнопка уведомления `dpai://answer/…` и текст поля ввода, если он есть (D-44). */
    fun answer(uri: String, text: String?, by: String, channel: String? = null): OperationResult<RequestAnswer> = when (val a = RequestAction.parse(uri)) {
        is Outcome.Failure -> OperationResult.record("request.answer", Provenance(Actor(ActorKind.USER, by)), ids, clock, a)
        is Outcome.Success -> answer(a.value.request, a.value.option, text, a.value.hash, by, channel)
    }

    /**
     * Ответ на запрос (D-44, D-06): проверка — запрос ждёт, не истёк, хеш и
     * вариант его; затем действие по назначению запроса и запись исхода.
     * Повтор тем же вариантом — прежний ответ, без второго действия.
     */
    fun answer(id: RequestId, option: String, text: String?, hash: ContentHash, by: String, channel: String? = null): OperationResult<RequestAnswer> {
        val r = requests.requests[id]
        val provenance = Provenance(Actor(ActorKind.USER, by), taskId = r?.task, sessionId = r?.session)
        val outcome: Outcome<RequestAnswer> = when (val c = UserRequests.check(requests, id, option, text, hash, clock.now())) {
            is Outcome.Failure -> c.also { if (r != null && !r.terminal && r.expired(clock.now())) commitRequests(listOf(RequestEvent.Expired(id, clock.now()))) }
            is Outcome.Success -> when (val v = c.value) {
                is AnswerCheck.Repeat -> Outcome.success(v.request.answer!!)
                is AnswerCheck.Apply -> applyAnswer(v, by, channel)
            }
        }
        return OperationResult.record("request.answer", provenance, ids, clock, outcome)
    }

    private fun applyAnswer(a: AnswerCheck.Apply, by: String, channel: String?): Outcome<RequestAnswer> {
        val r = a.request
        val effect: Outcome<String> = when (RequestPurpose.of(r.purpose)) {
            RequestPurpose.PROBE, RequestPurpose.RECOVERY -> Outcome.success("записано")
            RequestPurpose.TASK_BLOCKED -> resolveBlocked(r.task!!, a.option.id, a.text)
            RequestPurpose.TASK_UNVERIFIED -> resolveUnverified(r.task!!, a.option.id, by)
            RequestPurpose.TASK_MERGE -> resolveMerge(r.task!!, a.option.id)
            RequestPurpose.TASK_REVIEW -> resolveReview(r.task!!, a.option.id, by)
            null -> Outcome.failure(DiagCode.UNSUPPORTED, "назначение запроса неизвестно: ${r.purpose}")
        }
        return when (effect) {
            is Outcome.Failure -> effect.also {
                commitRequests(listOf(RequestEvent.Failed(r.id, a.option.id, effect.errors.first().code, clock.now())))
                // Слияние не удалось (конфликт и т. п.) — сразу новый запрос с причиной, а не потерянная кнопка (A-30).
                if (RequestPurpose.of(r.purpose) == RequestPurpose.TASK_MERGE) {
                    mergesState.pending.values.firstOrNull { it.task == r.task }?.let { askMerge(it, effect.errors.first().message) }
                }
            }
            is Outcome.Success -> {
                val answer = RequestAnswer(a.option.id, a.text, by, clock.now(), effect.value, channel?.takeIf { CHANNEL.matches(it) })
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

    private fun resolveUnverified(task: TaskId, option: String, by: String, why: String = "принято без проверки ответом на запрос"): Outcome<String> {
        val t = tasks.tasks[task] ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        if (t.state != TaskState.COMPLETION_UNVERIFIED) return Outcome.failure(DiagCode.REQUEST_NOT_PENDING, "задача уже не COMPLETION_UNVERIFIED: ${t.state}", "task $task")
        return when (option) {
            WAIVE -> when (val c = tasksLogic.complete(tasks, task, clock.now(), by to why)) {
                is Outcome.Failure -> c
                is Outcome.Success -> Outcome.success("задача принята без проверки").also { commitTasks(c.value) }
            }
            CANCEL -> when (val c = tasksLogic.move(tasks, task, TaskState.CANCELLED, clock.now(), DiagCode.CANCELLED_BY_USER)) {
                is Outcome.Failure -> c
                is Outcome.Success -> Outcome.success("задача отменена").also { commitTasks(c.value) }
            }
            LATER -> Outcome.success("решение отложено")
            VERIFY -> when (val v = verifyTask(task)) {
                is Outcome.Failure -> v
                is Outcome.Success -> Outcome.success("проверка: задача ${v.value}")
            }
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
                // Ревью без итога (D-59): задача ушла из VERIFIED или ревью роли снова идёт — вопрос снят.
                RequestPurpose.TASK_REVIEW -> state != TaskState.VERIFIED || r.task?.let { t -> tasks.tasks[t]?.let { nextReview(it) != null } } == true
                else -> false
            }
        }
        val es = UserRequests.expire(requests, now) + stale.filter { !it.expired(now) }.map { RequestEvent.Cancelled(it.id, DiagCode.REQUEST_NOT_PENDING, now) }
        if (es.isNotEmpty()) commitRequests(es)
        askMerges()
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

    /**
     * События в памяти — активный сегмент журнала (D-37), по порядку записи;
     * старшие — в сегментах на диске. Для поиска (ТЗ, §75): журнал дописывает
     * такт, поэтому вызывать под замком такта, как [tick].
     */
    fun recentEvents(): List<EventEnvelope> = journal.records(journal.activeFrom).map { it.event }

    /** Номер первой записи в памяти: записи до него — в сегментах на диске (D-37). */
    val eventsFrom: Long get() = journal.activeFrom

    /** Последние версии контекстов задач в памяти (ТЗ, §75); под замком такта, как [recentEvents]. */
    fun contexts(): List<ContextBundle> = contextOf.values.toList()

    /** Проект контура: репозиторий, ветка интеграции (ТЗ, §75 — поиск по проектам). */
    val projectConfig: ProjectConfig get() = config

    /** Последняя версия контекста задачи — что видел Claude в последнем ходе. */
    fun lastContext(task: TaskId): ContextBundle? = contextOf[task] ?: storedContext(task, report = false)

    /** Итоги ходов задач в памяти — до завершения задачи; дальше — из документа. Читает и поток экрана. */
    private val messagesOf = java.util.concurrent.ConcurrentHashMap<TaskId, List<TurnMessage>>()

    /**
     * Итоговые сообщения ходов (D-42): что модель сказала, остановившись, —
     * целиком, в документ задачи `messages`, раньше реакции на исход хода.
     */
    private fun recordTurnMessages(session: SessionId, turns: List<com.dpsmpx.dpai.execution.claude.ClaudeTurn>) {
        if (turns.isEmpty()) return
        val rec = sessions.sessions.getValue(session)
        var list = turnMessages(rec.task)
        for ((i, t) in turns.withIndex()) {
            val text = t.text?.takeIf { it.isNotBlank() } ?: continue
            val turn = rec.turn - (turns.size - 1 - i)
            list = TurnMessages.append(list, TurnMessage(session, turn, clock.now(), t.status.name, rec.usage.lastPrompt?.model, text))
        }
        messagesOf[rec.task] = list
        saveTaskDocument("messages", rec.task, TurnMessages.toJson(list))
    }

    /**
     * Изменения задачи (D-42): от базы ветки интеграции до рабочего дерева
     * задачи, а после его уборки — до ветки задачи. Только чтение; дерево
     * ищется в неизменяемом снимке [worktrees], поэтому вызов безопасен вне
     * потока такта (экран, канал управления).
     */
    fun taskDiff(task: TaskId, maxChars: Int = com.dpsmpx.dpai.orchestrator.git.TaskDiff.MAX_CHARS): Outcome<com.dpsmpx.dpai.orchestrator.git.TaskDiff> {
        val g = git ?: return Outcome.failure(DiagCode.UNSUPPORTED, "у проекта нет репозитория: изменений задачи нет", "task $task")
        val repo = config.repository ?: return Outcome.failure(DiagCode.UNSUPPORTED, "у проекта нет репозитория: изменений задачи нет", "task $task")
        val base = config.integrationBranch ?: return Outcome.failure(DiagCode.UNSUPPORTED, "у проекта нет ветки интеграции", "task $task")
        if (task !in tasks.tasks) return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такой задачи", "task $task")
        val branch = TASK_BRANCH + task.value
        val tree = worktrees.worktrees.values.firstOrNull { it.branch == branch && !it.removed }
        return com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(g, tree?.path ?: repo.root, base, if (tree != null) null else branch, maxChars)
    }

    /** Итоговые сообщения ходов задачи по порядку — для окна задачи (D-42). */
    fun turnMessages(task: TaskId): List<TurnMessage> = messagesOf[task]
        ?: documents?.read(docKey("messages", task))?.valueOrNull()?.let { TurnMessages.fromJson(it.content).valueOrNull() }.orEmpty()

    /**
     * Работает ли Git проекта (самодиагностика, ТЗ, §79): итог последней
     * операции Git контура или проверки [checkGit]; неизвестно — `null`
     * (D-13: не «здоров»).
     */
    @Volatile
    var gitOk: Boolean? = null
        private set

    /** Проверить Git проекта: `git status` в каталоге репозитория. Без репозитория — ничего. */
    fun checkGit() {
        val repo = config.repository ?: return
        val g = git ?: return
        gitOk = g.status(repo.root) is Outcome.Success
    }

    /** Время контура (D-07) — для снимков снаружи. */
    fun now(): com.dpsmpx.dpai.core.clock.Timestamp = clock.now()

    /** Что ход сессии делает сейчас (D-42): только в памяти, для экрана. */
    fun live(session: SessionId): com.dpsmpx.dpai.sessions.LiveTurn? = driver.live(session)

    /** Отчёт расхода (D-40; `docs/EFFICIENCY.md`, §9). */
    fun usage(): com.dpsmpx.dpai.monitoring.UsageReport = com.dpsmpx.dpai.monitoring.UsageReport.build(sessions, tasks)

    fun dashboard(): DashboardView =
        Dashboard.build(sessions, tasks, accounts, emptyMap(), Measure.observed(mergesState.pending.size), sessionsLogic, clock.now())

    companion object {
        /** Сколько последних решений об уведомлениях держать в памяти. */
        const val POSTED_KEEP = 500

        /** Сессии, которые не опрашиваются: ждут человека или сверки. */
        /** Ветка дерева задачи: по ней дерево узнаётся после перезапуска. */
        private const val TASK_BRANCH = "task/"

        /** Каталоги и файлы, которые порождают сборка и запуск, а не автор: кеши байткода, зависимостей, проверок. */
        private val ARTIFACT_DIRS = setOf("__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", "node_modules", ".gradle", ".tox", ".venv")
        private val ARTIFACT_FILES = Regex(""".*\.(pyc|pyo)$|(^|.*/)\.DS_Store$""")

        /** Что из изменённого коммитится: без кешей и артефактов ([ARTIFACT_DIRS], [ARTIFACT_FILES]). */
        fun committable(files: List<String>): List<String> =
            files.filter { f -> f.split('/').none { it in ARTIFACT_DIRS } && !ARTIFACT_FILES.matches(f) }

        /** Ключ источника «что среда делает сама» (D-40). */
        const val ENVIRONMENT_SOURCE = "orchestrator:environment"

        /** Ключ источника с выжимкой проваленной проверки (D-40). */
        const val VERIFICATION_SOURCE = "tool:verification"

        /** Ключ источника с выжимкой блокирующего ревью (D-59, п. 3). */
        const val REVIEW_SOURCE = "tool:review"

        /** Изменённых файлов в handoff — не больше: список, а не дамп рабочего дерева. */
        private const val MAX_HANDOFF_FILES = 200

        private val WAIT_STATES = setOf(SessionState.PAUSED, SessionState.BLOCKED, SessionState.RECOVERY_PENDING)

        /** Сколько последних отказов держит [problems]; все — в журнале приложения. */
        const val KEPT_PROBLEMS = 200

        /** Как часто такт сверяет сессии в `RECOVERY_PENDING` с target (A-44). */
        val RECONCILE_EVERY: Duration = 1.minutes

        /** Пауза перед новым запуском, когда target недоступен до начала хода (A-50). */
        val TARGET_RETRY: Duration = 1.minutes

        /** Варианты ответов на запросы оркестратора (D-44). */
        const val RETRY = "retry"
        const val REPLY = "reply"
        const val CANCEL = "cancel"
        const val WAIVE = "waive"
        const val LATER = "later"
        const val ACK = "ack"
        const val VERIFY = "verify"
        const val MERGE = "merge"
        const val DECLINE = "decline"

        /** Канал ответа — короткое слово латиницей (`notification`, `overlay`, `app`, `cli`). */
        private val CHANNEL = Regex("[a-z]{1,16}")
    }
}
