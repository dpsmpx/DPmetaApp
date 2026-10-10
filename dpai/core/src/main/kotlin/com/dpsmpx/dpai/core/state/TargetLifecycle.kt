package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.state.TargetState.*

/**
 * Доступность execution target по пробам здоровья (`docs/EXECUTION_MODEL.md`,
 * §1; ТЗ, §14). `ONLINE` → `OFFLINE` напрямую недопустимо: один пропуск
 * пробы даёт только `DEGRADED`, в `OFFLINE` target уходит после нескольких
 * порогов (`TargetHealthPolicy` модуля `execution`).
 */
enum class TargetState { UNKNOWN, ONLINE, DEGRADED, OFFLINE, DISABLED }

object TargetLifecycle {
    val table: TransitionTable<TargetState> =
        TransitionTable(
            name = "target",
            states = TargetState.entries,
            initial = UNKNOWN,
            terminal = emptySet(),
            ordinary = mapOf(
                UNKNOWN to setOf(ONLINE, DEGRADED, OFFLINE, DISABLED),
                ONLINE to setOf(DEGRADED, DISABLED),
                DEGRADED to setOf(ONLINE, OFFLINE, DISABLED),
                OFFLINE to setOf(ONLINE, DEGRADED, DISABLED),
            ),
            explicit = mapOf(DISABLED to setOf(UNKNOWN)),
        )
}
