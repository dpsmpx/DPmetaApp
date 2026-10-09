package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.monitoring.DashboardView
import com.dpsmpx.dpai.monitoring.DisplayState
import com.dpsmpx.dpai.monitoring.SelfDiagnosis
import com.dpsmpx.dpai.monitoring.SystemHealth
import com.dpsmpx.dpai.monitoring.Timeline
import com.dpsmpx.dpai.monitoring.UsageReport
import com.dpsmpx.dpai.sessions.LiveTurn
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.time.Duration

/**
 * Смысл цвета, а не сам цвет: оболочка (Android, `cli`) сама выбирает
 * оттенок по теме. `WARNING` — нужно внимание, `ERROR` — отказ.
 */
enum class Tone { NEUTRAL, ACTIVE, WARNING, ERROR, DONE }

data class Counter(val label: String, val value: String, val tone: Tone)

data class SessionLine(val session: SessionId, val task: String, val state: String, val tone: Tone, val silent: String, val next: String)

/** Сводка (ТЗ, §38): состояние приложения, счётчики, сессии, что требует внимания. */
data class DashboardScreen(
    val health: String,
    val healthTone: Tone,
    val reasons: List<String>,
    val counters: List<Counter>,
    val sessions: List<SessionLine>,
    val attention: List<String>,
    /** Загрузка окон подписок и порог «у предела» (D-42, D-45). */
    val limits: List<SubscriptionLimits> = emptyList(),
)

/** Окно подписки на экране: «5 ч — 42 % · сброс 15:00»; загрузка неизвестна — «?» (D-13). */
data class WindowLine(val window: String, val percent: String, val reset: String?, val tone: Tone)

/** Подписка на сводке: окна, порог и у предела ли она сейчас. */
data class SubscriptionLimits(
    val subscription: com.dpsmpx.dpai.core.ids.SubscriptionId,
    val label: String,
    val windows: List<WindowLine>,
    val nearPercent: Int,
    val near: Boolean,
)

data class TimelineLine(val time: String, val text: String)

/** Что ход делает сейчас (D-42): инструмент и его цель, последний текст модели, как давно. */
data class LiveLine(val tool: String?, val target: String?, val text: String?, val ago: String)

/** Экран сессии (ТЗ, §39, §40): строка сводки, живой вид хода и временная шкала её потока. */
data class SessionScreen(
    val line: SessionLine?,
    val session: SessionId,
    val timeline: List<TimelineLine>,
    val live: LiveLine? = null,
    val actions: List<SessionAction> = emptyList(),
)

/** Действие человека с сессией (D-42, п. 3). */
enum class SessionAction(val label: String) {
    STOP("Остановить ход"),
    SEND("Запрос"),
    FRESH("Свежая сессия"),
}

/** Карточка задачи; [level] — глубина в DAG: 0 — без зависимостей, иначе 1 + наибольшая у зависимостей. */
data class TaskCard(val id: TaskId, val title: String, val state: String, val tone: Tone, val priority: String, val dependsOn: List<String>, val level: Int, val held: Boolean = false)

data class BoardColumn(val title: String, val cards: List<TaskCard>)

/** Доска задач и DAG (ТЗ, §41): колонки по смыслу состояния, внутри — по уровню DAG и id. */
data class TaskBoard(val columns: List<BoardColumn>)

/**
 * Состояния экранов без платформы (`docs/ARCHITECTURE.md`, §2: `ui`):
 * Android только рисует их (D-02), тесты проверяют на хосте. Чистые
 * функции — одинаковый вход даёт одинаковый экран (D-22); неизвестное —
 * «?», а не ноль (D-13).
 */
object Screens {
    /** [usage] — отчёт расхода (D-40): три меры на сводке, когда ходы уже были. */
    fun dashboard(view: DashboardView, health: SelfDiagnosis, usage: UsageReport? = null): DashboardScreen = DashboardScreen(
        health = health.health.name,
        healthTone = when (health.health) {
            SystemHealth.HEALTHY -> Tone.DONE
            SystemHealth.DEGRADED, SystemHealth.RECOVERY -> Tone.WARNING
            else -> Tone.ERROR
        },
        reasons = health.reasons,
        counters = listOf(
            Counter("Активных сессий", view.activeSessions.toString(), if (view.activeSessions > 0) Tone.ACTIVE else Tone.NEUTRAL),
            Counter("В очереди", view.queuedTasks.toString(), Tone.NEUTRAL),
            Counter("Заблокировано", view.blocked.toString(), if (view.blocked > 0) Tone.WARNING else Tone.NEUTRAL),
            Counter("Упало", view.failed.toString(), if (view.failed > 0) Tone.ERROR else Tone.NEUTRAL),
            Counter("Ждут одобрения", count(view.waitingApproval), if ((view.waitingApproval.orNull() ?: 0) > 0) Tone.WARNING else Tone.NEUTRAL),
            Counter("Аккаунтов доступно", view.availableAccounts.toString(), Tone.NEUTRAL),
        ) + usageCounters(usage),
        sessions = view.rows.map { r ->
            SessionLine(r.session, r.task, r.state.name, tone(r.state), silent(r.silentFor), r.next ?: "—")
        },
        attention = view.attention.map { "${it.subject}: ${it.detail}" },
    )

