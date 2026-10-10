package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.state.SessionState.*

/**
 * Жизненный цикл сессии (`docs/SESSION_MODEL.md`, §1; ТЗ, §8). Из конечных
 * состояний переходов нет: продолжение — новая сессия
 * (`docs/SESSION_MODEL.md`, §2).
 */
enum class SessionState {
    CREATED, QUEUED, STARTING, RUNNING, WAITING, PAUSED, BLOCKED, NEAR_LIMIT, VERIFYING,
    RECOVERY_PENDING, COMPLETED, FAILED, CANCELLED,
}

object SessionLifecycle {
    val table: TransitionTable<SessionState> =
        TransitionTable(
            name = "сессия",
            states = SessionState.entries,
            initial = CREATED,
            terminal = setOf(COMPLETED, FAILED, CANCELLED),
            ordinary = mapOf(
                CREATED to setOf(QUEUED, CANCELLED),
                QUEUED to setOf(STARTING, CANCELLED),
                STARTING to setOf(RUNNING, FAILED, CANCELLED, RECOVERY_PENDING),
                RUNNING to setOf(WAITING, PAUSED, BLOCKED, NEAR_LIMIT, VERIFYING, COMPLETED, FAILED, CANCELLED, RECOVERY_PENDING),
                WAITING to setOf(RUNNING, PAUSED, BLOCKED, NEAR_LIMIT, FAILED, CANCELLED, RECOVERY_PENDING),
                PAUSED to setOf(RUNNING, FAILED, CANCELLED, RECOVERY_PENDING),
                BLOCKED to setOf(RUNNING, PAUSED, FAILED, CANCELLED, RECOVERY_PENDING),
                NEAR_LIMIT to setOf(RUNNING, PAUSED, VERIFYING, COMPLETED, FAILED, CANCELLED, RECOVERY_PENDING),
                VERIFYING to setOf(RUNNING, COMPLETED, FAILED, CANCELLED, RECOVERY_PENDING),
                RECOVERY_PENDING to setOf(RUNNING, COMPLETED, FAILED, CANCELLED),
            ),
        )
}
