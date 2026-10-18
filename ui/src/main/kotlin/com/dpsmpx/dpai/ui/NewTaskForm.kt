package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskClass
import com.dpsmpx.dpai.tasks.TaskPolicy

/**
 * Окно «Новая задача» (D-42, п. 1): всё, что задаётся при создании, —
 * приоритет, класс, модель и усилие, предел стоимости хода, область и
 * зависимости. Поля — текст как ввёл человек; разбор здесь, а не в
 * оболочке Android (D-22): пустое — «по умолчанию», неверное — отказ с
 * объяснением, а не тихая замена.
 */
object NewTaskForm {
    fun command(
        title: String,
        objective: String,
        key: String,
        priority: Priority = Priority.NORMAL,
        taskClass: TaskClass = TaskClass.NORMAL_ENGINEERING,
        model: String = "",
        effort: String = "",
        budget: String = "",
        scope: String = "",
        dependencies: Set<TaskId> = emptySet(),
    ): Outcome<Command.AddTask> {
        if (title.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "нужно название", "title")
        if (objective.isBlank()) return Outcome.failure(DiagCode.VALUE_INVALID, "нужно «Что сделать»", "objective")
        val cents = when (val b = budgetCents(budget)) {
            is Outcome.Failure -> return b
            is Outcome.Success -> b.value
        }
        val paths = scopePaths(scope)
        // Область — тем же правилом, что у оркестратора: ошибку видно до «Поставить», с самим путём.
        paths.firstOrNull { !com.dpsmpx.dpai.tasks.Tasks.validScopePath(it) }?.let {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь области «$it»: относительный внутри репозитория, латиница, цифры и . _ @ + -", "scope")
        }
        val policy = try {
            TaskPolicy(taskClass = taskClass, model = model.trim().ifEmpty { null }, effort = effort.trim().ifEmpty { null }, maxBudgetUsdCents = cents)
        } catch (e: IllegalArgumentException) {
            return Outcome.failure(DiagCode.VALUE_INVALID, e.message ?: "политика недопустима", "policy")
        }
        return Outcome.success(Command.AddTask(title.trim(), objective.trim(), priority, policy, paths, dependencies, key))
    }

    /**
     * Предел стоимости хода в долларах («0,25» или «0.25») → центы; пусто —
     * без предела. Дробь цента и переполнение — отказ, а не тихое
     * округление (D-05, ревью 18 октября 2026).
     */
    fun budgetCents(text: String): Outcome<Long?> {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Outcome.success(null)
        val cents = try {
            t.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.UNNECESSARY)?.longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
        return if (cents == null || cents <= 0) Outcome.failure(DiagCode.VALUE_INVALID, "бюджет — положительное число долларов, до цента", "budget") else Outcome.success(cents)
    }

    /** Область: пути через запятую или с новой строки; пустые отбрасываются. Правило путей — `Tasks.validScopePath` (ТЗ, §31). */
    fun scopePaths(text: String): Set<String> = text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).toSet()
}
