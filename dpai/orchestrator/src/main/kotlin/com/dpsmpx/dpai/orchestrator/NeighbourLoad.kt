package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.ids.ExecutionTargetId

/**
 * Нагрузка соседних контуров на общие target (D-179; `DPmetaApp:D-147`): у каждого
 * рабочего проекта свой [Orchestrator], а места на target и общий предел сессий —
 * одни на всех. Контур считает свои незавершённые сессии плюс эти. Такты контуров
 * идут по очереди под одним замком оболочки — подсчёт и запуск не расходятся.
 */
interface NeighbourLoad {
    /** Незавершённые сессии других контуров на [target]. */
    fun sessions(target: ExecutionTargetId): Int

    /** Незавершённые сессии других контуров всего. */
    fun total(): Int

    companion object {
        /** Соседей нет — один проект, как до D-179. */
        val NONE: NeighbourLoad = object : NeighbourLoad {
            override fun sessions(target: ExecutionTargetId): Int = 0
            override fun total(): Int = 0
        }
    }
}
