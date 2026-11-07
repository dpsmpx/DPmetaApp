package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.monitoring.DashboardView
import com.dpsmpx.dpai.monitoring.SelfDiagnostics
import com.dpsmpx.dpai.monitoring.SystemSignals
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

class ScreensTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()

    @Test
    fun unknownCountIsQuestionMarkNotZero() {
        val view = DashboardView(0, 0, 0, 0, Measure.Unknown("нет связи"), 0, 0, emptyList(), emptyList())
        val health = SelfDiagnostics.evaluate(SystemSignals(true, false, true, true, 1, 1, false))
        val screen = Screens.dashboard(view, health)
        assertEquals("?", screen.counters.first { it.label == "Запросы ждут ответа" }.value)
        assertEquals(Tone.DONE, screen.healthTone)
    }

    @Test
    fun summaryHidesZeroCountersButKeepsUnknown() {
        val view = DashboardView(2, 0, 0, 0, Measure.Unknown("нет связи"), 0, 0, emptyList(), emptyList())
        val health = SelfDiagnostics.evaluate(SystemSignals(true, false, true, true, 1, 1, false))
        val screen = Screens.dashboard(view, health)
        // «0» скрыт (AI:A-6), неизвестное — «?», а не ноль (D-13); активные — и числом для пульта (D-76).
        assertEquals(listOf("Активных сессий" to "2", "Запросы ждут ответа" to "?"), screen.shownCounters.map { it.label to it.value })
        assertEquals(2, screen.active)
        assertEquals(emptyList(), screen.usage)
    }

    @Test
    fun boardPlacesDependentTasksDeeperInDag() {
        val ids = IdGenerator(clock, RandomSource.seeded(3))
        val tasks = Tasks()
        var state = TasksState.EMPTY
        fun add(title: String, deps: Set<com.dpsmpx.dpai.core.ids.TaskId> = emptySet()) = ids.next(IdKinds.TASK).also { id ->
            state = Tasks.applyAll(state, tasks.create(state, id, title, "цель", Priority.NORMAL, clock.now(), deps).valueOrNull()!!)
        }
        val a = add("a")
        val b = add("b", setOf(a))
        add("c", setOf(a, b))
        val cards = Screens.board(state).columns.flatMap { it.cards }.associateBy { it.title }
        assertEquals(0, cards.getValue("a").level)
        assertEquals(1, cards.getValue("b").level)
        assertEquals(2, cards.getValue("c").level)
        assertEquals(listOf("a", "b"), cards.getValue("c").dependsOn)
    }

    /** 20+ сессий на демо-системе: сводка, экран сессии и доска строятся (критерий этапа 11). */
    @Test
    fun demoSystemShowsTwentyPlusSessions() {
        val demo = DemoSystem(clock, NioVfs(dir), sessions = 25)
        repeat(6) {
            demo.step()
            clock.advance(1.seconds)
        }
        val screen = demo.dashboard()
        assertTrue(screen.sessions.size >= 20, "сессий: ${screen.sessions.size}")
        val first = screen.sessions.first().session
        val session = demo.session(first)
        assertEquals(first, session.line?.session)
        assertTrue(session.timeline.isNotEmpty())
        assertTrue(demo.board().columns.sumOf { it.cards.size } >= 25)
    }

    @Test
    fun demoIsDeterministicForSameSeed() {
        fun run(sub: String): List<String> {
            val c = ManualClock()
            val demo = DemoSystem(c, NioVfs(dir.resolve(sub).also { it.toFile().mkdirs() }), sessions = 10)
            repeat(10) {
                demo.step()
                c.advance(1.seconds)
            }
            return demo.dashboard().sessions.map { "${it.session.value} ${it.state}" }
        }
        assertEquals(run("x"), run("y"))
    }

    /** Ревью A-87: демо ставит задачи до потолка — пока экран открыт, журнал и память не растут без предела. */
    @Test
    fun demoStopsAddingTasksAtTheCap() {
        val clock = ManualClock()
        val demo = DemoSystem(clock, NioVfs(dir), sessions = 5, maxTasks = 12)
        repeat(400) {
            demo.step()
            clock.advance(1.seconds)
        }
        assertTrue(demo.orchestrator.tasks.tasks.size <= 12, "${demo.orchestrator.tasks.tasks.size}")
    }
}
