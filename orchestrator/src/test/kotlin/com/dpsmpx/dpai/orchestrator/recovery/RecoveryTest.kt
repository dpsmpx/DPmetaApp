package com.dpsmpx.dpai.orchestrator.recovery

import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.orchestrator.sessions.SessionsProjection
import com.dpsmpx.dpai.storage.snapshot.StateRecovery
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import com.dpsmpx.dpai.testkit.Gen
import com.dpsmpx.dpai.testkit.Property
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Восстановление (критерии этапа 13; ТЗ, §54, §55, §95, §119;
 * `docs/RECOVERY.md`): строки матрицы и хаос-тест.
 */
class RecoveryTest {
    @TempDir
    lateinit var dir: Path

    private fun harness(seed: Long = 1) = RecoveryHarness(Files.createTempDirectory(dir, "h"), seed)

    // ---- строки матрицы ----

    @Test
    fun appKilledWhileSessionRunsIsReconciledAndContinued() {
        val h = harness()
        h.slots(2)
        val s = h.launch(MockScenario.SLOW_OUTPUT)
        h.pollAll()
        h.host.advance(s)
        h.pollAll()
        assertEquals(SessionState.RUNNING, h.sessions.sessions.getValue(s).state)
        h.crash()
        assertEquals(listOf(Resolution.RESOLVED_RUNNING), h.resolutions)
        assertEquals(SessionState.RUNNING, h.sessions.sessions.getValue(s).state)
        // Подхваченная сессия доходит до конца без повтора уже принятых событий.
        while (h.host.advance(s)) h.pollAll()
        h.pollAll()
        assertEquals(SessionState.COMPLETED, h.sessions.sessions.getValue(s).state)
        assertTrue(h.accounts.slots.values.none { s in it.sessions }, "аренда возвращена")
    }

    @Test
    fun sessionFinishedWhileAppWasDownIsResolvedCompleted() {
        val h = harness()
        h.slots(1)
        val s = h.launch(MockScenario.SLOW_OUTPUT)
        h.pollAll()
        while (h.host.advance(s)) continue // приложения нет — ход дошёл до конца на target
        h.crash()
        assertEquals(listOf(Resolution.RESOLVED_COMPLETED), h.resolutions)
        assertEquals(SessionState.COMPLETED, h.sessions.sessions.getValue(s).state)
    }

    /** Ревью A-64: итог хода — за первой страницей событий агента; сверка читает все страницы. */
    @Test
    fun finishedTurnBeyondFirstPageIsResolvedCompleted() {
        val h = RecoveryHarness(Files.createTempDirectory(dir, "h"), 1, reconcilePage = 2)
        h.slots(1)
        val s = h.launch(MockScenario.SLOW_OUTPUT)
        h.pollAll()
        while (h.host.advance(s)) continue
        h.crash()
        assertEquals(listOf(Resolution.RESOLVED_COMPLETED), h.resolutions)
        assertEquals(SessionState.COMPLETED, h.sessions.sessions.getValue(s).state)
    }

    @Test
    fun targetGoneLeavesSessionPendingNotFailed() {
        val h = harness()
        h.slots(1)
        val s = h.launch(MockScenario.SLOW_OUTPUT)
        h.pollAll()
        h.host.reachable = false
        h.crash()
        assertEquals(listOf(Resolution.UNKNOWN), h.resolutions)
        assertEquals(SessionState.RECOVERY_PENDING, h.sessions.sessions.getValue(s).state, "неизвестно — не «упала»")
        h.host.reachable = true
        h.reconcilePending()
        assertEquals(Resolution.RESOLVED_RUNNING, h.resolutions.last())
        assertEquals(SessionState.RUNNING, h.sessions.sessions.getValue(s).state)
    }

    @Test
    fun claudeProcessVanishedIsStoppedAndFailed() {
        val h = harness()
        h.slots(1)
        val s = h.launch(MockScenario.SLOW_OUTPUT)
        h.pollAll()
        h.host.lose(s)
        h.crash()
        assertEquals(listOf(Resolution.RESOLVED_FAILED), h.resolutions)
        assertEquals(SessionState.FAILED, h.sessions.sessions.getValue(s).state)
        assertEquals(RunState.EXITED, h.host.state(s), "осиротевший процесс остановлен")
        assertTrue(h.accounts.slots.values.none { s in it.sessions })
    }

    @Test
    fun runUnknownToTargetIsConfirmedAbsent() {
        val h = harness()
        h.slots(1)
        val s = h.launch(MockScenario.SLOW_OUTPUT)
        // Другой target под тем же адресом: запуска там нет.
        val stranger = com.dpsmpx.dpai.execution.agent.MockAgentHost(h.clock, com.dpsmpx.dpai.core.ids.IdGenerator(h.clock, RandomSource.seeded(5)))
        val r = Reconciler(com.dpsmpx.dpai.sessions.Sessions(), h.clock)
        val pending = com.dpsmpx.dpai.sessions.Sessions.applyAll(h.sessions, r.markPending(h.sessions))
        val res = r.reconcile(pending, s, com.dpsmpx.dpai.execution.agent.AgentClient(stranger, listOf("dpai"), null, "t"))
        assertEquals(Resolution.RESOLVED_FAILED, res.resolution)
        assertEquals(DiagCode.STATE_CONFLICT, res.diagnostics.single().code)
    }

