package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextBundle
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.security.Sensitivity
import com.dpsmpx.dpai.tasks.TasksState

/**
 * Быстрые действия главного экрана (ТЗ, §73). Каждое — те же команды
 * задач, что в её листе (D-42): «Пауза всем» — `task.hold` каждой задаче,
 * которую можно приостановить, «Продолжить все» — `task.resume` каждой
 * приостановленной. «Ждут человека» — задачи, которым нужно решение:
 * повтор, ответ, ручная проверка. Повтор и продолжение после отказа всех
 * разом не делаются: повтор тратит пределы подписки (D-27), решение — по
 * каждой задаче в её листе. Задачи в архиве не трогаются.
 */
data class QuickActions(val holdable: List<TaskId>, val resumable: List<TaskId>, val waiting: List<TaskCard>) {
    /** Команды «Пауза всем»: ключ каждой — [key] и id задачи, повтор нажатия той же серией ничего не удваивает (D-42). */
    fun holdAll(key: String): List<Command> = holdable.map { Command.Hold(it, "$key:${it.value}") }

    fun resumeAll(key: String): List<Command> = resumable.map { Command.Resume(it, "$key:${it.value}") }

    companion object {
        val NONE = QuickActions(emptyList(), emptyList(), emptyList())

        /** Решение человека нужно задаче в этих состояниях: повтор, ответ (`BLOCKED`), ручная проверка. */
        private val WAITING = setOf(TaskState.BLOCKED, TaskState.FAILED, TaskState.COMPLETION_UNVERIFIED)

        fun of(tasks: TasksState): QuickActions {
            val shown = tasks.tasks.values.filter { !it.archived }.sortedBy { it.id.value }
            return QuickActions(
                holdable = shown.filter { TaskAction.HOLD in TaskScreens.actions(it) }.map { it.id },
                resumable = shown.filter { TaskAction.RESUME in TaskScreens.actions(it) }.map { it.id },
                waiting = shown.filter { it.state in WAITING }
                    .sortedWith(compareByDescending<com.dpsmpx.dpai.tasks.TaskRecord> { it.updatedAt.epochMillis }.thenBy { it.id.value })
                    .map { TaskCard(it.id, it.title, it.state.name, Screens.tone(it.state), it.priority.name, emptyList(), 0, it.held) },
            )
        }
    }
}

/** Источник в окне «Контекст»: ключ, уровень, размер и текст — или почему текста нет. */
data class ContextSourceLine(val key: String, val level: String, val chars: Int, val text: String?, val hidden: String?)

/** Окно «Контекст» сессии (ТЗ, §74, View context): что видел Claude в последнем ходе задачи. */
data class ContextView(
    val title: String,
    val objective: String,
    val nextAction: String?,
    val hash: String,
    val sources: List<ContextSourceLine>,
    /** Не вошедшие источники (D-40): ключ и почему. */
    val omitted: List<String>,
)

object ContextScreens {
    /** Больше этого текст источника в окне обрезается — с числом оставшихся знаков (D-05: не молча). */
    const val MAX_CHARS = 4_000

    /**
     * Версия контекста — для показа. Текст источников уровня `SENSITIVE` и
     * `SECRET` не показывается (ТЗ, §65): только ключ и размер; остальное —
     * через [Redactor] (D-14), длинное — обрезано с пометкой.
     */
    fun view(c: ContextBundle): ContextView = ContextView(
        // Последняя собранная версия задачи: её видел последний ход или увидит следующий (после отказа проверки — новая).
        title = "Контекст задачи v${c.version}",
        objective = Redactor.STANDARD.redact(c.objective),
        nextAction = c.nextAction?.let(Redactor.STANDARD::redact),
        hash = c.hash.toString(),
        sources = c.sources.map { s ->
            val secret = s.sensitivity == Sensitivity.SENSITIVE || s.sensitivity == Sensitivity.SECRET
            ContextSourceLine(
                key = s.key,
                level = s.level.name,
                chars = s.content.length,
                text = if (secret) null else clip(Redactor.STANDARD.redact(s.content)),
                hidden = if (secret) "текст скрыт: ${s.sensitivity.name}" else null,
            )
        },
        omitted = c.omitted.map { o -> o.key + (o.sameAs?.let { " — повтор $it" } ?: " — не вошёл по бюджету, ≈${o.estimatedTokens} ток.") },
    )

    private fun clip(text: String): String = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "\n… ещё ${text.length - MAX_CHARS} знаков"
}
