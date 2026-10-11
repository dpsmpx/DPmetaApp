package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.git.TaskChanges
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
)

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

    fun detail(t: TaskRecord, sessions: SessionsState, waiting: String? = null): TaskDetail = TaskDetail(
        id = t.id, title = t.title, objective = t.objective,
        state = t.state.name, tone = Screens.tone(t.state), held = t.held, priority = t.priority.name,
        taskClass = t.policy.taskClass, model = t.policy.model, effort = t.policy.effort, budgetCents = t.policy.maxBudgetUsdCents,
        retries = t.retries, reason = t.lastReason?.takeIf { t.state != TaskState.DONE }?.name,
        lastSession = sessions.sessions.values.filter { it.task == t.id }.maxByOrNull { it.createdAt.epochMillis }?.id,
        actions = actions(t),
        waiting = waiting?.takeIf { t.state == TaskState.READY },
    )
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
 * Изменения задачи для листа задачи (D-42, п. 3) — чистая функция:
 * части [TaskChanges] по порядку («что принесёт слияние», «не
 * закоммичено», «новые файлы»), у строки — вид. На экране — не больше
 * [SCREEN_LINES] строк: длинное целиком — `dpai task <id> diff`.
 */
object TaskChangesScreen {
    const val SCREEN_LINES = 2000

    fun of(c: TaskChanges, warnings: List<String> = emptyList(), limit: Int = SCREEN_LINES): ChangesView {
        val files = { part: List<String>? -> part.orEmpty().count { it.startsWith("diff --git ") } }
        val summary = when {
            c.committed == null && c.uncommitted == null ->
                if (c.base == null) "Показать нечего: у проекта нет ветки интеграции, дерева задачи нет" else "Ветки ${c.branch} ещё нет: задача не начиналась"
            c.empty -> "Изменений нет: в ${c.branch} нет ничего, чего нет в ${c.base ?: "базе"}"
            else -> listOfNotNull(
                c.committed?.let { "слияние в ${c.base}: файлов ${files(it)}" },
                c.uncommitted?.takeIf { it.isNotEmpty() }?.let { "не закоммичено: файлов ${files(it)}" },
                "новых файлов ${c.untracked.size}".takeIf { c.untracked.isNotEmpty() },
            ).joinToString(" · ")
        }
        val all = buildList {
            c.committed?.takeIf { it.isNotEmpty() }?.let {
                add(DiffLine(DiffKind.SECTION, "Что принесёт слияние (${c.base}...${c.branch})"))
                addAll(classify(it))
            }
            c.uncommitted?.takeIf { it.isNotEmpty() }?.let {
                add(DiffLine(DiffKind.SECTION, "Не закоммичено в дереве задачи"))
                addAll(classify(it))
            }
            if (c.untracked.isNotEmpty()) {
                add(DiffLine(DiffKind.SECTION, "Новые файлы, ещё не в Git (${c.untracked.size})"))
                c.untracked.forEach { add(DiffLine(DiffKind.FILE, it)) }
            }
            if (c.truncated) add(DiffLine(DiffKind.NOTE, "Вывод обрезан: часть длиннее ${TaskChanges.MAX_LINES} строк"))
            warnings.forEach { add(DiffLine(DiffKind.NOTE, it)) }
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
