package com.dpsmpx.dpai.orchestrator.recovery

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.accounts.AuthStatus
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.AccountId
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.ClaudeRequest
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.orchestrator.accounts.AccountsProjection
import com.dpsmpx.dpai.orchestrator.accounts.accountEnvelopes
import com.dpsmpx.dpai.orchestrator.sessions.SessionsProjection
import com.dpsmpx.dpai.orchestrator.sessions.sessionEnvelopes
import com.dpsmpx.dpai.sessions.AccountFact
import com.dpsmpx.dpai.sessions.SessionDriver
import com.dpsmpx.dpai.sessions.SessionEvent
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import java.nio.file.Path

/**
 * Стенд восстановления: оркестратор с журналом на диске и target
 * ([host]), который переживает перезапуски оркестратора. Всё, что
 * оркестратор знает, — только из журнала: [crash] выбрасывает память и
 * поднимает состояние заново ([boot]).
 */
class RecoveryHarness(private val dir: Path, seed: Long) {
    val clock = ManualClock()
    val ids = IdGenerator(clock, RandomSource.seeded(seed))
    val host = MockAgentHost(clock, IdGenerator(clock, RandomSource.seeded(seed + 1)))
    val scenarios = HashMap<SessionId, MockScenario>()
    private val accountsLogic = Accounts()
    private val sessionsLogic = Sessions()
    private val reconciler = Reconciler(sessionsLogic, clock)
    private val orphans = OrphanScan(accountsLogic, Tasks())

    lateinit var journal: EventJournal
    lateinit var recorder: EventRecorder
    lateinit var runner: MockRunner
    lateinit var driver: SessionDriver
    var accounts = AccountsState.EMPTY
    var sessions = SessionsState.EMPTY
    val resolutions = mutableListOf<Resolution>()

    /** Сколько событий записать до «убийства» приложения в середине коммита; `null` — всё. */
    var crashAfterEvents: Int? = null

    private class Killed : RuntimeException()

    val agent: AgentClient get() = AgentClient(host, listOf("dpai"), null, "target mock")

    init {
        boot()
    }

    fun boot() {
        journal = (EventJournal.open(NioVfs(dir), "data/journal.log") as Outcome.Success).value
        recorder = EventRecorder(ids, clock).also { it.resumeFrom(journal.records()) }
        accounts = StateRecovery(AccountsProjection).replayAll(journal)
        sessions = StateRecovery(SessionsProjection).replayAll(journal)
        runner = MockRunner.create(clock, RandomSource.seeded(clock.now().epochMillis), host) { s, _ -> scenarios[s] ?: MockScenario.SUCCESS }
        driver = SessionDriver(sessionsLogic, runner, clock)
        commitS(reconciler.markPending(sessions))
        reconcilePending()
        val report = orphans.scan(sessions, accounts, TasksState.EMPTY, WorktreesState.EMPTY, emptySet(), clock.now())
        commitA(report.accountFixes)
    }

    /** Сверка всех сессий в `RECOVERY_PENDING`; подтверждённо идущие подхватывает новый раннер. */
    fun reconcilePending() {
        for (r in sessions.sessions.values.filter { it.state == SessionState.RECOVERY_PENDING }.sortedBy { it.id.value }) {
            val res = reconciler.reconcile(sessions, r.id, agent)
            resolutions += res.resolution
            commitS(res.events)
            if (res.resolution == Resolution.RESOLVED_RUNNING) runner.adopt(r.id, r.provider, r.agentSequence, turnRunning = true)
            if (sessions.sessions.getValue(r.id).terminal) commitFacts(listOf(AccountFact.Release(r.id)))
        }
    }

    /** «Убить» приложение: память — прочь, потом снова [boot]. */
    fun crash() = boot()

    fun commitS(events: List<SessionEvent>) = commit(recorder.sessionEnvelopes(events)) { k -> sessions = Sessions.applyAll(sessions, events.take(k)) }

