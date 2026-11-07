package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.monitoring.DashboardView
import com.dpsmpx.dpai.monitoring.DisplayState
import com.dpsmpx.dpai.monitoring.SelfDiagnosis
import com.dpsmpx.dpai.monitoring.SystemHealth
import com.dpsmpx.dpai.monitoring.Timeline
import com.dpsmpx.dpai.monitoring.UsageReport
import com.dpsmpx.dpai.orchestrator.review.Review
import com.dpsmpx.dpai.sessions.LiveTurn
import com.dpsmpx.dpai.sessions.SessionRecord
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.TasksState
import kotlin.time.Duration

/**
 * Смысл цвета, а не сам цвет: оболочка (Android, `cli`) сама выбирает
 * оттенок по теме. `WARNING` — нужно внимание, `ERROR` — отказ.
 */
enum class Tone { NEUTRAL, ACTIVE, WARNING, ERROR, DONE }

data class Counter(val label: String, val value: String, val tone: Tone)

/** Строка сессии; [review] — название роли, если сессия — ревью задачи (D-73), а не её работа. */
data class SessionLine(val session: SessionId, val task: String, val state: String, val tone: Tone, val silent: String, val next: String, val review: String? = null)

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
    /** Расход (D-40) — отдельно от счётчиков: на сводке он свёрнут «Расход ▸» (AI:A-6). */
    val usage: List<Counter> = emptyList(),
    /** Активных сессий — числом: строка «AI» на сводке пульта (DPmetaApp:D-76). */
    val active: Int = 0,
) {
    /**
     * Счётчики, которые стоит показать (AI:A-6): обычный «0» ничего не
     * говорит и скрыт; «0» с тревогой (нет аккаунтов) и неизвестное («?») —
     * показываются (D-13).
     */
    val shownCounters: List<Counter> get() = counters.filter { it.value != "0" || it.tone != Tone.NEUTRAL }
}

/** Окно подписки на экране: «5 ч — 42 % · сброс 15:00»; загрузка неизвестна — «?» (D-13). */
data class WindowLine(val window: String, val percent: String, val reset: String?, val tone: Tone)

/** Подписка на сводке: окна, порог и у предела ли она сейчас. */
data class SubscriptionLimits(
    val subscription: com.dpsmpx.dpai.core.ids.SubscriptionId,
    val label: String,
    val windows: List<WindowLine>,
    val nearPercent: Int,
    val near: Boolean,
    /** Принимает задачи подписок, упёршихся в предел (D-56). */
    val rotation: Boolean = false,
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
    /** Расход сессии (D-42, п. 4); ходов не было — `null`. */
    val usage: String? = null,
    /** Задача сессии и лента её чата (D-59): все сессии задачи в одном пространстве. */
    val title: String? = null,
    val chat: List<ChatLine> = emptyList(),
    /**
     * Задача сессии — к ней привязано имя, которое задаёт человек, её лист
     * и контекст с экрана сессии (ТЗ, §74); неизвестна — `null`.
     */
    val task: TaskId? = null,
    /** Состояние для кружка в заголовке (D-61). */
    val status: SessionStatus = SessionStatus.IDLE,
    /** Почему сюда не пишут (A-14): ревью, не последняя сессия, проверка, завершена; пишут — `null`. */
    val noSend: String? = null,
    /** Сколько идёт текущий ход — «5 мин» (от начала хода в журнале сессии); ход не идёт — `null`. */
    val workingFor: String? = null,
    /** Исполнитель сессии — исполнитель её слота (D-100): от него подсказка поля и варианты модели (AI:A-10). */
    val executor: com.dpsmpx.dpai.core.ids.Executor = com.dpsmpx.dpai.core.ids.Executor.CLAUDE,
)

/** Состояние сессии одним знаком в заголовке окна (D-61): идёт, готово, ошибка или ждёт. */
enum class SessionStatus { RUNNING, DONE, ERROR, IDLE }

/**
 * Строка ленты чата (D-59): [mine] — запрос человека (справа); [thinking]
 * — рассуждение модели (свёрнуто); [event] — событие контура одной строкой
 * по центру; иначе — текст модели. [here] — запись этой сессии, а не
 * соседней сессии той же задачи.
 */
data class ChatLine(val text: String, val time: String, val mine: Boolean = false, val thinking: Boolean = false, val event: Boolean = false, val here: Boolean = true)

