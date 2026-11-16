package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState
import java.math.BigDecimal

/**
 * Работа студии по рабочему проекту — для карточки и сводки пульта (DPmetaApp:D-176): задачи,
 * запросы человеку, идущие сессии, ходы ИИ и расход, ручные сборки и проверки за сутки. Только
 * числа: ни текстов задач, ни путей, кроме корня проекта, по которому пульт находит свой проект.
 * Контура проекта ещё нет (нет target с каталогом Claude) — числа ИИ `null`: неизвестно, а не ноль (D-13).
 */
data class ProjectWork(
    val project: String,
    val root: String,
    /** Незавершённые задачи вне архива. */
    val tasksOpen: Int?,
    /** Запросы человеку (D-44): одобрения и вопросы задач. */
    val requests: Int?,
    /** Идущие сессии ИИ. */
    val running: Int?,
    /** Ходы ИИ за всё время журнала проекта. */
    val turns: Int?,
    /** Расход ИИ по журналу проекта, USD; [costEstimated] — часть по оценке. */
    val costUsd: BigDecimal?,
    val costEstimated: Boolean,
    /** Ручные сборки и проверки за сутки (D-153); `null` — история не прочиталась. */
    val manual: ManualCounts?,
) {
    companion object {
        fun of(project: ProjectEntry, tasks: TasksState?, sessions: SessionsState?, requests: Int?, manual: ManualCounts?): ProjectWork {
            val all = sessions?.sessions?.values
            return ProjectWork(
                project = project.project.value,
                root = project.root,
                tasksOpen = tasks?.tasks?.values?.count { !it.terminal && !it.archived },
                requests = requests,
                running = all?.count { !it.terminal },
                turns = all?.sumOf { it.usage.turns },
                costUsd = all?.fold(BigDecimal.ZERO) { acc, s -> acc + s.usage.costUsd },
                costEstimated = all?.any { it.usage.costEstimated } == true,
                manual = manual,
            )
        }

        /** Ручные сборки и проверки проекта за сутки до [now]: идущий запуск — только свой. */
        fun manual(project: ProjectEntry, active: ManualRun?, history: com.dpsmpx.dpai.core.result.Outcome<List<ManualHistoryEntry>>, now: Timestamp): ManualCounts =
            ManualCounts.of(active?.takeIf { it.project.project == project.project }, listOf(history), now)
    }
}
