package com.dpsmpx.dpai.core.state

import com.dpsmpx.dpai.core.state.CircuitState.*

/** Предохранитель внешнего ресурса (`docs/EXECUTION_MODEL.md`, §2; ТЗ, §81). */
enum class CircuitState { HEALTHY, FAILING, OPEN, HALF_OPEN, RECOVERED }

object CircuitLifecycle {
    val table: TransitionTable<CircuitState> =
        TransitionTable(
            name = "предохранитель",
            states = CircuitState.entries,
            initial = HEALTHY,
            terminal = emptySet(),
            ordinary = mapOf(
                HEALTHY to setOf(FAILING),
                FAILING to setOf(HEALTHY, OPEN),
                OPEN to setOf(HALF_OPEN),
                HALF_OPEN to setOf(RECOVERED, OPEN),
                RECOVERED to setOf(HEALTHY, FAILING),
            ),
        )
}
