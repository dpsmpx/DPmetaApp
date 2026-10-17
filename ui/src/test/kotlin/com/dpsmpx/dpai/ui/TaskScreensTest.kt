package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Действия задачи по состоянию (D-42): экран не предлагает того, что оркестратор отклонит. */
class TaskScreensTest {
    private val clock = ManualClock()
    private val id = IdGenerator(clock, RandomSource.seeded(3)).next(IdKinds.TASK)
    private fun task(state: TaskState, held: Boolean = false) =
        TaskRecord(id, "задача", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = state, held = held)

    @Test
    fun actionsFollowState() {
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.HOLD, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.RUNNING)), "идущей — без правки политики")
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.POLICY, TaskAction.SCOPE, TaskAction.RESUME, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.READY, held = true)))
        assertTrue(TaskAction.REPLY in TaskScreens.actions(task(TaskState.BLOCKED)) && TaskAction.RETRY in TaskScreens.actions(task(TaskState.BLOCKED)))
        assertEquals(listOf(TaskAction.PRIORITY, TaskAction.VERIFY, TaskAction.WAIVE, TaskAction.CANCEL), TaskScreens.actions(task(TaskState.COMPLETION_UNVERIFIED)))
        assertEquals(listOf(TaskAction.POLICY, TaskAction.RETRY, TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.FAILED)), "завершённой — без области")
        assertEquals(listOf(TaskAction.ARCHIVE), TaskScreens.actions(task(TaskState.DONE)))
        assertTrue(TaskAction.CANCEL.dangerous && TaskAction.WAIVE.dangerous && !TaskAction.HOLD.dangerous)
        assertEquals(id, TaskScreens.detail(task(TaskState.READY), SessionsState.EMPTY).id)
    }

    /** D-42, п. 1: лист задачи показывает область и зависимости; выбор зависимостей — без завершённых и самой задачи. */
    @Test
    fun scopeAndDependenciesInDetail() {
        val gen = IdGenerator(clock, RandomSource.seeded(4))
        val dep = TaskRecord(gen.next(IdKinds.TASK), "база", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.QUEUED)
        val done = TaskRecord(gen.next(IdKinds.TASK), "готовая", "цель", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now(), state = TaskState.DONE)
        val t = task(TaskState.READY).copy(dependencies = setOf(dep.id), scope = setOf("src/a"))
        val all = listOf(t, dep, done).associateBy { it.id }
        val d = TaskScreens.detail(t, SessionsState.EMPTY, tasks = all)
        assertEquals(setOf("src/a"), d.scope)
        assertEquals(listOf(DependencyLine(dep.id, "база", "QUEUED")), d.dependencies)
        assertEquals(listOf(dep.id), TaskScreens.dependencyChoices(all, except = t.id).map { it.id })
    }
}