    /** Расход на сводке (`docs/EFFICIENCY.md`, §9): работа на расход, кеш prompt, сэкономленный контекст (оценка). */
    private fun usageCounters(u: UsageReport?): List<Counter> {
        if (u == null || u.turns + u.unknownTurns + u.prompts == 0) return emptyList()
        fun pct(v: Double?) = v?.let { "${(it * 100).toInt()} %" } ?: "—"
        return listOf(
            Counter("Токенов на задачу", u.tokensPerDoneTask?.toString() ?: "—", Tone.NEUTRAL),
            Counter("Вход из кеша", pct(u.cacheReadShare), Tone.NEUTRAL),
            Counter("Контекст не послан (оценка)", pct(u.contextSavedShare), Tone.NEUTRAL),
        )
    }

    fun session(
        view: DashboardView,
        session: SessionId,
        events: List<EventEnvelope>,
        live: LiveTurn? = null,
        now: com.dpsmpx.dpai.core.clock.Timestamp? = null,
        actions: List<SessionAction> = emptyList(),
    ): SessionScreen {
        val stream = StreamRef.of(session)
        val own = events.filter { it.stream == stream }
        return SessionScreen(
            line = dashboard(view, OK).sessions.firstOrNull { it.session == session },
            session = session,
            timeline = Timeline.of(own).map { TimelineLine(it.at.toIsoString().substring(11, 19), it.text) },
            live = live?.let { l -> LiveLine(l.tool, l.target, l.text, now?.let { ago(it.epochMillis - l.at.epochMillis) } ?: "?") },
            actions = actions,
        )
    }

    /**
     * Действия с сессией (D-42): остановить — идущий ход; запрос и свежая
     * сессия — последней сессии задачи, когда ход не идёт и задача не на
     * проверке. Оркестратор проверяет каждую команду сам.
     */
    fun sessionActions(session: SessionId, sessions: SessionsState, tasks: TasksState): List<SessionAction> {
        val s = sessions.sessions[session] ?: return emptyList()
        val own = sessions.sessions.values.filter { it.task == s.task }
        val running = own.any { !it.terminal && it.state !in WAIT }
        val last = own.maxWithOrNull(compareBy({ it.createdAt.epochMillis }, { it.id.value }))?.id == session
        val taskState = tasks.tasks[s.task]?.state
        return buildList {
            if (!s.terminal && s.state !in WAIT) add(SessionAction.STOP)
            if (last && !running && taskState !in BUSY) {
                add(SessionAction.SEND)
                add(SessionAction.FRESH)
            }
        }
    }

    private val WAIT = setOf(com.dpsmpx.dpai.core.state.SessionState.PAUSED, com.dpsmpx.dpai.core.state.SessionState.BLOCKED, com.dpsmpx.dpai.core.state.SessionState.RECOVERY_PENDING)
    private val BUSY = setOf(TaskState.RUNNING, TaskState.IMPLEMENTED, TaskState.VERIFYING, TaskState.VERIFIED)