/** Действие человека с сессией (D-42, п. 3). */
enum class SessionAction(val label: String) {
    STOP("Остановить ход"),
    SEND("Запрос"),
    FRESH("Свежая сессия"),
    /**
     * Ход остановлен с handoff, следующая сессия задачи — на другом слоте
     * (D-77; ТЗ, §74, Reassign). Имя — с «прервать» (аудит удобства, A-13):
     * в листе задачи выбор слота следующего хода ход не прерывает (D-66).
     */
    REASSIGN("Прервать и перенести"),
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
        health = Words.health(health.health.name),
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
            Counter("Запросы ждут ответа", count(view.waitingApproval), if ((view.waitingApproval.orNull() ?: 0) > 0) Tone.WARNING else Tone.NEUTRAL),
            // Ни одного аккаунта — ничто не может идти: «0» здесь — предупреждение, и он виден (проверка PR #172).
            Counter("Аккаунтов доступно", view.availableAccounts.toString(), if (view.availableAccounts == 0) Tone.WARNING else Tone.NEUTRAL),
        ),
        usage = usageCounters(usage),
        active = view.activeSessions,
        sessions = view.rows.map { r ->
            SessionLine(r.session, r.task, Words.display(r.state.name), tone(r.state), silent(r.silentFor), r.next ?: "—", r.review?.let(::reviewLabel))
        },
        attention = view.attention.map { "${it.title}: ${it.detail}" },
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
        usage: com.dpsmpx.dpai.sessions.UsageTotals? = null,
        task: TaskId? = null,
    ): SessionScreen {
        val stream = StreamRef.of(session)
        val own = events.filter { it.stream == stream }
        return SessionScreen(
            line = dashboard(view, OK).sessions.firstOrNull { it.session == session },
            session = session,
            timeline = Timeline.of(own).map { TimelineLine(it.at.toIsoString().substring(11, 19), it.text) },
            live = live?.let { l -> LiveLine(l.tool, l.target, l.text, now?.let { ago(it.epochMillis - l.at.epochMillis) } ?: "?") },
            actions = actions,
            usage = usage?.let { UsageText.of(com.dpsmpx.dpai.sessions.UsageSummary.of(listOf(it))) },
            task = task,
        )
    }

    /** Название роли ревью по имени из записи сессии (D-73); незнакомое — как есть. */
    fun reviewLabel(role: String): String = ReviewRole.entries.firstOrNull { it.name == role }?.let(Review::label) ?: "ревью $role"

    /**
     * Состояние для кружка в заголовке (D-61): ход идёт — «идёт»; задача
     * готова или ход завершён — «готово»; сбой или блок — «ошибка»; иначе —
     * «ждёт» (очередь, пауза, проверка).
     */
    fun sessionStatus(record: SessionRecord?, taskState: TaskState?, running: Boolean): SessionStatus = when {
        running -> SessionStatus.RUNNING
        taskState == TaskState.FAILED || taskState == TaskState.BLOCKED ||
            record?.state == com.dpsmpx.dpai.core.state.SessionState.FAILED || record?.state == com.dpsmpx.dpai.core.state.SessionState.BLOCKED -> SessionStatus.ERROR
        taskState == TaskState.DONE || taskState == TaskState.VERIFIED || taskState == TaskState.COMPLETION_UNVERIFIED -> SessionStatus.DONE
        else -> SessionStatus.IDLE
    }

    /**
     * Лента задачи целиком (D-62): записи чата и то, что было до него —
     * итоги ходов из `dpai.task-messages` раньше первой записи чата и цель
     * задачи, если чат начался не с неё (задачи, начатые до D-59). Вывод
     * модели — только в чате, лист задачи его больше не показывает.
     */
    fun chatEntries(
        chat: List<com.dpsmpx.dpai.orchestrator.ChatEntry>,
        turns: List<com.dpsmpx.dpai.orchestrator.TurnMessage>,
        task: com.dpsmpx.dpai.tasks.TaskRecord?,
    ): List<com.dpsmpx.dpai.orchestrator.ChatEntry> {
        val first = chat.firstOrNull()?.at?.epochMillis ?: Long.MAX_VALUE
        val older = turns.filter { it.at.epochMillis < first }
            .map { com.dpsmpx.dpai.orchestrator.TaskChat.entry(it.at, com.dpsmpx.dpai.orchestrator.ChatRole.ASSISTANT, it.text, it.session) }
        val goal = task?.takeIf { chat.firstOrNull()?.role != com.dpsmpx.dpai.orchestrator.ChatRole.USER }?.let { t ->
            com.dpsmpx.dpai.orchestrator.TaskChat.entry(t.createdAt, com.dpsmpx.dpai.orchestrator.ChatRole.USER, if (t.objective.trim() == t.title.trim()) t.title else "${t.title}\n\n${t.objective}")
        }
        return (listOfNotNull(goal) + older + chat).sortedBy { it.at.epochMillis }
    }

    /**
     * Лента чата задачи для экрана (D-59): время — «ЧЧ:ММ» в поясе [zone],
     * у записей другого дня — «ДД.ММ ЧЧ:ММ»; записи по порядку.
     */
    fun chat(
        entries: List<com.dpsmpx.dpai.orchestrator.ChatEntry>,
        session: SessionId?,
        now: com.dpsmpx.dpai.core.clock.Timestamp,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): List<ChatLine> {
        val today = java.time.Instant.ofEpochMilli(now.epochMillis).atZone(zone).toLocalDate()
        return entries.map { e ->
            val t = java.time.Instant.ofEpochMilli(e.at.epochMillis).atZone(zone)
            val time = if (t.toLocalDate() == today) "%02d:%02d".format(t.hour, t.minute) else "%02d.%02d %02d:%02d".format(t.dayOfMonth, t.monthValue, t.hour, t.minute)
            ChatLine(
                e.text, time,
                mine = e.role == com.dpsmpx.dpai.orchestrator.ChatRole.USER,
                thinking = e.role == com.dpsmpx.dpai.orchestrator.ChatRole.THINKING,
                event = e.role == com.dpsmpx.dpai.orchestrator.ChatRole.EVENT,
                here = e.session == null || session == null || e.session == session,
            )
        }
    }

    /**
     * Действия с сессией (D-42): остановить — идущий ход; запрос и свежая
     * сессия — последней рабочей сессии задачи, когда ход не идёт и задача
     * не на проверке. Сессии ревью продолжать нельзя (D-73): она только
     * читает — запрос ей оркестратор отвергнет. Оркестратор проверяет
     * каждую команду сам.
     */
    fun sessionActions(session: SessionId, sessions: SessionsState, tasks: TasksState): List<SessionAction> {
        val s = sessions.sessions[session] ?: return emptyList()
        val own = sessions.sessions.values.filter { it.task == s.task }
        val running = own.any { !it.terminal && it.state !in WAIT }
        val last = s.review == null && own.filter { it.review == null }.maxWithOrNull(compareBy({ it.createdAt.epochMillis }, { it.id.value }))?.id == session
        val taskState = tasks.tasks[s.task]?.state
        return buildList {
            if (!s.terminal && s.state !in WAIT) add(SessionAction.STOP)
            // Во время хода сообщение — в очередь, ход не прерывается (D-60); свежая сессия — после хода.
            if (last && (running || taskState !in BUSY)) add(SessionAction.SEND)
            if (last && !running && taskState !in BUSY) add(SessionAction.FRESH)
            // Перенос — у последней рабочей сессии незавершённой задачи, пока она не на проверке (D-77).
            if (last && tasks.tasks[s.task]?.terminal == false && taskState !in CHECKING) add(SessionAction.REASSIGN)
        }
    }

    /**
     * Почему запрос в эту сессию не отправить — словами, для подсказки в
     * поле (аудит удобства, A-14); можно — `null`. Прежде на всё было
     * «задача проверяется или завершена», и при ждущем ответе тоже.
     */
    fun noSend(session: SessionId, sessions: SessionsState, tasks: TasksState): String? {
        val s = sessions.sessions[session] ?: return "сессии нет"
        if (SessionAction.SEND in sessionActions(session, sessions, tasks)) return null
        val t = tasks.tasks[s.task]
        return when {
            s.review != null -> "сессия ревью только читает — сообщение в рабочую сессию задачи"
            t == null -> "задачи нет"
            t.terminal -> "задача завершена (${Words.task(t.state.name)})"
            t.state in CHECKING -> "задача на проверке — сообщение после неё"
            else -> "сообщение — в последнюю рабочую сессию задачи"
        }
    }

    private val WAIT = setOf(com.dpsmpx.dpai.core.state.SessionState.PAUSED, com.dpsmpx.dpai.core.state.SessionState.BLOCKED, com.dpsmpx.dpai.core.state.SessionState.RECOVERY_PENDING)
    private val CHECKING = setOf(TaskState.IMPLEMENTED, TaskState.VERIFYING, TaskState.VERIFIED)
    private val BUSY = setOf(TaskState.RUNNING, TaskState.IMPLEMENTED, TaskState.VERIFYING, TaskState.VERIFIED)

    /**
     * Пределы подписок (D-45): загрузка окон «5 ч» и «неделя», время сброса
     * в поясе [zone], порог; тон окна — по порогу.
     */
    fun limits(
        accounts: com.dpsmpx.dpai.accounts.AccountsState,
        now: com.dpsmpx.dpai.core.clock.Timestamp,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        labelOf: (com.dpsmpx.dpai.accounts.SubscriptionState) -> String = { it.label },
    ): List<SubscriptionLimits> =
        accounts.subscriptions.values.sortedBy { it.id.value }.map { sub ->
            SubscriptionLimits(
                sub.id, labelOf(sub),
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
                sub.nearPercent, sub.nearAt(now), sub.rotationAllowed,
            )
        }

    /** «3 с назад», «2 мин назад»; будущее (часы разошлись) — «только что». */
    /** Длительность для «работает …»: «40 с», «5 мин», «1 ч 5 мин». */
    fun duration(ms: Long): String = when {
        ms < 60_000 -> "${(ms / 1_000).coerceAtLeast(0)} с"
        ms < 3_600_000 -> "${ms / 60_000} мин"
        else -> "${ms / 3_600_000} ч ${ms % 3_600_000 / 60_000} мин"
    }

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
        "Ждёт вас" to setOf(TaskState.BLOCKED, TaskState.COMPLETION_UNVERIFIED, TaskState.FAILED),
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
