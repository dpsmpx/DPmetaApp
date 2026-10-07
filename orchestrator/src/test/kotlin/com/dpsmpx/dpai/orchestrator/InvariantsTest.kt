package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.git.Worktree
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Инварианты ТЗ, §118: каждое нарушение называется по имени; чистое состояние — без находок. */
class InvariantsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(118))
    private val task = ids.next(IdKinds.TASK)
    private val tasks: TasksState = Tasks.applyAll(TasksState.EMPTY, Tasks().create(TasksState.EMPTY, task, "т", "ц", Priority.NORMAL, clock.now()).valueOrNull()!!)

    private fun input(
        tasks: TasksState = this.tasks,
        worktrees: WorktreesState = WorktreesState.EMPTY,
        events: List<EventEnvelope> = emptyList(),
        merged: List<com.dpsmpx.dpai.core.ids.TaskId> = emptyList(),
        diagnostics: List<Diagnostic> = emptyList(),
    ) = InvariantInput(AccountsState.EMPTY, SessionsState.EMPTY, tasks, worktrees, emptySet(), events, merged, emptyMap(), diagnostics)

    private fun names(i: InvariantInput) = Invariants.check(i).map {
        assertEquals(DiagCode.INVARIANT_VIOLATED, it.code)
        it.message.substringBefore(":")
    }

    private fun event(stream: StreamRef, n: Long) = EventEnvelope(ids.next(IdKinds.EVENT), EventType.TASK_CREATED, stream, n, clock.now(), clock.now())

    @Test
    fun cleanStateHasNoViolations() {
        val s = StreamRef.of(task)
        assertEquals(emptyList(), Invariants.check(input(events = listOf(event(s, 1), event(s, 2)))))
    }

    @Test
    fun eachViolationIsNamed() {
        val s = StreamRef.of(task)
        val dup = event(s, 1)
        assertEquals(listOf("event-stable-id", "event-sequence"), names(input(events = listOf(dup, dup.copy(sequence = 2), event(s, 4)))))
        assertEquals(listOf("destructive-approved"), names(input(merged = listOf(task))))
        val done = TasksState(mapOf(task to tasks.tasks.getValue(task).copy(state = TaskState.DONE)))
        assertEquals(listOf("done-has-verification"), names(input(tasks = done)))
        val owner = ids.next(IdKinds.SESSION)
        val tree = Worktree(ids.next(IdKinds.WORKTREE), ids.next(IdKinds.REPOSITORY), "/t/1", "task/1", owner)
        val twin = Worktree(ids.next(IdKinds.WORKTREE), tree.repository, "/t/1", "task/2")
        assertEquals(listOf("worktree-owner", "worktree-exclusive"), names(input(worktrees = WorktreesState(worktrees = mapOf(tree.id to tree, twin.id to twin)))))
        // «Нет секретов в диагностике» держится по построению: конструктор Diagnostic закрыт,
        // фабрика пропускает текст через Redactor — проверка подтверждает, а не ловит.
        val leaked = Diagnostic.error(DiagCode.INTERNAL_ERROR, "ключ sk-" + "ant-api03-" + "Ab1".repeat(14))
        assertTrue("<redacted>" in leaked.message, leaked.message)
        assertEquals(emptyList(), names(input(diagnostics = listOf(leaked))))
    }
}
