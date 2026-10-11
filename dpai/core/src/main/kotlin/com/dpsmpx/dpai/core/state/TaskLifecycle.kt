package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.state.TaskState.*

/**
 * Жизненный цикл задачи (`docs/SCHEDULER.md`, §1; ТЗ, §32, §41). В `DONE` —
 * только из `VERIFIED` или из `COMPLETION_UNVERIFIED` с исключением политики
 * (D-23).
 */
enum class TaskState {
    BACKLOG, QUEUED, READY, RUNNING, WAITING, BLOCKED, IMPLEMENTED, VERIFYING, VERIFIED,
    COMPLETION_UNVERIFIED, DONE, FAILED, CANCELLED,
}

object TaskLifecycle {
    val table: TransitionTable<TaskState> =
        TransitionTable(
            name = "задача",
            states = TaskState.entries,
            initial = BACKLOG,
            terminal = setOf(DONE, FAILED, CANCELLED),
            ordinary = mapOf(
                BACKLOG to setOf(QUEUED, CANCELLED),
                QUEUED to setOf(READY, BLOCKED, BACKLOG, CANCELLED),
                READY to setOf(RUNNING, QUEUED, BLOCKED, CANCELLED),
                RUNNING to setOf(WAITING, BLOCKED, IMPLEMENTED, READY, FAILED, CANCELLED),
                WAITING to setOf(RUNNING, BLOCKED, READY, FAILED, CANCELLED),
                BLOCKED to setOf(READY, QUEUED, FAILED, CANCELLED),
                IMPLEMENTED to setOf(VERIFYING, COMPLETION_UNVERIFIED, CANCELLED),
                VERIFYING to setOf(VERIFIED, READY, COMPLETION_UNVERIFIED, FAILED, CANCELLED),
                VERIFIED to setOf(DONE, READY),
                COMPLETION_UNVERIFIED to setOf(VERIFYING, DONE, CANCELLED),
            ),
            explicit = mapOf(
                FAILED to setOf(QUEUED),
                CANCELLED to setOf(BACKLOG),
                DONE to setOf(BACKLOG),
            ),
        )
}
