package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.ids.ApprovalId
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState

/** Что проверяют инварианты: состояние контура, журнал и выданная диагностика. */
data class InvariantInput(
    val accounts: AccountsState,
    val sessions: SessionsState,
    val tasks: TasksState,
    val worktrees: WorktreesState,
    val targets: Set<ExecutionTargetId>,
    val events: List<EventEnvelope>,
    val merged: List<TaskId>,
    val mergeApprovals: Map<TaskId, ApprovalId>,
    val diagnostics: List<Diagnostic>,
)

/**
 * Машинно проверяемые инварианты (ТЗ, §118, аудит S; критерий этапа 19).
 * Нарушение — `INVARIANT_VIOLATED` с именем инварианта и предметом; пустой
 * список — всё держится. Проверка — чистая функция (D-22): её зовут тесты
 * сценариев, хаоса и восстановления после каждого шага.
 */
object Invariants {
    private val ACTIVE = setOf(SessionState.STARTING, SessionState.RUNNING, SessionState.WAITING, SessionState.NEAR_LIMIT, SessionState.VERIFYING)

    fun check(i: InvariantInput): List<Diagnostic> = buildList {
        fun fail(name: String, subject: String, detail: String) = add(Diagnostic.error(DiagCode.INVARIANT_VIOLATED, "$name: $detail", subject))
        val active = i.sessions.sessions.values.filter { it.state in ACTIVE }

        // У каждой работающей сессии ровно одна задача — и она есть.
        for (s in active) if (s.task !in i.tasks.tasks) fail("session-has-task", "session ${s.id}", "задачи ${s.task} нет")
        // У задачи не больше одной активной сессии (параллельного исполнения одной задачи в V1 нет).
        for ((task, ss) in active.groupBy { it.task }) if (ss.size > 1) fail("task-single-session", "task $task", "активных сессий ${ss.size}")
        // Каждая активная сессия — на одном известном target.
        for (s in active) if (s.target !in i.targets) fail("session-has-target", "session ${s.id}", "target ${s.target} неизвестен")
        // У каждой активной сессии есть слот аккаунта, и слот её держит.
        for (s in active) {
            val slot = i.accounts.slots[s.slot]
            when {
                slot == null -> fail("session-has-account", "session ${s.id}", "слота ${s.slot} нет")
                s.id !in slot.sessions -> fail("session-has-account", "session ${s.id}", "слот ${s.slot} её не держит")
            }
        }
        // Каждое разрушительное действие (слияние в ветку интеграции) — с записью одобрения.
        for (t in i.merged) if (t !in i.mergeApprovals) fail("destructive-approved", "task $t", "слияние без одобрения")
        // У каждого события устойчивый id: id не повторяются, номера в потоке — подряд.
        val seen = HashSet<String>()
        for (e in i.events) if (!seen.add(e.id.value)) fail("event-stable-id", "event ${e.id}", "id повторяется")
        for ((stream, es) in i.events.groupBy { it.stream }) {
            val seqs = es.map { it.sequence }
            if (seqs != (1L..seqs.size).toList()) fail("event-sequence", "stream $stream", "номера не подряд с 1")
        }
        // В выданной диагностике нет секретов.
        for (d in i.diagnostics) if (Redactor.STANDARD.redact(d.message) != d.message) fail("no-secret-in-diagnostics", d.subject ?: "diagnostic", "в сообщении похожее на секрет")
        // Завершённая задача — с итогом проверки или записанным исключением политики (D-23).
        for (t in i.tasks.tasks.values) if (t.state == TaskState.DONE && t.verification == null) fail("done-has-verification", "task ${t.id}", "DONE без итога проверки")
        // У отменённой задачи нет активной сессии.
        for (s in active) if (i.tasks.tasks[s.task]?.state == TaskState.CANCELLED) fail("cancelled-no-session", "task ${s.task}", "отменена, но сессия ${s.id} активна")
        // У каждого живого дерева с владельцем — живая сессия-владелец; два живых дерева не делят путь.
        val live = i.worktrees.worktrees.values.filter { !it.removed }
        for (w in live) {
            val owner = w.owner ?: continue
            val s = i.sessions.sessions[owner]
            when {
                s == null -> fail("worktree-owner", "worktree ${w.id}", "владельца $owner нет")
                s.terminal -> fail("worktree-owner", "worktree ${w.id}", "владелец $owner уже завершён")
            }
        }
        for ((path, ws) in live.groupBy { it.path }) if (ws.size > 1) fail("worktree-exclusive", path, "живых деревьев ${ws.size}")
    }
}
