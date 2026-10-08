package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskLifecycle
import com.dpsmpx.dpai.core.state.TaskState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Базовый приоритет (ТЗ, §18): порядок перечисления — от высшего к низшему. */
enum class Priority { CRITICAL, HIGH, NORMAL, LOW, BACKGROUND }

/**
 * Класс задачи (D-40, п. 5; `docs/EFFICIENCY.md`, §7): по нему выбираются
 * модель, усилие и право на субагентов. По умолчанию — `NORMAL_ENGINEERING`
 * с моделью аккаунта, как до D-40.
 */
enum class TaskClass {
    TRIVIAL, CLASSIFICATION, EXTRACTION, SUMMARIZATION, REPOSITORY_RECON,
    NORMAL_ENGINEERING, COMPLEX_DEBUGGING, ARCHITECTURE, CRITICAL_REVIEW,
}

/**
 * Политика задачи (ТЗ, §20, §86): повторы, пауза перед повтором,
 * продолжения, длительность хода, бюджет. Только целые и явные пороги
 * (`docs/SCHEDULER.md`, §3). [taskClass] — маршрут модели (D-40); [model]
 * и [effort] — явный выбор автора: сильнее маршрута, без эскалации и
 * удешевления.
 */
data class TaskPolicy(
    val maxRetries: Int = 3,
    val retryBackoff: Duration = 1.minutes,
    val maxContinuations: Int = 10,
    val turnTimeout: Duration = 2.hours,
    val maxBudgetUsdCents: Long? = null,
    val taskClass: TaskClass = TaskClass.NORMAL_ENGINEERING,
    val model: String? = null,
    val effort: String? = null,
) {
    init {
        require(model == null || NAME.matches(model)) { "модель — 1…100 знаков: буквы, цифры, «.», «-», «_», «[», «]»" }
        require(effort == null || NAME.matches(effort)) { "усилие — 1…100 знаков: буквы, цифры, «.», «-», «_»" }
        require(maxRetries in 0..100) { "maxRetries — 0…100" }
        require(maxContinuations in 0..1000) { "maxContinuations — 0…1000" }
        require(!retryBackoff.isNegative() && turnTimeout.isPositive()) { "пауза ≥ 0, тайм-аут > 0" }
        require(maxBudgetUsdCents == null || maxBudgetUsdCents > 0) { "бюджет — положительный" }
    }

    private companion object {
        /** Имя модели или уровня усилия — аргумент командной строки: без пробелов и управляющих знаков. */
        val NAME = Regex("[A-Za-z0-9._\\[\\]-]{1,100}")
    }
}

/**
 * Задача (ТЗ, §7; `docs/SCHEDULER.md`). [dependencies] — «зависит от»;
 * [capabilities] — что нужно от target (ТЗ, §51). [manualPriority] —
 * ручной приоритет пользователя: больше — выше; действует, если политика
 * планировщика его разрешает. [failures] — сколько раз встречался каждый
 * ключ отказа (защита от петель, ТЗ, §22).
 */
data class TaskRecord(
    val id: TaskId,
    val title: String,
    val objective: String,
    val priority: Priority,
    val dependencies: Set<TaskId>,
    val capabilities: Set<String>,
    val policy: TaskPolicy,
    val createdAt: Timestamp,
    val state: TaskState = TaskLifecycle.table.initial,
    val manualPriority: Int? = null,
    val queuedAt: Timestamp? = null,
    val updatedAt: Timestamp = createdAt,
    val retries: Int = 0,
    val continuations: Int = 0,
    val notBefore: Timestamp? = null,
    val lastReason: DiagCode? = null,
    val failures: Map<String, Int> = emptyMap(),
    /** Последний записанный итог проверки и, если проверки не было, — исключение политики (D-23). */
    val verification: VerificationRecord? = null,
    /**
     * Заявленная область задачи — пути файлов, которые она затронет (ТЗ,
     * §31): по ней контур решает о пересечении с идущими задачами.
     * `null` — неизвестна, пересечения не проверяются (D-13).
     */
    val scope: Set<String>? = null,
) {
    val terminal: Boolean get() = state in TaskLifecycle.table.terminal
}

data class TasksState(val tasks: Map<TaskId, TaskRecord> = emptyMap()) {
    /** Сколько незавершённых задач ждут [id] — влияние на блокировку (`docs/SCHEDULER.md`, §3). */
    fun blockingImpact(id: TaskId): Int = tasks.values.count { !it.terminal && id in it.dependencies }

    companion object {
        val EMPTY = TasksState()
    }
}

/**
 * Итог проверки задачи (ТЗ, §32): `PASSED`, `FAILED`, `INCOMPLETE` и хеш
 * отчёта; [waivedBy] — кто записал исключение политики, разрешив `DONE`
 * без проверки, и [reason] — почему.
 */
data class VerificationRecord(val verdict: String, val reportHash: String?, val waivedBy: String? = null, val reason: String? = null)

/** Факт о задаче (D-12). */
sealed interface TaskEvent {
    val task: TaskId
    val at: Timestamp

    data class Created(
        override val task: TaskId,
        val title: String,
        val objective: String,
        val priority: Priority,
        val dependencies: Set<TaskId>,
        val capabilities: Set<String>,
        val policy: TaskPolicy,
        override val at: Timestamp,
    ) : TaskEvent

    data class StateChanged(
        override val task: TaskId,
        val from: TaskState,
        val to: TaskState,
        val reason: DiagCode?,
        val notBefore: Timestamp?,
        override val at: Timestamp,
    ) : TaskEvent

    data class DependenciesChanged(override val task: TaskId, val dependencies: Set<TaskId>, override val at: Timestamp) : TaskEvent

    data class PriorityChanged(override val task: TaskId, val priority: Priority, val manual: Int?, override val at: Timestamp) : TaskEvent

    /** Неудачная попытка: ключ петли и код причины; повторы и петли считаются по ним. */
    data class AttemptFailed(override val task: TaskId, val loopKey: String, val code: DiagCode, override val at: Timestamp) : TaskEvent

    data class VerificationRecorded(override val task: TaskId, val record: VerificationRecord, override val at: Timestamp) : TaskEvent

    /** Заявленная область задачи (ТЗ, §31). */
    data class ScopeChanged(override val task: TaskId, val scope: Set<String>, override val at: Timestamp) : TaskEvent

    /** Продолжение в новой сессии (handoff, ТЗ, §28): счётчик против бесконечного продолжения. */
    data class Continued(override val task: TaskId, override val at: Timestamp) : TaskEvent
}

/**
 * Политика планировщика (ТЗ, §83–§85): старение — шаг ожидания, за который
 * уровень поднимается на один; ручной приоритет — разрешён ли; порог
 * петли — сколько раз один ключ отказа допустим.
 */
data class SchedulerPolicy(
    val agingStep: Duration = 30.minutes,
    val manualPriorityAllowed: Boolean = true,
    val loopThreshold: Int = 3,
) {
    init {
        require(agingStep.isPositive()) { "шаг старения — положительный" }
        require(loopThreshold >= 2) { "порог петли ≥ 2" }
    }
}
