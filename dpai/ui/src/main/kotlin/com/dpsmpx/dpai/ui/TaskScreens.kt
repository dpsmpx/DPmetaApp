package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.core.text.Words
import com.dpsmpx.dpai.orchestrator.git.TaskDiff
import com.dpsmpx.dpai.orchestrator.review.Review
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.FindingSeverity
import com.dpsmpx.dpai.tasks.ReviewRecord
import com.dpsmpx.dpai.tasks.ReviewRole
import com.dpsmpx.dpai.tasks.ReviewVerdict
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskRecord

/**
 * Действие человека с задачей (D-42, п. 2); [dangerous] — только после
 * явного подтверждения (ТЗ, §68): отмена теряет несохранённую работу хода,
 * исключение записывает «готово» без проверки.
 */
enum class TaskAction(val label: String, val dangerous: Boolean = false) {
    PRIORITY("Приоритет"),
    /** Выше всех в очереди и без паузы (D-77; ТЗ, §73, Run Task); пределы подписки — как у всех. */
    RUN_FIRST("Запустить первой"),
    POLICY("Модель, бюджет, ревью"),
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
    /** Кто выполняет задачу сейчас (D-56): слот — target и подписка; идущей сессии нет — `null`. */
    val executor: String? = null,
    /** Исполнитель задачи по политике (D-100): от него варианты класса, модели и усилия (AI:A-10). */
    val taskExecutor: com.dpsmpx.dpai.core.ids.Executor = com.dpsmpx.dpai.core.ids.Executor.CLAUDE,
    /** Кому и когда задача передавалась (D-56): по сессии на строку, с причиной перехода между подписками. */
    val assignments: List<String> = emptyList(),
    /** Роли ревью задачи (D-73) — из политики — и итог каждой; без ревью — пусто. */
    val reviews: List<ReviewLine> = emptyList(),
)

/** Роль ревью в листе задачи (D-73): итог словами, находки — блокирующие первыми, пометка (кто решил, почему без итога). */
data class ReviewLine(val role: ReviewRole, val label: String, val verdict: String, val tone: Tone, val findings: List<FindingLine>, val note: String?)

/** Находка ревью; [where] — «файл:строка», если ревьюер их назвал. */
data class FindingLine(val severity: FindingSeverity, val title: String, val where: String?, val detail: String?)

/** Строка зависимости в листе задачи. */
data class DependencyLine(val id: TaskId, val title: String, val state: String)

/**
 * Какие действия доступны задаче (D-42) — по её состоянию; оркестратор
 * всё равно проверяет каждую команду сам, экран лишь не предлагает
 * заведомо отклонённое. Чистая функция (D-22).
 */
object TaskScreens {
    private val EDITABLE = setOf(TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY, TaskState.WAITING, TaskState.BLOCKED, TaskState.FAILED, TaskState.CANCELLED)
    private val WAITS_TO_RUN = setOf(TaskState.QUEUED, TaskState.READY, TaskState.WAITING)
    private val HOLDABLE = setOf(TaskState.BACKLOG, TaskState.QUEUED, TaskState.READY, TaskState.RUNNING, TaskState.WAITING, TaskState.BLOCKED)

    /**
     * Главные действия листа задачи (AI:A-9): одно-два по состоянию —
     * заливными; остальные [TaskDetail.actions] — в ⋮. Пауза — сначала
     * «Продолжить»; ждёт ответа — «Ответить и повторить»; без проверки —
     * «Проверить снова»; в очереди — «Запустить первой»; упала или
     * отменена — «Повторить».
     */
    fun primary(d: TaskDetail): List<TaskAction> {
        val want = buildList {
            if (d.held) add(TaskAction.RESUME)
            when (d.state) {
                TaskState.BLOCKED.name -> add(TaskAction.REPLY)
                TaskState.COMPLETION_UNVERIFIED.name -> add(TaskAction.VERIFY)
                TaskState.FAILED.name, TaskState.CANCELLED.name -> add(TaskAction.RETRY)
                in WAITS_TO_RUN.map { it.name } -> add(TaskAction.RUN_FIRST)
            }
        }
        return want.filter { it in d.actions }.take(2)
    }

