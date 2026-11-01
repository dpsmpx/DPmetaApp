package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TargetState
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.target.CheckStatus
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.execution.target.TargetHealthPolicy
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Единый интерфейс к target (критерии этапа 3) на `MOCK`: запуск, события,
 * полная остановка; проба здоровья с порогами; `doctor`.
 */
class ExecutionTargetManagerTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(3))
    private val mock = MockAgentHost(clock, ids)
    private val policy = TargetHealthPolicy(offlineAfterFailures = 3, offlineAfter = 60.seconds, openPause = 30.seconds)
    private val manager = ExecutionTargetManager(clock, policy, mockHost = mock)
    private val target = ExecutionTarget(ids.next(IdKinds.TARGET), TargetType.MOCK, "mock", TargetPlatform.POSIX, null, listOf("dpai"))

    init {
        manager.register(target)
    }

    /**
     * Заглушка держит события не больше чем у KEEP_FINISHED завершённых
     * запусков (этап 19): у давнего остаются номера — новый ход продолжает
     * нумерацию, как у настоящего агента после уборки.
     */
    @Test
    fun mockCompactsOldFinishedRunsButKeepsNumbering() {
        val client = manager.client(target.id).valueOrNull()!!
        val live = ids.next(IdKinds.SESSION)
        client.start(live, RunSpec(listOf("claude", "-p", "живой")))
        val runs = List(MockAgentHost.KEEP_FINISHED + 10) { ids.next(IdKinds.SESSION) }
        for (r in runs) {
            client.start(r, RunSpec(listOf("claude", "-p", "x")))
            mock.exit(r, 0)
        }
        val old = runs.first()
        val before = client.status(old).valueOrNull()!!
        assertEquals(RunState.EXITED, before.state)
        assertEquals(emptyList(), client.events(old, 0, 100).valueOrNull()!!.events, "события давнего сжаты")
        assertEquals(RunState.RUNNING, mock.state(live), "идущий не трогается")
        // Новый ход давнего запуска: номер хода и событий продолжаются.
        val again = client.start(old, RunSpec(listOf("claude", "-p", "ещё"))).valueOrNull()!!
        assertEquals(before.turn + 1, again.turn)
        assertEquals(before.lastSequence + 1, again.lastSequence)
    }

    @Test
    fun processStartsStreamsAndStopsThroughUnifiedInterface() {
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        assertEquals(RunState.RUNNING, client.start(run, RunSpec(listOf("claude", "-p", "x"))).valueOrNull()!!.state)
        mock.emit(run, EventType.TARGET_PROCESS_OUTPUT)
        val channel = RunChannel(client, run)
        assertEquals(listOf(EventType.TARGET_PROCESS_STARTED, EventType.TARGET_PROCESS_OUTPUT), channel.poll().valueOrNull()!!.events.map { it.type })
        val stopped = client.stop(run).valueOrNull()!!
        assertEquals(RunState.EXITED, stopped.run!!.state)
        assertEquals(ExitReason.STOPPED, stopped.run.reason)
        assertEquals(EventType.TARGET_PROCESS_EXITED, channel.poll().valueOrNull()!!.events.single().type)
    }

    @Test
    fun unreachableTargetGoesOfflineOnlyAfterThresholds() {
        assertEquals(TargetState.ONLINE, manager.probe(target.id).valueOrNull()!!.health.state)
        mock.reachable = false
        clock.advance(10.seconds)
        val first = manager.probe(target.id).valueOrNull()!!
        assertEquals(TargetState.DEGRADED, first.health.state)
        clock.advance(40.seconds)
        manager.probe(target.id)
        clock.advance(40.seconds)
        val third = manager.probe(target.id).valueOrNull()!!
        assertEquals(TargetState.OFFLINE, third.health.state)
        // Предохранитель открыт: до паузы проба не идёт к target.
        val callsBefore = mock.calls
        clock.advance(5.seconds)
        manager.probe(target.id)
        assertEquals(callsBefore, mock.calls)
    }

    @Test
    fun doctorReportsChecks() {
        val r = manager.doctor(target.id).valueOrNull()!!
        assertEquals(CheckStatus.OK, r.overall, r.checks.toString())
        mock.reachable = false
        val down = manager.doctor(target.id).valueOrNull()!!
        assertEquals(CheckStatus.FAIL, down.overall)
    }

    /** Агент другой версии — WARN: протокол строгий, расхождение видно до отказа. */
    @Test
    fun agentVersionDifferentFromControllerIsWarning() {
        val same = ExecutionTargetManager(clock, policy, mockHost = mock, controllerVersion = "mock").also { it.register(target) }
        assertEquals(CheckStatus.OK, same.doctor(target.id).valueOrNull()!!.checks.first { it.name == "агент" }.status)
        val newer = ExecutionTargetManager(clock, policy, mockHost = mock, controllerVersion = "0.2.0").also { it.register(target) }
        val agent = newer.doctor(target.id).valueOrNull()!!.checks.first { it.name == "агент" }
        assertEquals(CheckStatus.WARN, agent.status)
        assertTrue("0.2.0" in agent.detail)
    }

    @Test
    fun disabledTargetIsNotProbed() {
        manager.setEnabled(target.id, false)
        val r = manager.probe(target.id)
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.TARGET_DISABLED, r.errors.single().code)
        assertEquals(TargetState.UNKNOWN, manager.setEnabled(target.id, true).valueOrNull()!!.health.state)
    }

    /** Target «в приложении» (D-75): без SSH, через канал к агенту в процессе приложения; без канала — не регистрируется. */
    @Test
    fun inAppTargetNeedsTheAppAgentChannel() {
        val inApp = ExecutionTarget(ids.next(IdKinds.TARGET), TargetType.IN_APP, "в приложении", TargetPlatform.POSIX, null, listOf("dpai"))
        val refused = manager.register(inApp)
        assertIs<Outcome.Failure>(refused)
        assertEquals(DiagCode.UNSUPPORTED, refused.errors.single().code)

        val app = ExecutionTargetManager(clock, policy, inAppHost = mock)
        assertTrue(app.register(inApp) is Outcome.Success)
        val run = ids.next(IdKinds.SESSION)
        assertEquals(RunState.RUNNING, app.client(inApp.id).valueOrNull()!!.start(run, RunSpec(listOf("claude", "-p", "x"))).valueOrNull()!!.state)
        assertEquals(RunState.RUNNING, mock.state(run), "вызов дошёл до канала в приложении")
    }
}
