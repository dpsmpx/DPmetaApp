package com.dpsmpx.dpai.monitoring

import com.dpsmpx.dpai.accounts.AccountsState
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.ExecutionTargetId
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.state.AccountState
import com.dpsmpx.dpai.core.state.HeartbeatFreshness
import com.dpsmpx.dpai.core.state.SessionState
import com.dpsmpx.dpai.core.state.TargetState
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.execution.claude.TurnTools
import com.dpsmpx.dpai.execution.target.TargetHealth
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.Sessions
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.time.Duration

/** Что показать о сессии (ТЗ, §38, §122): `STALE` — работающая по автомату, но молчащая дольше порога. */
enum class DisplayState { CREATED, QUEUED, STARTING, RUNNING, WAITING, PAUSED, BLOCKED, NEAR_LIMIT, VERIFYING, RECOVERY_PENDING, STALE, COMPLETED, FAILED, CANCELLED }

data class SessionRow(
    val session: SessionId,
    val task: String,
    val state: DisplayState,
    val freshness: HeartbeatFreshness,
    val silentFor: Measure<Duration>,
    val next: String?,
    /** Роль ревью (D-73), если сессия — ревью задачи, а не её работа. */
    val review: String? = null,
)

/** Что требует внимания пользователя (ТЗ, §38): блокировки, вход, недоступные targets, упавшие задачи. */
/**
 * Что требует внимания: [subject] — id (по нему ведёт ссылка), [title] —
 * имя для человека (название задачи, каталог слота), [detail] — словами
 * (D-89; аудит 1 ноября 2026, A-3).
 */
data class Attention(val kind: String, val subject: String, val detail: String, val title: String = subject)

/** Сводка (ТЗ, §38). Неизвестное — `Measure.Unknown`, а не ноль (D-13). */
data class DashboardView(
    val activeSessions: Int,
    val queuedTasks: Int,
    val blocked: Int,
    val failed: Int,
    val waitingApproval: Measure<Int>,
    val availableAccounts: Int,
    val onlineTargets: Int,
    val rows: List<SessionRow>,
    val attention: List<Attention>,
)

/**
 * Сборка сводки из состояния (ТЗ, §38, §122). Чистая функция: одинаковое
 * состояние и время — одинаковая сводка (D-22). Работающая сессия, о
 * которой нет вестей дольше порога `stale`, показывается `STALE`, а не
 * `RUNNING`; `DEAD` — только по пробе (D-13).
 */
object Dashboard {
    private val ACTIVE = setOf(SessionState.STARTING, SessionState.RUNNING, SessionState.WAITING, SessionState.NEAR_LIMIT, SessionState.VERIFYING)
    private val DISPLAY = SessionState.entries.map { DisplayState.valueOf(it.name) }.toTypedArray()
    private val SESSION_ORDER = Comparator<SessionRecord> { a, b ->
        val terminal = a.terminal.compareTo(b.terminal)
        val state = a.state.ordinal.compareTo(b.state.ordinal)
        when {
            terminal != 0 -> terminal
            state != 0 -> state
            else -> a.id.value.compareTo(b.id.value)
        }
    }

    fun build(
        sessions: SessionsState,
        tasks: TasksState,
        accounts: AccountsState,
        targets: Map<ExecutionTargetId, TargetHealth>,
        pendingApprovals: Measure<Int>,
        sessionsLogic: Sessions,
        now: Timestamp,
    ): DashboardView {
        var activeSessions = 0
        var blockedSessions = 0
        val ordered = sessions.sessions.values.sortedWith(SESSION_ORDER)
        val rows = ordered.map {
            if (it.state in ACTIVE) activeSessions++
            if (it.state == SessionState.BLOCKED) blockedSessions++
            row(it, tasks, sessionsLogic, now)
        }
        val attention = buildList {
            fun taskOf(s: SessionRecord) = "сессия задачи «${tasks.tasks[s.task]?.title ?: s.task.value}»"
            ordered.asSequence().filter { it.state == SessionState.BLOCKED }
                .forEach { add(Attention("session-blocked", it.id.value, it.lastReason?.let { r -> Words.reason(r.name) } ?: "заблокирована", taskOf(it))) }
            rows.asSequence().filter { it.state == DisplayState.STALE }.forEach { r ->
                val title = sessions.sessions[r.session]?.let(::taskOf) ?: r.session.value
                add(Attention("session-stale", r.session.value, "нет вестей ${r.silentFor.orNull()?.let(Words::duration) ?: "давно"}", title))
            }
            accounts.slots.values.filter { it.state == AccountState.AUTH_REQUIRED && !it.disabled }.sortedBy { it.id.value }
                .forEach { add(Attention("account-login", it.id.value, "нужен вход в Claude Code", "слот ${it.configDir.substringAfterLast('/')}")) }
            targets.toSortedMap(compareBy { it.value }).filterValues { it.state == TargetState.OFFLINE }
                .forEach { (id, _) -> add(Attention("target-offline", id.value, "target недоступен")) }
            tasks.tasks.values.filter { it.state == TaskState.FAILED }.sortedBy { it.id.value }
                .forEach { add(Attention("task-failed", it.id.value, it.lastReason?.let { r -> Words.reason(r.name) } ?: "упала", "задача «${it.title}»")) }
        }
        return DashboardView(
            activeSessions = activeSessions,
            queuedTasks = tasks.tasks.values.count { it.state == TaskState.QUEUED || it.state == TaskState.READY },
            blocked = tasks.tasks.values.count { it.state == TaskState.BLOCKED } + blockedSessions,
            failed = tasks.tasks.values.count { it.state == TaskState.FAILED },
            waitingApproval = pendingApprovals,
            availableAccounts = accounts.slots.values.count { it.state == AccountState.AVAILABLE },
            onlineTargets = targets.values.count { it.state == TargetState.ONLINE || it.state == TargetState.DEGRADED },
            rows = rows,
            attention = attention,
        )
    }