    fun actions(t: TaskRecord): List<TaskAction> = buildList {
        if (!t.terminal) add(TaskAction.PRIORITY)
        if (t.state in WAITS_TO_RUN) add(TaskAction.RUN_FIRST)
        if (t.state in EDITABLE) add(TaskAction.POLICY)
        if (t.state in EDITABLE && !t.terminal) add(TaskAction.SCOPE)
        // Завершённую не отпускают и не удерживают (`Tasks.hold`): у отменённой на паузе «Продолжить» был бы отказом.
        if (t.held) {
            if (!t.terminal) add(TaskAction.RESUME)
        } else if (t.state in HOLDABLE) {
            add(TaskAction.HOLD)
        }
        if (t.state == TaskState.FAILED || t.state == TaskState.CANCELLED || t.state == TaskState.BLOCKED) add(TaskAction.RETRY)
        if (t.state == TaskState.BLOCKED) add(TaskAction.REPLY)
        if (t.state == TaskState.COMPLETION_UNVERIFIED) {
            add(TaskAction.VERIFY)
            add(TaskAction.WAIVE)
        }
        if (!t.terminal && t.state != TaskState.VERIFIED) add(TaskAction.CANCEL)
        if (t.terminal) add(if (t.archived) TaskAction.UNARCHIVE else TaskAction.ARCHIVE)
    }

    /**
     * Имя слота для человека (D-56): target и подписка — «Termux (claude-slot-1) ·
     * подписка pro»; неизвестный слот — его target.
     */
    fun slotName(accounts: com.dpsmpx.dpai.accounts.AccountsState?, s: com.dpsmpx.dpai.sessions.SessionRecord, targetLabel: (com.dpsmpx.dpai.core.ids.ExecutionTargetId) -> String): String =
        targetLabel(s.target) + (accounts?.subscriptions?.get(s.subscription)?.let { " · ${it.plan ?: it.label}" } ?: "")

    /**
     * Почему задача ушла с прежней сессии (D-56): по её handoff — предел
     * подписки так и назван; handoff нет — по причине сессии.
     */
    private fun why(prev: com.dpsmpx.dpai.sessions.SessionRecord, handoffs: List<com.dpsmpx.dpai.contexts.Handoff>): String? {
        val by = handoffs.lastOrNull { it.session == prev.id }?.reason
        val reason = prev.lastReason
        return when {
            by == com.dpsmpx.dpai.contexts.HandoffReason.ACCOUNT_EXHAUSTED -> "предел подписки"
            by == com.dpsmpx.dpai.contexts.HandoffReason.NEAR_LIMIT -> "подписка у предела"
            by == com.dpsmpx.dpai.contexts.HandoffReason.CONTEXT_OVERFLOW -> "переполнение контекста"
            by == com.dpsmpx.dpai.contexts.HandoffReason.TARGET_FAILED -> "target недоступен"
            reason == com.dpsmpx.dpai.core.diagnostics.DiagCode.CLAUDE_RATE_LIMITED -> "предел подписки"
            reason == null || reason == com.dpsmpx.dpai.core.diagnostics.DiagCode.SESSION_HANDED_OFF -> null
            // Словами, а не кодом (D-88, U-2): «предел подписки», а не `CLAUDE_RATE_LIMITED`.
            else -> Words.reason(reason.name)
        }
    }

    /** Строки «кому и когда» (D-56): время начала сессии, слот, её состояние; смена подписки — «передана: причина». */
    fun assignments(
        task: TaskId,
        sessions: SessionsState,
        accounts: com.dpsmpx.dpai.accounts.AccountsState?,
        targetLabel: (com.dpsmpx.dpai.core.ids.ExecutionTargetId) -> String,
        handoffs: List<com.dpsmpx.dpai.contexts.Handoff> = emptyList(),
        zone: java.time.ZoneId = com.dpsmpx.dpai.core.clock.DisplayZone.current,
    ): List<String> {
        val own = sessions.sessions.values.filter { it.task == task }.sortedWith(compareBy({ it.createdAt.epochMillis }, { it.id.value }))
        return own.mapIndexed { i, s ->
            val at = java.time.Instant.ofEpochMilli(s.createdAt.epochMillis).atZone(zone)
            val time = "%02d.%02d %02d:%02d".format(at.dayOfMonth, at.monthValue, at.hour, at.minute)
            val prev = own.getOrNull(i - 1)
            val handed = when {
                prev == null -> "начата"
                prev.subscription != s.subscription -> "передана другой подписке" + (why(prev, handoffs)?.let { ": $it" } ?: "")
                prev.slot != s.slot -> "передана другому слоту" + (why(prev, handoffs)?.let { ": $it" } ?: "")
                else -> "продолжена"
            }
            "$time — ${slotName(accounts, s, targetLabel)} — $handed · ${Words.session(s.state.name)}"
        }
    }