    @Test
    fun orphansAreRepairedWhereSafeAndReportedOtherwise() {
        val h = harness()
        h.slots(2)
        val done = h.launch(MockScenario.SUCCESS)
        h.pollAll()
        assertEquals(SessionState.COMPLETED, h.sessions.sessions.getValue(done).state)
        // Аренда конечной сессии, «забытая» до сбоя, дерево без живого владельца, задача RUNNING без сессии.
        val accounts = com.dpsmpx.dpai.accounts.Accounts.applyAll(
            h.accounts,
            listOf(com.dpsmpx.dpai.accounts.AccountEvent.SessionLeased(h.accounts.slots.keys.first(), done, h.sessions.sessions.getValue(done).task, h.clock.now())),
        )
        val repo = Repository(h.ids.next(IdKinds.REPOSITORY), h.ids.next(IdKinds.TARGET), "/r")
        var wt = Worktrees.applyAll(WorktreesState.EMPTY, Worktrees.register(WorktreesState.EMPTY, repo, h.clock.now()).valueOrNull()!!)
        val tree = h.ids.next(IdKinds.WORKTREE)
        wt = Worktrees.applyAll(wt, Worktrees.create(wt, tree, repo.id, "/w", "b", h.clock.now()).valueOrNull()!!)
        wt = Worktrees.applyAll(wt, Worktrees.claim(wt, tree, done, h.clock.now()).valueOrNull()!!)
        val tasks = Tasks()
        var ts = TasksState.EMPTY
        val task = h.ids.next(IdKinds.TASK)
        for (out in listOf(
            tasks.create(ts, task, "т", "ц", Priority.NORMAL, h.clock.now()),
        )) ts = Tasks.applyAll(ts, out.valueOrNull()!!)
        ts = Tasks.applyAll(ts, tasks.enqueue(ts, task, h.clock.now()).valueOrNull()!!)
        ts = Tasks.applyAll(ts, tasks.promote(ts, h.clock.now()))
        ts = Tasks.applyAll(ts, tasks.start(ts, task, h.clock.now()).valueOrNull()!!)
        val strayRun = h.ids.next(IdKinds.SESSION)
        val report = OrphanScan(com.dpsmpx.dpai.accounts.Accounts(), tasks).scan(h.sessions, accounts, ts, wt, setOf(strayRun), h.clock.now())
        assertEquals(1, report.accountFixes.size)
        assertEquals(1, report.worktreeFixes.size)
        assertEquals(TaskState.READY, Tasks.applyAll(ts, report.taskFixes).tasks.getValue(task).state)
        assertEquals(1, report.questions.size, "чужой процесс — вопрос пользователю, а не самовольная остановка")
        assertEquals(DiagCode.ORPHAN_FOUND, report.questions.single().code)
    }

    // ---- хаос (ТЗ, §95) ----

    private enum class Op { ADVANCE, POLL, CRASH, CRASH_MID_COMMIT, TARGET_DOWN, TARGET_UP, LOSE, LAUNCH }

    /**
     * Свойство: при любой последовательности сбоев — убийство приложения
     * (в том числе посреди записи), пропадание и возвращение target,
     * исчезновение процесса Claude — после возвращения target и сверки
     * каждая сессия в конечном или приостановленном состоянии, журнал без
     * пропусков, состояние из журнала совпадает с памятью, аренды — ровно у
     * живых сессий, а ход, который не теряли, завершён успешно.
     */
    @Test
    fun chaos() {
        val ops = Gen.list(Gen.element(Op.entries), 5..60)
        Property.forAll(ops, iterations = minOf(Property.iterations, 60)) { plan ->
            val h = harness(seed = plan.hashCode().toLong())
            h.slots(8)
            val lost = HashSet<SessionId>()
            val all = mutableListOf<SessionId>()
            val pick = RandomSource.seeded(plan.size.toLong())
            fun anyRunning(): SessionId? = all.filter { !h.sessions.sessions.getValue(it).terminal && h.host.hasPending(it) }.let { if (it.isEmpty()) null else it[pick.nextInt(it.size)] }
            h.step { all += h.launch(MockScenario.SLOW_OUTPUT) }
            for (op in plan) {
                h.clock.advance(1.seconds)
                when (op) {
                    Op.ADVANCE -> anyRunning()?.let { h.host.advance(it) }
                    Op.POLL -> h.step { h.pollAll() }
                    Op.CRASH -> h.crash()
                    Op.CRASH_MID_COMMIT -> {
                        h.crashAfterEvents = pick.nextInt(3)
                        h.step { h.pollAll() }
                        h.crashAfterEvents = null
                    }
                    Op.TARGET_DOWN -> h.host.reachable = false
                    Op.TARGET_UP -> { h.host.reachable = true; h.step { h.reconcilePending() } }
                    Op.LOSE -> anyRunning()?.let { h.host.lose(it); lost += it }
                    Op.LAUNCH -> if (h.accounts.slots.values.any { it.sessions.isEmpty() } && h.host.reachable) h.step { all += h.launch(MockScenario.SLOW_OUTPUT) }
                }
            }
            // Конец: target вернулся, ходы на target доиграли, оркестратор перезапущен и сверился.
            h.host.reachable = true
            all.forEach { s -> while (h.host.advance(s)) continue }
            h.crash()
            h.pollAll()
            for (s in all) {
                val st = h.sessions.sessions.getValue(s).state
                assertTrue(st == SessionState.COMPLETED || st == SessionState.FAILED, "$s: $st, план $plan")
                if (s !in lost) assertEquals(SessionState.COMPLETED, st, "$s не теряли, план $plan")
            }
            assertEquals(h.sessions, StateRecovery(SessionsProjection).replayAll(h.journal), "журнал = память")
            for ((_, recs) in h.journal.records().groupBy { it.event.stream }) {
                assertEquals((1L..recs.size).toList(), recs.map { it.event.sequence }, "поток без пропусков")
            }
            val leased = h.accounts.slots.values.flatMap { it.sessions.keys }.toSet()
            assertEquals(h.sessions.sessions.values.filter { !it.terminal }.map { it.id }.toSet(), leased, "аренды — ровно у живых")
        }
    }
}
