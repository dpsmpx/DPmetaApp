package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.sessions.UsageTotals
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.TasksState
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Работа студии по проекту для пульта (DPmetaApp:D-176): числа контура, неизвестное — не ноль. */
class ProjectWorkTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(176))
    private val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Linux", "/root/.claude-slot-1").valueOrNull()!!
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/projects/alice/app", "/root/projects/alice/.trees/app", "main", emptyList())
    private val now = Timestamp(10L * 24 * 60 * 60 * 1000)

    private fun task(state: TaskState, archived: Boolean = false) =
        TaskRecord(ids.next(IdKinds.TASK), "задача", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = state, archived = archived)

    private fun session(state: SessionState, usage: UsageTotals) = SessionRecord(
        ids.next(IdKinds.SESSION), "k-" + ids.next(IdKinds.OPERATION).value, ids.next(IdKinds.TASK), ids.next(IdKinds.ACCOUNT), ids.next(IdKinds.SUBSCRIPTION),
        target.id, "/c", ProviderSessionId.newUuid(RandomSource.seeded(1)), null, state, clock.now(), clock.now(), usage = usage,
    )

    @Test
    fun countsOpenTasksRunningSessionsTurnsAndCost() {
        val tasks = TasksState(listOf(task(TaskState.RUNNING), task(TaskState.READY), task(TaskState.DONE), task(TaskState.DONE, archived = true)).associateBy { it.id })
        val sessions = SessionsState(
            listOf(
                session(SessionState.RUNNING, UsageTotals(turns = 2, costUsd = BigDecimal("0.50"))),
                session(SessionState.COMPLETED, UsageTotals(turns = 3, costUsd = BigDecimal("1.25"), costEstimated = true)),
            ).associateBy { it.id },
        )
        val manual = ProjectWork.manual(project, null, Outcome.success(emptyList()), now)
        val w = ProjectWork.of(project, tasks, sessions, 1, manual)
        assertEquals(ProjectWork(project.project.value, "/root/projects/alice/app", 2, 1, 1, 5, BigDecimal("1.75"), true, ManualCounts(null, 0, 0)), w)
    }

    @Test
    fun withoutContourAiNumbersAreUnknown() {
        val w = ProjectWork.of(project, null, null, null, null)
        assertNull(w.tasksOpen)
        assertNull(w.turns)
        assertNull(w.costUsd)
        assertNull(w.manual)
    }
}