    /**
     * Кто чем занят (D-56): по target — его идущие сессии: задача, проект
     * (каталог репозитория) и с какого времени; target без сессий — нет в
     * ответе (свободен).
     */
    fun work(
        sessions: SessionsState,
        tasks: com.dpsmpx.dpai.tasks.TasksState,
        project: String?,
        zone: java.time.ZoneId = com.dpsmpx.dpai.core.clock.DisplayZone.current,
    ): Map<com.dpsmpx.dpai.core.ids.ExecutionTargetId, List<String>> =
        sessions.sessions.values.filter { !it.terminal }.sortedWith(compareBy({ it.createdAt.epochMillis }, { it.id.value }))
            .groupBy { it.target }
            .mapValues { (_, list) ->
                list.map { s ->
                    val at = java.time.Instant.ofEpochMilli(s.createdAt.epochMillis).atZone(zone)
                    val title = tasks.tasks[s.task]?.title ?: s.task.value
                    "«$title»" + (project?.let { " · проект $it" } ?: "") + " · с %02d:%02d".format(at.hour, at.minute)
                }
            }

    fun detail(
        t: TaskRecord,
        sessions: SessionsState,
        waiting: String? = null,
        accounts: com.dpsmpx.dpai.accounts.AccountsState? = null,
        targetLabel: (com.dpsmpx.dpai.core.ids.ExecutionTargetId) -> String = { it.value },
        handoffs: List<com.dpsmpx.dpai.contexts.Handoff> = emptyList(),
        tasks: Map<TaskId, TaskRecord> = emptyMap(),
    ): TaskDetail = TaskDetail(
        id = t.id, title = t.title, objective = t.objective,
        state = t.state.name, tone = Screens.tone(t.state), held = t.held, priority = t.priority.name,
        taskClass = t.policy.taskClass, model = t.policy.model, effort = t.policy.effort, budgetCents = t.policy.maxBudgetUsdCents,
        taskExecutor = t.policy.executor,
        retries = t.retries, reason = t.lastReason?.takeIf { t.state != TaskState.DONE }?.let { Words.reason(it.name) },
        lastSession = sessions.sessions.values.filter { it.task == t.id }.maxByOrNull { it.createdAt.epochMillis }?.id,
        actions = actions(t),
        waiting = waiting?.takeIf { t.state == TaskState.READY },
        scope = t.scope,
        dependencies = t.dependencies.map { d -> DependencyLine(d, tasks[d]?.title ?: d.value, tasks[d]?.state?.name ?: "?") }.sortedBy { it.title },
        usage = UsageText.of(com.dpsmpx.dpai.sessions.UsageSummary.of(sessions.sessions.values.filter { it.task == t.id }.map { it.usage })),
        executor = sessions.sessions.values.filter { it.task == t.id && !it.terminal }.maxByOrNull { it.createdAt.epochMillis }
            ?.let { slotName(accounts, it, targetLabel) },
        assignments = assignments(t.id, sessions, accounts, targetLabel, handoffs),
        reviews = t.policy.reviews.sorted().map { review(it, t.reviews[it]) },
    )

    /** Строка роли ревью: итога ещё нет — «ещё нет»; итог прошлой попытки работы сбрасывается её запуском. */
    fun review(role: ReviewRole, r: ReviewRecord?): ReviewLine {
        val (verdict, tone) = when (r?.verdict) {
            null -> "ещё нет" to Tone.NEUTRAL
            ReviewVerdict.PASSED -> "пройдено" to Tone.DONE
            ReviewVerdict.BLOCKED -> "нужно исправить" to Tone.WARNING
            ReviewVerdict.FAILED -> "без итога" to Tone.ERROR
            ReviewVerdict.WAIVED -> "принято без ревью" to Tone.WARNING
        }
        val note = listOfNotNull(
            r?.by?.let { "решил: $it" },
            r?.reason,
            r?.omitted?.takeIf { it > 0 }?.let { "не показано находок: $it" },
        ).joinToString(" · ").ifEmpty { null }
        val findings = r?.findings.orEmpty().map { f ->
            FindingLine(f.severity, f.title, listOfNotNull(f.file, f.line?.toString()).joinToString(":").ifEmpty { null }, f.detail)
        }
        return ReviewLine(role, Review.label(role), verdict, tone, findings, note)
    }