    /**
     * Пределы подписок (D-45): загрузка окон «5 ч» и «неделя», время сброса
     * в поясе [zone], порог; тон окна — по порогу.
     */
    fun limits(accounts: com.dpsmpx.dpai.accounts.AccountsState, now: com.dpsmpx.dpai.core.clock.Timestamp, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<SubscriptionLimits> =
        accounts.subscriptions.values.sortedBy { it.id.value }.map { sub ->
            SubscriptionLimits(
                sub.id, sub.label,
                com.dpsmpx.dpai.accounts.LimitWindow.entries.map { w ->
                    val ws = sub.window(w)
                    val u = ws.utilization
                    WindowLine(
                        if (w == com.dpsmpx.dpai.accounts.LimitWindow.SESSION) "5 ч" else "неделя",
                        u?.let { "${Math.round(it * 100)} %" } ?: "?",
                        ws.resetAt?.takeIf { it > now }?.let { r ->
                            val t = java.time.Instant.ofEpochMilli(r.epochMillis).atZone(zone)
                            if (r.epochMillis - now.epochMillis < 86_400_000) "%02d:%02d".format(t.hour, t.minute) else "%02d.%02d %02d:%02d".format(t.dayOfMonth, t.monthValue, t.hour, t.minute)
                        },
                        when {
                            ws.limitedAt(now) -> Tone.ERROR
                            ws.nearAt(now, sub.nearPercent / 100.0) -> Tone.WARNING
                            u == null -> Tone.NEUTRAL
                            else -> Tone.DONE
                        },
                    )
                },
                sub.nearPercent, sub.nearAt(now),
            )
        }

    /** «3 с назад», «2 мин назад»; будущее (часы разошлись) — «только что». */
    fun ago(ms: Long): String = when {
        ms < 1_000 -> "только что"
        ms < 60_000 -> "${ms / 1_000} с назад"
        ms < 3_600_000 -> "${ms / 60_000} мин назад"
        else -> "${ms / 3_600_000} ч назад"
    }

    /** Доска (ТЗ, §41); архив (D-42) — только по [showArchived]. */
    fun board(tasks: TasksState, showArchived: Boolean = false): TaskBoard = boardOf(if (showArchived) tasks else TasksState(tasks.tasks.filterValues { !it.archived }))

    private fun boardOf(tasks: TasksState): TaskBoard {
        val level = HashMap<TaskId, Int>()
        fun levelOf(id: TaskId, path: Set<TaskId>): Int = level[id] ?: run {
            // Цикл в DAG планировщик не пропускает (DEPENDENCY_CYCLE); здесь — только защита от зацикливания отрисовки.
            val deps = tasks.tasks[id]?.dependencies.orEmpty().filter { it in tasks.tasks && it !in path }
            (if (deps.isEmpty()) 0 else 1 + deps.maxOf { levelOf(it, path + id) }).also { level[id] = it }
        }
        val cards = tasks.tasks.values.map { t ->
            TaskCard(
                t.id, t.title, t.state.name, tone(t.state), t.priority.name,
                t.dependencies.mapNotNull { tasks.tasks[it]?.title }.sorted(), levelOf(t.id, emptySet()), t.held,
            )
        }
        return TaskBoard(
            COLUMNS.map { (title, states) ->
                BoardColumn(title, cards.filter { TaskState.valueOf(it.state) in states }.sortedWith(compareBy({ it.level }, { it.id.value })))
            },
        )
    }

    fun tone(s: DisplayState): Tone = when (s) {
        DisplayState.STARTING, DisplayState.RUNNING, DisplayState.WAITING, DisplayState.VERIFYING -> Tone.ACTIVE
        DisplayState.STALE, DisplayState.BLOCKED, DisplayState.NEAR_LIMIT, DisplayState.PAUSED, DisplayState.RECOVERY_PENDING -> Tone.WARNING
        DisplayState.FAILED -> Tone.ERROR
        DisplayState.COMPLETED -> Tone.DONE
        DisplayState.CREATED, DisplayState.QUEUED, DisplayState.CANCELLED -> Tone.NEUTRAL
    }

    fun tone(s: TaskState): Tone = when (s) {
        TaskState.RUNNING, TaskState.WAITING, TaskState.IMPLEMENTED, TaskState.VERIFYING, TaskState.VERIFIED -> Tone.ACTIVE
        TaskState.BLOCKED, TaskState.COMPLETION_UNVERIFIED -> Tone.WARNING
        TaskState.FAILED -> Tone.ERROR
        TaskState.DONE -> Tone.DONE
        TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY, TaskState.CANCELLED -> Tone.NEUTRAL
    }

    private val COLUMNS = listOf(
        "Очередь" to setOf(TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY),
        "В работе" to setOf(TaskState.RUNNING, TaskState.WAITING, TaskState.IMPLEMENTED, TaskState.VERIFYING, TaskState.VERIFIED),
        "Внимание" to setOf(TaskState.BLOCKED, TaskState.COMPLETION_UNVERIFIED, TaskState.FAILED),
        "Готово" to setOf(TaskState.DONE, TaskState.CANCELLED),
    )

    private val OK = SelfDiagnosis(SystemHealth.HEALTHY, emptySet(), emptyList())

    private fun count(m: Measure<Int>): String = m.orNull()?.toString() ?: "?"

    private fun silent(m: Measure<Duration>): String {
        val d = m.orNull() ?: return "?"
        return when {
            d.inWholeSeconds < 60 -> "${d.inWholeSeconds} с"
            d.inWholeMinutes < 60 -> "${d.inWholeMinutes} мин"
            else -> "${d.inWholeHours} ч"
        }
    }
}
