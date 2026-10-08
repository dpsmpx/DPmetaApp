package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.contexts.ContextAssembler
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
import java.math.BigDecimal

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
) {
    private val recorder = EventRecorder(ids, clock)
    private var lastSnapshotAt = 0L
    private val accountsLogic = Accounts()
    private val sessionsLogic = Sessions()
    private val tasksLogic = Tasks()
    private val driver = SessionDriver(sessionsLogic, runner, clock)
    private val completion = CompletionFlow(tasksLogic, verifier, git, clock)
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
    /** Ожидающие и выполненные слияния — из журнала (`MergesProjection`). */
    var mergesState: MergesState = MergesState.EMPTY
        private set

    /** Слитые задачи по порядку. */
    val merged: List<TaskId> get() = mergesState.merged.keys.toList()

    /** Одобрение каждого слияния — запись для инварианта «разрушительное — с одобрением» (ТЗ, §118). */
    val mergeApprovals: Map<TaskId, ApprovalId> get() = mergesState.merged
    val problems = mutableListOf<Diagnostic>()

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
                    is Outcome.Success -> contextOf[t.id] = c.value
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
    ): Outcome<TaskId> {
        val id = ids.next(IdKinds.TASK)
        val created = tasksLogic.create(tasks, id, title, objective, priority, clock.now(), dependencies, capabilities)
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
        // Продолжение — прерванная сессия той же задачи: та же сессия провайдера.
        val previous = sessions.sessions.values.filter { it.task == task }.maxByOrNull { it.createdAt.epochMillis * 1000 + it.turn }
        val continues = previous?.takeIf { it.state == SessionState.CANCELLED && it.lastReason == DiagCode.SESSION_HANDED_OFF }
        val provider = continues?.provider ?: ProviderSessionId.newUuid(random)
        val attempt = sessions.sessions.values.count { it.task == task } + 1
        // Пачка 1 — до вызова target: аренда и сессия с id провайдера (сверка после сбоя узнаёт поток).
        batch {
            commitAccounts(lease.valueOrNull()!!.events)
            ok(sessionsLogic.create(sessions, accounts, session, "task:${task.value}:attempt:$attempt", task, slot, target, provider, clock.now(), continues?.id), "сессия задачи $task не создана")
                ?.also { commitSessions(it.events) }
        } ?: return releaseLease(session)
        val workDir = prepareWorktree(task, session) ?: return failLaunch(task, session, DiagCode.WORKTREE_MISSING)
        val bundle = context(task, t.objective) ?: return failLaunch(task, session, DiagCode.VALUE_INVALID)
        val budget = t.policy.maxBudgetUsdCents?.let { BigDecimal(it).movePointLeft(2) }
        val request = ClaudeRequest(
            PromptRenderer.render(bundle), permissionMode = ClaudeCodeCli.TURN_PERMISSION_MODE,
            workDir = workDir, timeout = t.policy.turnTimeout, maxBudgetUsd = budget,
        )
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
    }

    private fun failLaunch(task: TaskId, session: SessionId, code: DiagCode) {
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), code).valueOrNull().orEmpty())
        releaseLease(session)
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

    /** Контекст хода; `null` — не собрался (причина — в [problems]): запуск не идёт, а не падает посреди такта. */
    private fun context(task: TaskId, objective: String): ContextBundle? {
        val previous = contextOf[task] ?: storedContext(task)
        val handoff = handoffs.latest(task)
        val sources = config.sources(task) + listOfNotNull(handoff?.asSource())
        val bundle = ok(
            ContextAssembler.assemble(
                previous?.id ?: ids.next(IdKinds.CONTEXT), task, config.project, objective, handoff?.nextAction, sources, clock.now(), previous,
            ),
            "контекст задачи $task не собран",
        ) ?: return null
        if (contextOf[task] !== bundle) saveTaskDocument("context", task, bundle.toJson())
        contextOf[task] = bundle
        return bundle
    }

    private fun poll(session: SessionId) {
        val before = sessions.sessions.getValue(session).state
        val step = driver.poll(sessions, session).valueOrNull() ?: return
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
            }
            SessionState.BLOCKED -> {
                commitTasks(tasksLogic.move(tasks, task, TaskState.BLOCKED, clock.now(), sessions.sessions.getValue(session).lastReason).valueOrNull().orEmpty())
                notify("task:${task.value}:blocked", NotificationCategory.BLOCKED, "Задача остановлена", "нужно вмешательство: ${sessions.sessions.getValue(session).lastReason}")
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
                if (tree != null) requestMerge(task, tree.branch)
            }
            TaskState.COMPLETION_UNVERIFIED -> {
                releaseTree(task, session)
                notify("task:${task.value}:unverified", NotificationCategory.WARNING, "Задача без проверки", "решите: проверить вручную или записать исключение")
            }
            else -> releaseTree(task, session) // READY: проверка не прошла — следующий исполнитель с тем же деревом
        }
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
    private fun handOff(task: TaskId, session: SessionId) {
        val s = sessions.sessions.getValue(session)
        val bundle = contextOf[task]
        val handoff = Handoff(
            id = ids.next(IdKinds.HANDOFF),
            key = "${session.value}:handoff",
            reason = if (s.lastReason == DiagCode.CLAUDE_RATE_LIMITED) HandoffReason.ACCOUNT_EXHAUSTED else HandoffReason.NEAR_LIMIT,
            session = session,
            task = task,
            project = config.project,
            context = bundle?.id ?: ids.next(IdKinds.CONTEXT),
            contextHash = bundle?.hash ?: com.dpsmpx.dpai.core.hashing.Hashing.sha256(""),
            state = "ход ${s.turn} прерван: ${s.lastReason ?: "без причины"}",
            completed = emptyList(), verified = emptyList(), failed = emptyList(),
            warnings = listOfNotNull(s.lastReason?.name), openProblems = emptyList(), currentAction = null,
            nextAction = "продолжить с места остановки в той же сессии",
            modifiedFiles = emptyList(), commit = null, branch = treeOf[task]?.let { worktrees.worktrees[it]?.branch },
            workspace = treeOf[task]?.let { worktrees.worktrees[it]?.path }, createdAt = clock.now(),
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
        maybeSnapshot()
        return out
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

    fun dashboard(): DashboardView =
        Dashboard.build(sessions, tasks, accounts, emptyMap(), Measure.observed(approvals.pending()), sessionsLogic, clock.now())

    companion object {
        /** Сессии, которые не опрашиваются: ждут человека или сверки. */
        /** Ветка дерева задачи: по ней дерево узнаётся после перезапуска. */
        private const val TASK_BRANCH = "task/"

        private val WAIT_STATES = setOf(SessionState.PAUSED, SessionState.BLOCKED, SessionState.RECOVERY_PENDING)
    }
}
