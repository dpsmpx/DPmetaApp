package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.MockAgentHost
import com.dpsmpx.dpai.execution.claude.MockRunner
import com.dpsmpx.dpai.execution.claude.MockScenario
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.orchestrator.Orchestrator
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Восстановление контроллера (ревью 8 октября 2026, A-42, A-43, A-44,
 * A-76): процесс приложения умирает посреди шага, target недоступен при
 * старте, ход теряется на target во время работы. Ни одна сессия не
 * держит аренду и место target вечно, задача доходит до итога.
 */
class LiveRecoveryTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(81))
    private val host = MockAgentHost(clock, ids)

    /** Смерть процесса приложения: `Error`, а не исключение, — её не ловит ни один обработчик контура. */
    private class Killed : Error()

    /** Сценарий хода «Claude»; ход идёт по шагам, если [slow]. */
    private var scenario = MockScenario.SUCCESS
    private var slow = false

    /** Когда «убить» приложение: до вызова агента `start` хода, после него или ни разу. */
    private enum class Crash { NONE, BEFORE_TURN_START, AFTER_TURN_START }

    private var crash = Crash.NONE

    private val target = TargetForm.validate(ids.next(IdKinds.TARGET), "сервер", "build.lan", "22", "dev", false, "/opt/dpai/bin/dpai", "/home/dev/.claude-dpai").valueOrNull()!!

    init {
        host.onStart = { run, spec, _ ->
            if (spec.command.take(3) == listOf("claude", "auth", "status")) {
                host.output(run, """{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","subscriptionType":"pro"}""")
                host.exit(run, 0)
            } else {
                val c = spec.command
                val sid = c[c.indexOfFirst { it == "--session-id" || it == "--resume" } + 1]
                val steps = MockRunner.script(scenario, sid).map { l -> { host.output(run, l); Unit } } +
                    listOf({ host.exit(run, if (scenario == MockScenario.SUCCESS) 0 else 1, ExitReason.EXITED) })
                if (slow) host.schedule(run, steps) else steps.forEach { it() }
            }
        }
    }

    /** Хост target: «убивает» приложение у вызова агента `start` хода Claude. */
    private val crashingHost = object : ProcessHost {
        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit) = run {
            val turn = "start" in spec.command && spec.stdin?.contains("\"--session-id\"") == true
            if (turn && crash == Crash.BEFORE_TURN_START) {
                crash = Crash.NONE
                throw Killed()
            }
            val r = host.start(spec, onOutput)
            if (turn && crash == Crash.AFTER_TURN_START) {
                crash = Crash.NONE
                throw Killed()
            }
            r
        }
    }

    private fun system(onEvents: (List<EventEnvelope>) -> Unit = {}) =
        LiveSystem(clock, NioVfs(dir), listOf(target), { _, _ -> crashingHost }, "/known_hosts", RandomSource.seeded(82), onEvents = onEvents)

    /** Шаги контура; «смерть» приложения — `false`. */
    private fun steps(s: LiveSystem, n: Int): Boolean {
        repeat(n) {
            try {
                s.step()
            } catch (_: Killed) {
                return false
            }
            assertEquals(emptyList(), s.orchestrator.invariants(), "инварианты ТЗ, §118")
            clock.advance(10.seconds)
        }
        return true
    }

    private fun done(s: LiveSystem, task: com.dpsmpx.dpai.core.ids.TaskId) {
        repeat(20) {
            if (s.orchestrator.tasks.tasks.getValue(task).state == TaskState.COMPLETION_UNVERIFIED) return
            steps(s, 1)
        }
        fail("задача не дошла до итога: ${s.orchestrator.tasks.tasks.getValue(task).state}; ${s.problems + s.orchestrator.problems}")
    }

    /** A-42: приложение умерло после записи сессии, до вызова агента — хода не было; попытка не считается, задача идёт заново. */
    @Test
    fun queuedSessionWithoutTurnIsCancelledAndTaskRuns() {
        val first = system()
        steps(first, 1)
        val task = first.addTask("задача", "цель").valueOrNull()!!
        crash = Crash.BEFORE_TURN_START
        assertTrue(!steps(first, 3), "приложение «умерло»")
        val queued = first.orchestrator.sessions.sessions.values.single()
        assertEquals(SessionState.QUEUED, queued.state)

        val second = system()
        val after = second.orchestrator.sessions.sessions.getValue(queued.id)
        assertEquals(SessionState.CANCELLED, after.state)
        assertEquals(DiagCode.RECOVERY_START_UNCONFIRMED, after.lastReason)
        assertTrue(second.orchestrator.accounts.slots.values.none { queued.id in it.sessions }, "аренда вернулась")
        done(second, task)
        assertEquals(0, second.orchestrator.tasks.tasks.getValue(task).retries, "попытка без хода не считается")
    }

    /** A-42: запуск дошёл до агента, а запись о нём — нет: ход подхватывается, задача в работе и доходит до итога. */
    @Test
    fun queuedSessionWhoseTurnReachedTargetIsAdopted() {
        slow = true
        val first = system()
        steps(first, 1)
        val task = first.addTask("задача", "цель").valueOrNull()!!
        crash = Crash.AFTER_TURN_START
        assertTrue(!steps(first, 3))
        val queued = first.orchestrator.sessions.sessions.values.single()
        assertEquals(SessionState.QUEUED, queued.state)
        assertTrue(host.hasPending(queued.id), "ход идёт на target")

        val second = system()
        assertEquals(SessionState.RUNNING, second.orchestrator.sessions.sessions.getValue(queued.id).state)
        assertEquals(TaskState.RUNNING, second.orchestrator.tasks.tasks.getValue(task).state)
        do { val more = host.advance(queued.id) } while (more)
        done(second, task)
        assertEquals(1, second.orchestrator.sessions.sessions.size, "второго хода нет")
    }

    /** A-42: приложение умерло между итогом хода `PAUSED` и handoff — после перезапуска handoff сделан, задача продолжается. */
    @Test
    fun pausedSessionWithoutHandoffIsHandedOffOnRestart() {
        scenario = MockScenario.PARTIAL_COMPLETION
        var armed = true
        val first = system { envs ->
            val paused = envs.any { it.type == EventType.SESSION_STATE_CHANGED && it.payload["to"] == JsonString(SessionState.PAUSED.name) }
            if (armed && paused) {
                armed = false
                throw Killed()
            }
        }
        steps(first, 1)
        val task = first.addTask("задача", "цель").valueOrNull()!!
        assertTrue(!steps(first, 5), "приложение «умерло» на PAUSED")
        val paused = first.orchestrator.sessions.sessions.values.single()
        assertEquals(SessionState.PAUSED, paused.state)

        scenario = MockScenario.SUCCESS
        val second = system()
        assertEquals(SessionState.CANCELLED, second.orchestrator.sessions.sessions.getValue(paused.id).state)
        assertEquals(DiagCode.SESSION_HANDED_OFF, second.orchestrator.sessions.sessions.getValue(paused.id).lastReason)
        done(second, task)
    }

    /** A-44, A-76: target не ответил при старте — сессия ждёт сверки, а когда target вернулся, сверяется в такте. */
    @Test
    fun targetDownAtStartIsReconciledWhenItReturns() {
        slow = true
        val first = system()
        steps(first, 1)
        val task = first.addTask("задача", "цель").valueOrNull()!!
        steps(first, 2)
        val session = first.orchestrator.sessions.sessions.values.single().id
        host.reachable = false
        val second = system()
        assertEquals(SessionState.RECOVERY_PENDING, second.orchestrator.sessions.sessions.getValue(session).state)
        steps(second, 2)
        assertEquals(SessionState.RECOVERY_PENDING, second.orchestrator.sessions.sessions.getValue(session).state, "неизвестно — не «упала»")
        host.reachable = true
        do { val more = host.advance(session) } while (more)
        clock.advance(Orchestrator.RECONCILE_EVERY)
        done(second, task)
    }

    /** A-43: супервизор хода умер на target во время работы — сессия уходит на сверку, процесс останавливается, сессия не висит RUNNING. */
    @Test
    fun runLostWhileWorkingIsReconciled() {
        slow = true
        val s = system()
        steps(s, 1)
        val task = s.addTask("задача", "цель").valueOrNull()!!
        steps(s, 2)
        val session = s.orchestrator.sessions.sessions.values.single().id
        assertEquals(SessionState.RUNNING, s.orchestrator.sessions.sessions.getValue(session).state)
        host.lose(session)
        steps(s, 1)
        assertEquals(SessionState.RECOVERY_PENDING, s.orchestrator.sessions.sessions.getValue(session).state)
        assertEquals(DiagCode.AGENT_RUN_LOST, s.orchestrator.sessions.sessions.getValue(session).lastReason)
        clock.advance(Orchestrator.RECONCILE_EVERY)
        steps(s, 1)
        assertEquals(SessionState.FAILED, s.orchestrator.sessions.sessions.getValue(session).state)
        assertTrue(s.orchestrator.accounts.slots.values.none { session in it.sessions }, "аренда вернулась")
        assertTrue(s.orchestrator.tasks.tasks.getValue(task).state != TaskState.RUNNING, "задача не висит в работе без хода")
    }
}
