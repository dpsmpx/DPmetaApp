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
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.ClaudeRunner
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.monitoring.Dashboard
import com.dpsmpx.dpai.monitoring.DashboardView
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
) {
    private val recorder = EventRecorder(ids, clock).also { it.resumeFrom(journal.records()) }
    private val accountsLogic = Accounts()
    private val sessionsLogic = Sessions()
    private val tasksLogic = Tasks()
    private val driver = SessionDriver(sessionsLogic, runner, clock)
    private val completion = CompletionFlow(tasksLogic, verifier, git, clock)
    val handoffs = Handoffs()
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
    private val merges = HashMap<ApprovalId, Pair<TaskId, Proposal>>()
    val merged = mutableListOf<TaskId>()
    val problems = mutableListOf<Diagnostic>()

    init {
        config.repository?.let { repo -> worktrees = Worktrees.applyAll(worktrees, Worktrees.register(worktrees, repo, clock.now()).valueOrNull().orEmpty()) }
    }

    // ---- журнал ----

    private fun write(envs: List<EventEnvelope>) = envs.forEach { e -> check(journal.append(e).isSuccess) { "журнал не принял ${e.type}" } }

    fun commitAccounts(es: List<AccountEvent>) {
        write(recorder.accountEnvelopes(es))
        accounts = Accounts.applyAll(accounts, es)
    }

    private fun commitSessions(es: List<SessionEvent>) {
        write(recorder.sessionEnvelopes(es))
        sessions = Sessions.applyAll(sessions, es)
    }

    private fun commitTasks(es: List<TaskEvent>) {
        write(recorder.taskEnvelopes(es))
        tasks = Tasks.applyAll(tasks, es)
    }

    private fun <T> ok(o: Outcome<T>, what: String): T? = when (o) {
        is Outcome.Success -> o.value
        is Outcome.Failure -> null.also { problems += Diagnostic.of(DiagCode.INTERNAL_ERROR, what, causes = o.errors) }
    }

    // ---- команды ----

    fun addTask(title: String, objective: String, priority: Priority = Priority.NORMAL, dependencies: Set<TaskId> = emptySet(), capabilities: Set<String> = emptySet()): Outcome<TaskId> {
        val id = ids.next(IdKinds.TASK)
        val created = tasksLogic.create(tasks, id, title, objective, priority, clock.now(), dependencies, capabilities)
        if (created is Outcome.Failure) return created
        commitTasks(created.valueOrNull()!!)
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
        for (a in tasksLogic.plan(tasks, clock.now(), offers)) launch(a.task, a.target)
        for (s in sessions.sessions.values.filter { it.state !in WAIT_STATES && !it.terminal }.map { it.id }.sorted()) poll(s)
    }

    private fun List<SessionId>.sorted() = sortedBy { it.value }

    private fun launch(task: TaskId, target: ExecutionTargetId) {
        val t = tasks.tasks.getValue(task)
        val session = ids.next(IdKinds.SESSION)
        val lease = accountsLogic.lease(accounts, session, task, clock.now(), target)
        if (lease is Outcome.Failure) return // свободного слота нет: задача ждёт следующего такта
        commitAccounts(lease.valueOrNull()!!.events)
        val slot = lease.valueOrNull()!!.slot
        // Продолжение — прерванная сессия той же задачи: та же сессия провайдера.
        val previous = sessions.sessions.values.filter { it.task == task }.maxByOrNull { it.createdAt.epochMillis * 1000 + it.turn }
        val continues = previous?.takeIf { it.state == SessionState.CANCELLED && it.lastReason == DiagCode.SESSION_HANDED_OFF }
        val provider = continues?.provider ?: ProviderSessionId.newUuid(random)
        val attempt = sessions.sessions.values.count { it.task == task } + 1
        val created = ok(sessionsLogic.create(sessions, accounts, session, "task:${task.value}:attempt:$attempt", task, slot, target, provider, clock.now(), continues?.id), "сессия задачи $task не создана")
            ?: return releaseLease(session)
        commitSessions(created.events)
        val workDir = prepareWorktree(task, session) ?: return failLaunch(task, session, DiagCode.WORKTREE_MISSING)
        val bundle = context(task, t.objective)
        val budget = t.policy.maxBudgetUsdCents?.let { BigDecimal(it).movePointLeft(2) }
        val request = ClaudeRequest(PromptRenderer.render(bundle), workDir = workDir, timeout = t.policy.turnTimeout, maxBudgetUsd = budget)
        val step = ok(driver.launch(sessions, accounts, session, request), "сессия $session не запущена") ?: return failLaunch(task, session, DiagCode.SESSION_FAILED)
        commitSessions(step.events)
        applyFacts(step.facts)
        commitTasks(tasksLogic.start(tasks, task, clock.now()).valueOrNull().orEmpty())
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
            val branch = "task/${task.value}"
            val ev = ok(Worktrees.create(worktrees, id, repo.id, path, branch, clock.now()), "дерево задачи не зарегистрировано") ?: return null
            if (ok(g.run(GitOp.WORKTREE_ADD, repo.root, listOf(path, branch)), "git worktree add") == null) return null
            worktrees = Worktrees.applyAll(worktrees, ev)
            treeOf[task] = id
            id
        }
        worktrees = Worktrees.applyAll(worktrees, ok(Worktrees.claim(worktrees, tree, session, clock.now()), "дерево занято") ?: return null)
        return worktrees.worktrees.getValue(tree).path
    }

    private fun context(task: TaskId, objective: String): ContextBundle {
        val previous = contextOf[task]
        val handoff = handoffs.latest(task)
        val sources = config.sources(task) + listOfNotNull(handoff?.asSource())
        val bundle = ContextAssembler.assemble(
            previous?.id ?: ids.next(IdKinds.CONTEXT), task, config.project, objective, handoff?.nextAction, sources, clock.now(), previous,
        ).valueOrNull()!!
        contextOf[task] = bundle
        return bundle
    }

    private fun poll(session: SessionId) {
        val before = sessions.sessions.getValue(session).state
        val step = driver.poll(sessions, session).valueOrNull() ?: return
        commitSessions(step.events)
        applyFacts(step.facts)
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
        merges[id] = task to proposal
        notify(approvals.request(id, proposal, clock.now()))
    }

    /**
     * Ответ на кнопку одобрения (ТЗ, §37, §68): слияние ветки задачи в ветку
     * интеграции — только по действительному одобрению своего предложения.
     */
    fun respond(uri: String, by: String): Outcome<Boolean> {
        val approval = when (val r = approvals.respond(uri, by, clock.now())) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value ?: return Outcome.success(false)
        }
        val (task, proposal) = merges.remove(approval.id) ?: return Outcome.failure(DiagCode.APPROVAL_INVALID, "одобрение не к слиянию")
        if (proposal.hash != approval.proposalHash) return Outcome.failure(DiagCode.APPROVAL_INVALID, "одобрено другое предложение")
        val repo = config.repository!!
        val g = git ?: return Outcome.failure(DiagCode.UNSUPPORTED, "нет Git")
        return when (val m = g.run(GitOp.MERGE, repo.root, proposal.arguments)) {
            is Outcome.Failure -> m
            is Outcome.Success -> Outcome.success(true).also { merged += task }
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
        commitSessions(sessionsLogic.transition(sessions, session, SessionState.CANCELLED, clock.now(), DiagCode.SESSION_HANDED_OFF).valueOrNull().orEmpty())
        releaseLease(session)
        releaseTree(task, session)
        commitTasks(ok(tasksLogic.continueLater(tasks, task, clock.now()), "продолжение задачи").orEmpty())
    }

    private fun releaseTree(task: TaskId, session: SessionId) {
        val tree = treeOf[task] ?: return
        worktrees = Worktrees.applyAll(worktrees, Worktrees.release(worktrees, tree, session, clock.now()).valueOrNull().orEmpty())
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

    fun dashboard(): DashboardView =
        Dashboard.build(sessions, tasks, accounts, emptyMap(), Measure.observed(approvals.pending()), sessionsLogic, clock.now())

    companion object {
        /** Сессии, которые не опрашиваются: ждут человека или сверки. */
        private val WAIT_STATES = setOf(SessionState.PAUSED, SessionState.BLOCKED, SessionState.RECOVERY_PENDING)
    }
}