    fun row(r: SessionRecord, tasks: TasksState, sessionsLogic: Sessions, now: Timestamp): SessionRow {
        val freshness = sessionsLogic.freshness(r, now)
        val display = when {
            r.state in ACTIVE && (freshness == HeartbeatFreshness.STALE || freshness == HeartbeatFreshness.UNKNOWN) -> DisplayState.STALE
            else -> DISPLAY[r.state.ordinal]
        }
        val last = r.lastHeartbeat ?: r.updatedAt
        val task = tasks.tasks[r.task]
        val next = task?.let { if (it.state == TaskState.VERIFIED && it.policy.reviews.isNotEmpty()) "ревью" else nextOf(it.state) }
        return SessionRow(r.id, task?.title ?: r.task.value, display, freshness, Measure.observed(now - last), next, r.review)
    }

    private fun nextOf(s: TaskState): String? = when (s) {
        TaskState.RUNNING -> "работа"
        TaskState.IMPLEMENTED, TaskState.VERIFYING -> "проверка"
        TaskState.COMPLETION_UNVERIFIED -> "решение по проверке"
        TaskState.BLOCKED -> "вмешательство"
        TaskState.READY, TaskState.QUEUED -> "назначение"
        else -> null
    }
}

/** Строка временной шкалы сессии (ТЗ, §40). */
data class TimelineEntry(val at: Timestamp, val sequence: Long, val text: String)

/**
 * Временная шкала из событий потока сессии (ТЗ, §40): по номерам, без
 * пропусков; события без описания для человека пропускаются, текст
 * модели — не шкала, а история (экран сессии).
 */
object Timeline {
    fun of(events: List<EventEnvelope>): List<TimelineEntry> =
        events.sortedBy { it.sequence }.mapNotNull { e -> describe(e)?.let { TimelineEntry(e.occurredAt, e.sequence, it) } }

    fun describe(e: EventEnvelope): String? = when (e.type) {
        EventType.SESSION_CREATED -> "сессия создана"
        EventType.SESSION_STATE_CHANGED -> "состояние: ${str(e, "from")} → ${str(e, "to")}" + (str(e, "reason")?.let { " ($it)" } ?: "")
        EventType.SESSION_TURN_STARTED -> "ход ${num(e, "turn")} начат"
        EventType.SESSION_TURN_FINISHED -> "ход ${num(e, "turn")}: ${str(e, "status")}"
        EventType.SESSION_TOOL_STARTED -> "инструмент ${str(e, "tool")}"
        EventType.SESSION_TURN_TOOLS -> tools(e)
        EventType.TASK_ASSIGNED -> "задача назначена"
        EventType.TASK_STATE_CHANGED -> "задача: ${str(e, "from")} → ${str(e, "to")}"
        EventType.TASK_VERIFICATION_RECORDED -> "проверка: ${str(e, "verdict")}"
        EventType.GIT_COMMIT_CREATED -> "коммит создан"
        EventType.HANDOFF_CREATED -> "handoff создан"
        EventType.TARGET_PROCESS_STARTED -> "процесс запущен"
        EventType.TARGET_PROCESS_EXITED -> "процесс завершён: ${str(e, "reason")}" + (num(e, "exitCode")?.let { ", код $it" } ?: "")
        else -> null
    }

    /** Итог инструментов хода (D-41): вызовов, вывод в контекст (оценка), повторов. */
    private fun tools(e: EventEnvelope): String? {
        val t = e.payload["tools"] as? JsonObject ?: return null
        val rows = (t["byTool"] as? JsonArray)?.items.orEmpty()
            .filterIsInstance<JsonObject>()
        fun sum(k: String) = rows.sumOf { (it[k] as? JsonNumber)?.toLongExact() ?: 0 }
        if (rows.isEmpty()) return null
        return "ход ${num(e, "turn")}: инструментов ${sum("calls")}, вывод ≈${TurnTools.tokens(sum("resultBytes"))} ток., повторов ${sum("duplicates")}"
    }

    private fun str(e: EventEnvelope, key: String): String? = (e.payload[key] as? JsonString)?.value

    private fun num(e: EventEnvelope, key: String): Long? = (e.payload[key] as? JsonNumber)?.toLongExact()
}
