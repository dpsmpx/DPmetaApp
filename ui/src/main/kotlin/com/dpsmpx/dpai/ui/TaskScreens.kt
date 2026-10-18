package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskRecord

/**
 * Действие человека с задачей (D-42, п. 2); [dangerous] — только после
 * явного подтверждения (ТЗ, §68): отмена теряет несохранённую работу хода,
 * исключение записывает «готово» без проверки.
 */
enum class TaskAction(val label: String, val dangerous: Boolean = false) {
    PRIORITY("Приоритет"),
    POLICY("Модель и бюджет"),
    /** Область и зависимости (D-42, п. 1) — до запуска. */
    SCOPE("Область и зависимости"),
    HOLD("Приостановить"),
    RESUME("Продолжить"),
    RETRY("Повторить"),
    REPLY("Ответить и повторить"),
    VERIFY("Проверить снова"),
    WAIVE("Принять без проверки", dangerous = true),
    ARCHIVE("В архив"),
    UNARCHIVE("Из архива"),
    CANCEL("Отменить задачу", dangerous = true),
}

/** Лист задачи: что с ней сейчас, её политика и доступные действия. */
data class TaskDetail(
    val id: TaskId,
    val title: String,
    val objective: String,
    val state: String,
    val tone: Tone,
    val held: Boolean,
    val priority: String,
    val taskClass: TaskClass,
    val model: String?,
    val effort: String?,
    val budgetCents: Long?,
    val retries: Int,
    val reason: String?,
    val lastSession: SessionId?,
    val actions: List<TaskAction>,
    /** Почему готовая задача не начинается (нет слота, подписка у предела) — если оркестратор знает. */
    val waiting: String? = null,
    /** Область — пути в репозитории (ТЗ, §31); `null` — не заявлена. */
    val scope: Set<String>? = null,
    /** Зависимости: задача, её название и состояние (ТЗ, §21). */
    val dependencies: List<DependencyLine> = emptyList(),
    /** Расход всех сессий задачи (D-42, п. 4); ходов не было — `null`. */
    val usage: String? = null,
)

/** Строка зависимости в листе задачи. */
data class DependencyLine(val id: TaskId, val title: String, val state: String)

/**
 * Какие действия доступны задаче (D-42) — по её состоянию; оркестратор
 * всё равно проверяет каждую команду сам, экран лишь не предлагает
 * заведомо отклонённое. Чистая функция (D-22).
 */
object TaskScreens {
    private val EDITABLE = setOf(TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY, TaskState.WAITING, TaskState.BLOCKED, TaskState.FAILED, TaskState.CANCELLED)
    private val HOLDABLE = setOf(TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY, TaskState.RUNNING, TaskState.WAITING, TaskState.BLOCKED)

    fun actions(t: TaskRecord): List<TaskAction> = buildList {
        if (!t.terminal) add(TaskAction.PRIORITY)
        if (t.state in EDITABLE) add(TaskAction.POLICY)
        if (t.state in EDITABLE && !t.terminal) add(TaskAction.SCOPE)
        if (t.held) add(TaskAction.RESUME) else if (t.state in HOLDABLE) add(TaskAction.HOLD)
        if (t.state == TaskState.FAILED || t.state == TaskState.CANCELLED || t.state == TaskState.BLOCKED) add(TaskAction.RETRY)
        if (t.state == TaskState.BLOCKED) add(TaskAction.REPLY)
        if (t.state == TaskState.COMPLETION_UNVERIFIED) {
            add(TaskAction.VERIFY)
            add(TaskAction.WAIVE)
        }
        if (!t.terminal && t.state != TaskState.VERIFIED) add(TaskAction.CANCEL)
        if (t.terminal) add(if (t.archived) TaskAction.UNARCHIVE else TaskAction.ARCHIVE)
    }

    fun detail(t: TaskRecord, sessions: SessionsState, waiting: String? = null, tasks: Map<TaskId, TaskRecord> = emptyMap()): TaskDetail = TaskDetail(
        id = t.id, title = t.title, objective = t.objective,
        state = t.state.name, tone = Screens.tone(t.state), held = t.held, priority = t.priority.name,
        taskClass = t.policy.taskClass, model = t.policy.model, effort = t.policy.effort, budgetCents = t.policy.maxBudgetUsdCents,
        retries = t.retries, reason = t.lastReason?.takeIf { t.state != TaskState.DONE }?.name,
        lastSession = sessions.sessions.values.filter { it.task == t.id }.maxByOrNull { it.createdAt.epochMillis }?.id,
        actions = actions(t),
        waiting = waiting?.takeIf { t.state == TaskState.READY },
        scope = t.scope,
        dependencies = t.dependencies.map { d -> DependencyLine(d, tasks[d]?.title ?: d.value, tasks[d]?.state?.name ?: "?") }.sortedBy { it.title },
        usage = UsageText.of(com.dpsmpx.dpai.sessions.UsageSummary.of(sessions.sessions.values.filter { it.task == t.id }.map { it.usage })),
    )

    /**
     * Задачи, от которых можно зависеть (D-42, п. 1): не завершённые, кроме
     * самой [except]; по названию. Оркестратор цикл всё равно отвергнет.
     */
    fun dependencyChoices(tasks: Map<TaskId, TaskRecord>, except: TaskId? = null): List<DependencyLine> =
        tasks.values.filter { !it.terminal && !it.archived && it.id != except }
            .map { DependencyLine(it.id, it.title, it.state.name) }
            .sortedBy { it.title }
}
