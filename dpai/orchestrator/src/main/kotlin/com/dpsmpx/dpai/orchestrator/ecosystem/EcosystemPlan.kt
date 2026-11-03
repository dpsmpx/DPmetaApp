package com.dpsmpx.dpai.orchestrator.ecosystem

import com.dpsmpx.dpai.ecosystem.EcosystemProject
import com.dpsmpx.dpai.ecosystem.SuggestedTask
import com.dpsmpx.dpai.orchestrator.commands.Command
import com.dpsmpx.dpai.tasks.Priority

/**
 * Предложение экосистемы с решением прогона (ТЗ, §45, §46): можно ли
 * поставить из него задачу и почему нет. [refusal] — причина словами;
 * `null` — можно, но в V1 только с подтверждения человека
 * (`docs/direction/V1_SCOPE.md`, §3).
 */
data class PlannedSuggestion(val project: EcosystemProject, val suggestion: SuggestedTask, val refusal: String?) {
    val allowed: Boolean get() = refusal == null

    /** Ключ команды постановки (D-42): проект и предложение — повторный прогон задачу не дублирует. */
    val key: String get() = EcosystemPlan.key(project.key, suggestion.key)

    /** Команда постановки задачи; предложение не разрешено — `null`. */
    fun command(): Command.AddTask? =
        if (!allowed) null else EcosystemPlan.priority(suggestion)?.let { Command.AddTask(suggestion.title, suggestion.objective, it, key = key) }
}

/**
 * Отбор предложений экосистемы (ТЗ, §45): одна логика для глобального
 * прогона ([EcosystemRun]), команды `dpai ecosystem` и экрана приложения.
 * Порядок детерминирован (D-22): приоритет, затем проект и ключ.
 */
object EcosystemPlan {
    fun key(project: String, suggestion: String): String = "ecosystem:$project/$suggestion"

    fun priority(s: SuggestedTask): Priority? = Priority.entries.firstOrNull { it.name == s.priority }

    val ORDER: Comparator<PlannedSuggestion> = compareBy({ priority(it.suggestion)?.ordinal ?: Int.MAX_VALUE }, { it.project.key }, { it.suggestion.key })

    /**
     * Почему из предложения нельзя поставить задачу: проект без контура в
     * [connected], проект или вид работы вне разрешённых прогону
     * ([allowedProjects], [allowedOperations]; `null` — без ограничения),
     * неизвестный приоритет. `null` — можно.
     */
    fun refusal(p: EcosystemProject, s: SuggestedTask, connected: Set<String>, allowedProjects: Set<String>?, allowedOperations: Set<String>?): String? = when {
        p.key !in connected -> "проект ${p.key} не подключён к контуру"
        allowedProjects != null && p.key !in allowedProjects -> "проект ${p.key} не разрешён прогону"
        allowedOperations != null && s.operation !in allowedOperations -> "вид работы ${s.operation} не разрешён прогону"
        priority(s) == null -> "неизвестный приоритет ${s.priority}"
        else -> null
    }

    /** Все предложения проектов с решением, в порядке [ORDER]. */
    fun of(
        projects: List<EcosystemProject>,
        connected: Set<String>,
        allowedProjects: Set<String>? = null,
        allowedOperations: Set<String>? = null,
    ): List<PlannedSuggestion> = projects.flatMap { p ->
        p.suggestions.map { s -> PlannedSuggestion(p, s, refusal(p, s, connected, allowedProjects, allowedOperations)) }
    }.sortedWith(ORDER)
}
