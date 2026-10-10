package com.dpsmpx.dpai.orchestrator.recovery

import com.dpsmpx.dpai.accounts.AccountEvent
import com.dpsmpx.dpai.accounts.Accounts
import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.git.WorktreeEvent
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TaskEvent
import com.dpsmpx.dpai.tasks.Tasks
import com.dpsmpx.dpai.tasks.TasksState

/**
 * Итог поиска сирот (ТЗ, §119; `docs/RECOVERY.md`, §5): безопасные
 * починки — события, которые можно применить сразу; остальное — вопросы
 * пользователю (диагностика), без самовольных действий.
 */
data class OrphanReport(
    val accountFixes: List<AccountEvent>,
    val worktreeFixes: List<WorktreeEvent>,
    val taskFixes: List<TaskEvent>,
    val questions: List<Diagnostic>,
) {
    val clean: Boolean get() = accountFixes.isEmpty() && worktreeFixes.isEmpty() && taskFixes.isEmpty() && questions.isEmpty()
}

/**
 * Поиск сирот (ТЗ, §119): аренды слотов, рабочие деревья и задачи без
 * живой сессии; живые сессии без аренды; запуски агента без сессии.
 * Безопасно — вернуть аренду и дерево конечной или неизвестной сессии,
 * вернуть задачу `RUNNING` без живой сессии в `READY`; остановить чужой
 * процесс или «починить» сессию без аренды — решение пользователя.
 */
class OrphanScan(private val accounts: Accounts, private val tasks: Tasks) {
    fun scan(
        sessions: SessionsState,
        accountsState: AccountsState,
        tasksState: TasksState,
        worktrees: WorktreesState,
        agentRuns: Set<SessionId>,
        now: Timestamp,
    ): OrphanReport {
        fun live(s: SessionId) = sessions.sessions[s]?.let { !it.terminal } ?: false
        val accountFixes = mutableListOf<AccountEvent>()
        var acc = accountsState
        for (slot in accountsState.slots.values.sortedBy { it.id.value }) {
            for (s in slot.sessions.keys.sortedBy { it.value }) {
                if (!live(s)) {
                    val es = accounts.release(acc, s, now).valueOrNull().orEmpty()
                    accountFixes += es
                    acc = Accounts.applyAll(acc, es)
                }
            }
        }
        val worktreeFixes = worktrees.worktrees.values.filter { w -> w.owner?.let { !w.removed && !live(it) } ?: false }.sortedBy { it.id.value }
            .flatMap { Worktrees.release(worktrees, it.id, it.owner!!, now).valueOrNull().orEmpty() }
        val taskFixes = mutableListOf<TaskEvent>()
        var ts = tasksState
        val tasksWithLiveSession = sessions.sessions.values.filter { !it.terminal }.map { it.task }.toSet()
        for (t in tasksState.tasks.values.filter { it.state == TaskState.RUNNING && it.id !in tasksWithLiveSession }.sortedBy { it.id.value }) {
            val es = tasks.continueLater(ts, t.id, now).valueOrNull().orEmpty()
            taskFixes += es
            ts = Tasks.applyAll(ts, es)
        }
        val leased = accountsState.slots.values.flatMap { it.sessions.keys }.toSet()
        val questions = buildList {
            sessions.sessions.values.filter { !it.terminal && it.id !in leased }.sortedBy { it.id.value }.forEach {
                add(Diagnostic.warning(DiagCode.ORPHAN_FOUND, "живая сессия без аренды слота: остановить или вернуть аренду — решение пользователя", "session ${it.id}"))
            }
            agentRuns.filter { it !in sessions.sessions }.sortedBy { it.value }.forEach {
                add(Diagnostic.warning(DiagCode.ORPHAN_FOUND, "запуск агента без сессии оркестратора: остановить — с одобрения пользователя", "run $it"))
            }
        }
        return OrphanReport(accountFixes, worktreeFixes, taskFixes, questions)
    }
}