    fun commitA(events: List<AccountEvent>) = commit(recorder.accountEnvelopes(events)) { k -> accounts = Accounts.applyAll(accounts, events.take(k)) }

    private fun commit(envs: List<com.dpsmpx.dpai.core.events.EventEnvelope>, applied: (Int) -> Unit) {
        val limit = crashAfterEvents
        for ((i, e) in envs.withIndex()) {
            if (limit != null && i >= limit) {
                applied(i)
                crashAfterEvents = null
                throw Killed()
            }
            check(journal.append(e).isSuccess)
        }
        applied(envs.size)
    }

    /** Действие оркестратора; «убийство» посреди коммита — перезапуск. */
    fun step(action: () -> Unit) {
        try {
            action()
        } catch (_: Killed) {
            crash()
        }
    }

    fun commitFacts(facts: List<AccountFact>) {
        for (f in facts) {
            val slot = accounts.slotOf(f.session)?.id ?: continue
            val out = when (f) {
                is AccountFact.Release -> accountsLogic.release(accounts, f.session, clock.now())
                is AccountFact.Success -> accountsLogic.observeSuccess(accounts, slot, clock.now())
                is AccountFact.AuthFailure -> accountsLogic.observeAuthFailure(accounts, slot, clock.now())
                is AccountFact.Error -> accountsLogic.observeError(accounts, slot, f.code, clock.now())
                is AccountFact.Limit -> accountsLogic.observeLimit(accounts, slot, f.window, f.level, f.resetAt, clock.now())
            }
            commitA(out.valueOrNull().orEmpty())
        }
    }

    fun slots(n: Int): List<AccountId> {
        val sub = ids.next(IdKinds.SUBSCRIPTION)
        commitA(accountsLogic.registerSubscription(accounts, sub, "основная", clock.now(), maxParallelSessions = 64).valueOrNull()!!)
        return List(n) { i ->
            val slot = ids.next(IdKinds.ACCOUNT)
            commitA(accountsLogic.registerSlot(accounts, slot, sub, ids.next(IdKinds.TARGET), "/home/dev/.claude-$i", clock.now()).valueOrNull()!!)
            val status = AuthStatus(true, AuthStatus.CLAUDE_AI, AuthStatus.FIRST_PARTY, AuthStatus.fingerprint("org", "a@example.org"), "max")
            commitA(accountsLogic.observeAuthStatus(accounts, slot, status, clock.now()).valueOrNull()!!)
            slot
        }
    }

    fun launch(scenario: MockScenario): SessionId {
        val id = ids.next(IdKinds.SESSION)
        scenarios[id] = scenario
        val task = ids.next(IdKinds.TASK)
        val lease = accountsLogic.lease(accounts, id, task, clock.now()).valueOrNull()!!
        commitA(lease.events)
        val target = accounts.slots.getValue(lease.slot).targetId
        val created = sessionsLogic.create(sessions, accounts, id, "k-${id.value}", task, lease.slot, target, ProviderSessionId.newUuid(RandomSource.seeded(id.hashCode().toLong())), clock.now())
        commitS(created.valueOrNull()!!.events)
        val step = driver.launch(sessions, accounts, id, ClaudeRequest("задача"))
        step.valueOrNull()?.let { commitS(it.events); commitFacts(it.facts) }
        return id
    }

    /** Опрос живых сессий, как такт оркестратора; недоступный target — пропуск. */
    fun pollAll() {
        if (!host.reachable) return
        for (r in sessions.sessions.values.filter { !it.terminal && it.state != SessionState.RECOVERY_PENDING && it.state != SessionState.PAUSED && it.state != SessionState.BLOCKED }.sortedBy { it.id.value }) {
            val out = driver.poll(sessions, r.id).valueOrNull() ?: continue
            commitS(out.events)
            commitFacts(out.facts)
        }
    }
}