    /**
     * Задачи, от которых можно зависеть (D-42, п. 1): не завершённые, кроме
     * самой [except]; по названию. Оркестратор цикл всё равно отвергнет.
     */
    fun dependencyChoices(tasks: Map<TaskId, TaskRecord>, except: TaskId? = null): List<DependencyLine> =
        tasks.values.filter { !it.terminal && !it.archived && it.id != except }
            .map { DependencyLine(it.id, it.title, it.state.name) }
            .sortedBy { it.title }
}

/** Вид строки изменений — для цвета на экране. */
enum class DiffKind { SECTION, STAT, FILE, HUNK, ADDED, REMOVED, CONTEXT, NOTE }

data class DiffLine(val kind: DiffKind, val text: String)

/** Изменения задачи на экране (D-42, п. 3): одна строка сути и строки вывода Git по частям. */
data class ChangesView(val summary: String, val lines: List<DiffLine>) {
    /** Текст для буфера обмена — как в `dpai task <id> diff`. */
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Изменения задачи для листа задачи (D-42, п. 3) — чистая функция над
 * [TaskDiff] (D-72): суть одной строкой, затем по файлам (`--numstat`),
 * новые файлы вне индекса и текст изменений, у строки — вид. На экране —
 * не больше [SCREEN_LINES] строк: длинное целиком — `dpai task <id> diff`.
 */
object TaskChangesScreen {
    const val SCREEN_LINES = 2000

    fun of(d: TaskDiff, limit: Int = SCREEN_LINES): ChangesView {
        val summary = if (d.files.isEmpty() && d.untracked.isEmpty()) {
            "Изменений нет: от ${d.base} до «${d.head}»"
        } else {
            listOfNotNull("файлов ${d.files.size}", "новых ${d.untracked.size}".takeIf { d.untracked.isNotEmpty() }, "от ${d.base} до «${d.head}»").joinToString(" · ")
        }
        val all = buildList {
            if (d.files.isNotEmpty()) {
                add(DiffLine(DiffKind.SECTION, "Файлы (от ${d.base}, ${d.mergeBase.take(10)})"))
                d.files.forEach { f -> add(DiffLine(DiffKind.STAT, "+${f.added ?: "?"} −${f.removed ?: "?"}  ${f.path}")) }
            }
            if (d.untracked.isNotEmpty()) {
                add(DiffLine(DiffKind.SECTION, "Новые файлы, ещё не в Git (${d.untracked.size})"))
                d.untracked.forEach { add(DiffLine(DiffKind.FILE, it)) }
            }
            if (d.patch.isNotEmpty()) {
                add(DiffLine(DiffKind.SECTION, "Изменения"))
                addAll(classify(d.patch.split('\n')))
            }
            if (d.truncated) add(DiffLine(DiffKind.NOTE, "Текст обрезан: больше ${TaskDiff.MAX_CHARS} знаков"))
        }
        val lines = if (all.size <= limit) all else all.take(limit) + DiffLine(DiffKind.NOTE, "На экране — первые $limit строк из ${all.size}; целиком — dpai task <id> diff")
        return ChangesView(summary, lines)
    }

    /**
     * Виды строк одной части: до первого `diff --git` — сводка `--stat`;
     * от `diff --git` до первого `@@` — заголовок файла (там `---` и `+++`
     * — имена, а не строки); дальше — по первому знаку.
     */
    fun classify(part: List<String>): List<DiffLine> {
        var seenFile = false
        var header = false
        return part.map { s ->
            val kind = when {
                s.startsWith("diff --git ") -> DiffKind.FILE.also { seenFile = true; header = true }
                !seenFile -> DiffKind.STAT
                header && !s.startsWith("@@") -> DiffKind.FILE
                s.startsWith("@@") -> DiffKind.HUNK.also { header = false }
                s.startsWith("+") -> DiffKind.ADDED
                s.startsWith("-") -> DiffKind.REMOVED
                s.startsWith("\\") -> DiffKind.NOTE
                else -> DiffKind.CONTEXT
            }
            DiffLine(kind, s)
        }
    }
}
