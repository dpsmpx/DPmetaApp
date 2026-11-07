package com.dpsmpx.dpai.tasks

import com.dpsmpx.dpai.core.ids.Executor
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.AccountId
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

/** Роль ревью задачи (D-73): отдельная сессия после проверки и коммита. */
enum class ReviewRole { CODE, SECURITY }

/**
 * Политика задачи (ТЗ, §20, §86): повторы, пауза перед повтором,
 * продолжения, длительность хода, бюджет. Только целые и явные пороги
 * (`docs/SCHEDULER.md`, §3). [taskClass] — маршрут модели (D-40); [model]
 * и [effort] — явный выбор автора: сильнее маршрута, без эскалации и
 * удешевления. [reviews] — роли ревью (D-73); по умолчанию ревью нет, у
 * класса `TRIVIAL` его не бывает. [executor] — исполнитель задачи
 * (D-100): Claude Code, Codex или Gemini; [reviewExecutor] — исполнитель
 * ревью, `null` — тот же, что у задачи.
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
    val reviews: Set<ReviewRole> = emptySet(),
    val executor: Executor = Executor.CLAUDE,
    val reviewExecutor: Executor? = null,
) {
    /** Исполнитель ревью этой задачи (D-100). */
    val reviewer: Executor get() = reviewExecutor ?: executor

    init {
        require(reviews.isEmpty() || taskClass != TaskClass.TRIVIAL) { "у простых задач ревью нет: смените класс или снимите роли" }
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
    /**
     * Удержана человеком (D-42): планировщик её не запускает, пока
     * удержание не снято; состояние задачи при этом прежнее.
     */
    val held: Boolean = false,
    /** В архиве (D-42): завершённая задача спрятана с доски; история в журнале — как была (D-12). */
    val archived: Boolean = false,
    /** Итоги ревью текущей попытки по ролям (D-73); новая попытка (`RUNNING`) их сбрасывает. */
    val reviews: Map<ReviewRole, ReviewRecord> = emptyMap(),
    /**
     * Слот, с которого человек задачу перенёс («Перенести», D-77): следующая
     * сессия задачи на него не идёт; запуск (`RUNNING`) исключение снимает.
     */
    val excludedSlot: AccountId? = null,
) {
    /** Роли политики, у которых ещё нет итога, позволяющего `DONE` (прошло или принято без ревью); по порядку ролей. */
    fun reviewsLeft(): List<ReviewRole> =
        policy.reviews.sorted().filter { reviews[it]?.verdict.let { v -> v != ReviewVerdict.PASSED && v != ReviewVerdict.WAIVED } }

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

/** Уровень находки ревью (D-73): `BLOCKING` возвращает задачу на повтор; остальные видны, но не держат `DONE`. */
enum class FindingSeverity { BLOCKING, MAJOR, MINOR, NOTE }

/** Находка ревью: что, где ([file], [line] — если ревьюер назвал) и подробности. Длины ограничены — см. [ReviewRecord]. */
data class ReviewFinding(val severity: FindingSeverity, val title: String, val file: String? = null, val line: Int? = null, val detail: String? = null) {
    init {
        require(title.isNotBlank() && title.length <= ReviewRecord.MAX_TITLE) { "заголовок находки — 1…${ReviewRecord.MAX_TITLE} знаков" }
        require(file == null || file.length <= ReviewRecord.MAX_FILE) { "путь — до ${ReviewRecord.MAX_FILE} знаков" }
        require(line == null || line > 0) { "строка — положительная" }
        require(detail == null || detail.length <= ReviewRecord.MAX_DETAIL) { "подробности — до ${ReviewRecord.MAX_DETAIL} знаков" }
    }
}

/**
 * Итог ревью роли (D-73): `PASSED` — блокирующих нет, `BLOCKED` — есть,
 * `FAILED` — итога нет (сбой сессии, ответ не по схеме), `WAIVED` —
 * человек принял без ревью.
 */
enum class ReviewVerdict { PASSED, BLOCKED, FAILED, WAIVED }

/**
 * Запись ревью роли: [attempt] — которая попытка ревью этой роли в
 * текущей попытке задачи (сбой — повтор один раз, D-73, п. 3);
 * [omitted] — сколько находок не вошло сверх [MAX_FINDINGS] (D-05: не
 * молча); [by] и [reason] — у `WAIVED` и `FAILED`.
 */
data class ReviewRecord(
    val role: ReviewRole,
    val verdict: ReviewVerdict,
    val attempt: Int,
    val session: com.dpsmpx.dpai.core.ids.SessionId?,
    val findings: List<ReviewFinding> = emptyList(),
    val omitted: Int = 0,
    val by: String? = null,
    val reason: String? = null,
) {
    init {
        require(attempt >= 1) { "попытка ревью — с 1" }
        require(findings.size <= MAX_FINDINGS && omitted >= 0) { "находок — до $MAX_FINDINGS, остальные — числом" }
        require(verdict != ReviewVerdict.BLOCKED || findings.any { it.severity == FindingSeverity.BLOCKING }) { "BLOCKED — только с блокирующей находкой" }
        require(verdict == ReviewVerdict.BLOCKED || findings.none { it.severity == FindingSeverity.BLOCKING }) { "блокирующая находка — только у BLOCKED" }
        require(verdict != ReviewVerdict.WAIVED || (!by.isNullOrBlank() && !reason.isNullOrBlank())) { "у принятого без ревью — кто и почему" }
        require(reason == null || reason.length <= MAX_DETAIL) { "причина — до $MAX_DETAIL знаков" }
    }

    companion object {
        const val MAX_FINDINGS = 20
        const val MAX_TITLE = 200
        const val MAX_FILE = 300
        const val MAX_DETAIL = 1_000
    }
}

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

    /** Политика задачи изменена человеком до запуска (D-42): класс, модель, усилие, бюджет хода. */
    data class PolicyChanged(override val task: TaskId, val policy: TaskPolicy, override val at: Timestamp) : TaskEvent

    /** Удержание задачи (D-42): `true` — не запускать, `false` — снова в план. */
    data class HoldChanged(override val task: TaskId, val held: Boolean, override val at: Timestamp) : TaskEvent

    /** Счётчики повторов, продолжений и петель сброшены явным повтором человека (D-42). */
    data class CountersReset(override val task: TaskId, override val at: Timestamp) : TaskEvent

    /** Завершённая задача спрятана в архив или возвращена (D-42). */
    data class ArchiveChanged(override val task: TaskId, val archived: Boolean, override val at: Timestamp) : TaskEvent

    /** Итог ревью роли (D-73): прошло, блокирует, не дало итога или принято без ревью. */
    data class ReviewRecorded(override val task: TaskId, val record: ReviewRecord, override val at: Timestamp) : TaskEvent

    /** Человек перенёс задачу со слота (D-77): следующая сессия — на другом. */
    data class SlotExcluded(override val task: TaskId, val slot: AccountId, override val at: Timestamp) : TaskEvent
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
