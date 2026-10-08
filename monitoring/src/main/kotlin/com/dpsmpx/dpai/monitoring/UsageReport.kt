package com.dpsmpx.dpai.monitoring

import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Отчёт расхода (D-40; `docs/EFFICIENCY.md`, §9): из состояния сессий и
 * задач, без отдельного хранилища. Наблюдаемое (со слов провайдера) и
 * оценка оркестратора разделены и так и подписаны; ходы без итога —
 * [unknownTurns]: суммы тогда — нижняя граница (D-13).
 */
data class UsageReport(
    val sessions: Int,
    val turns: Int,
    val unknownTurns: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheCreationTokens: Long,
    val thinkingTokens: Long,
    val apiCalls: Long,
    val subagents: Long,
    val costUsd: BigDecimal,
    val costEstimated: Boolean,
    val models: Map<String, Long>,
    val compactedSessions: Int,
    val prompts: Int,
    val promptTokens: Long,
    val omittedTokens: Long,
    val reusedTokens: Long,
    val modes: Map<PromptMode, Int>,
    val routes: Map<String, Int>,
    val tasksDone: Int,
    val tasksFailed: Int,
    val retries: Int,
    val continuations: Int,
    val topTasks: List<Pair<TaskId, Long>>,
    val expensive: Set<String>,
) {
    /** Вход модели целиком: без кеша, чтение и запись кеша. */
    val totalInput: Long get() = inputTokens + cacheReadTokens + cacheCreationTokens

    /** Доля входа, прочитанная из кеша prompt (наблюдаемое); входа нет — `null`. */
    val cacheReadShare: Double? get() = ratio(cacheReadTokens, totalInput)

    /** Токены (вход и выход) на выполненную задачу — грубая мера «работа на расход»; задач нет — `null`. */
    val tokensPerDoneTask: Long? get() = if (tasksDone == 0) null else (totalInput + outputTokens) / tasksDone

    /** Доля токенов на дорогих моделях (имя содержит одно из [expensive]). */
    val expensiveShare: Double? get() = ratio(models.filterKeys { m -> expensive.any { it in m } }.values.sum(), models.values.sum())

    /**
     * Доля контекста задачи, которую слой не послал (оценка): повтор при
     * `--resume` и не вошедшее по бюджету против всего, что было бы послано.
     */
    val contextSavedShare: Double? get() = ratio(reusedTokens + omittedTokens, promptTokens + reusedTokens + omittedTokens)

    /** Средний prompt хода (оценка). */
    val avgPromptTokens: Long? get() = if (prompts == 0) null else promptTokens / prompts

    fun render(): String = buildString {
        fun pct(v: Double?) = v?.let { "%.1f %%".format(it * 100) } ?: "—"
        appendLine("## Расход модели (наблюдаемое, со слов провайдера)")
        appendLine()
        appendLine("| Мера | Значение |")
        appendLine("|---|---|")
        appendLine("| сессий / ходов | $sessions / $turns" + (if (unknownTurns > 0) " (+$unknownTurns без итога: суммы — нижняя граница)" else "") + " |")
        appendLine("| вызовов API | $apiCalls |")
        appendLine("| вход без кеша / чтение кеша / запись кеша | $inputTokens / $cacheReadTokens / $cacheCreationTokens |")
        appendLine("| доля входа из кеша | ${pct(cacheReadShare)} |")
        appendLine("| выход (из них размышления) | $outputTokens ($thinkingTokens) |")
        appendLine("| субагентов | $subagents |")
        appendLine("| сжатий истории | $compactedSessions |")
        appendLine("| стоимость, USD | ${costUsd.setScale(4, RoundingMode.HALF_UP).toPlainString()}${if (costEstimated) " (оценка по прейскуранту)" else ""} |")
        appendLine("| доля дорогих моделей (${expensive.sorted().joinToString()}) | ${pct(expensiveShare)} |")
        appendLine("| токенов на выполненную задачу | ${tokensPerDoneTask ?: "—"} |")
        appendLine()
        appendLine("## Контекст (оценка оркестратора)")
        appendLine()
        appendLine("| Мера | Значение |")
        appendLine("|---|---|")
        appendLine("| prompt хода, средний | ${avgPromptTokens ?: "—"} |")
        appendLine("| не послано повторно / не вошло по бюджету | $reusedTokens / $omittedTokens |")
        appendLine("| доля контекста, не посланная слоем | ${pct(contextSavedShare)} |")
        appendLine("| режимы | ${modes.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "—" }} |")
        appendLine("| маршруты | ${routes.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "—" }} |")
        appendLine()
        appendLine("## Работа")
        appendLine()
        appendLine("| Мера | Значение |")
        appendLine("|---|---|")
        appendLine("| выполнено / провалено | $tasksDone / $tasksFailed |")
        appendLine("| повторов / продолжений | $retries / $continuations |")
        appendLine("| модели, токенов | ${models.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "—" }} |")
        append("| больше всех расходуют | ${topTasks.joinToString { "${it.first.value} ${it.second}" }.ifEmpty { "—" }} |")
    }

    companion object {
        private fun ratio(a: Long, b: Long): Double? = if (b <= 0) null else a.toDouble() / b

        /** Отчёт по состоянию; [top] — сколько задач с наибольшим расходом назвать. */
        fun build(sessions: SessionsState, tasks: TasksState, expensive: Set<String> = setOf("opus"), top: Int = 5): UsageReport {
            val all = sessions.sessions.values
            val u = all.map { it.usage }
            val byTask = all.groupBy { it.task }.mapValues { (_, ss) -> ss.sumOf { it.usage.inputTokens + it.usage.cacheReadTokens + it.usage.cacheCreationTokens + it.usage.outputTokens } }
            return UsageReport(
                sessions = all.size,
                turns = u.sumOf { it.turns },
                unknownTurns = u.sumOf { it.unknownTurns },
                inputTokens = u.sumOf { it.inputTokens },
                outputTokens = u.sumOf { it.outputTokens },
                cacheReadTokens = u.sumOf { it.cacheReadTokens },
                cacheCreationTokens = u.sumOf { it.cacheCreationTokens },
                thinkingTokens = u.sumOf { it.thinkingTokens },
                apiCalls = u.sumOf { it.apiCalls },
                subagents = u.sumOf { it.subagents },
                costUsd = u.fold(BigDecimal.ZERO) { acc, x -> acc + x.costUsd },
                costEstimated = u.any { it.costEstimated },
                models = u.flatMap { it.models.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() },
                compactedSessions = u.count { it.compacted },
                prompts = u.sumOf { it.prompts },
                promptTokens = u.sumOf { it.promptTokens },
                omittedTokens = u.sumOf { it.omittedTokens },
                reusedTokens = u.sumOf { it.reusedTokens },
                modes = u.mapNotNull { it.lastPrompt?.mode }.groupingBy { it }.eachCount(),
                routes = u.mapNotNull { it.lastPrompt?.route }.groupingBy { it.substringBefore(':') }.eachCount(),
                tasksDone = tasks.tasks.values.count { it.state == TaskState.DONE },
                tasksFailed = tasks.tasks.values.count { it.state == TaskState.FAILED },
                retries = tasks.tasks.values.sumOf { it.retries },
                continuations = tasks.tasks.values.sumOf { it.continuations },
                topTasks = byTask.entries.filter { it.value > 0 }.sortedWith(compareByDescending<Map.Entry<TaskId, Long>> { it.value }.thenBy { it.key.value }).take(top).map { it.key to it.value },
                expensive = expensive,
            )
        }
    }
}
