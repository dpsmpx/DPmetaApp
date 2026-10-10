package com.dpsmpx.dpai.monitoring

import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.execution.claude.TurnTools
import com.dpsmpx.dpai.sessions.PromptMode
import com.dpsmpx.dpai.sessions.ToolTotals
import com.dpsmpx.dpai.sessions.SessionsState
import com.dpsmpx.dpai.tasks.TasksState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Отчёт расхода (D-40; `docs/EFFICIENCY.md`, §9): из состояния сессий и
 * задач, без отдельного хранилища. Наблюдаемое (со слов провайдера) и
 * оценка оркестратора разделены и так и подписаны; ходы без итога —
 * [unknownTurns]: суммы тогда — нижняя граница (D-13).
 *
 * D-41: [tools] — замер инструментов по потоку (байты точные, токены —
 * оценка); [toolCallsByMode] — вызовов инструментов на сессию по режиму
 * prompt: так видно, сколько исследования экономит рабочий набор
 * контрольной точки. [contextTokensAtEnd] — контекст в конце последнего
 * хода сессий (наблюдаемое).
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
    val tools: ToolTotals = ToolTotals.EMPTY,
    val toolCallsByMode: Map<PromptMode, Double> = emptyMap(),
    val contextTokensAtEnd: List<Long> = emptyList(),
    val durationMs: Long = 0,
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

    /** Вывод инструментов в контексте ходов, оценка в токенах (байты точные). */
    val toolTokens: Long get() = TurnTools.tokens(tools.total.resultBytes)

    /** Доля вывода инструментов, полученная повторными вызовами без изменений между ними (нижняя граница). */
    val duplicateShare: Double? get() = ratio(tools.total.duplicateBytes, tools.total.resultBytes)

    /**
     * Сколько входа модели — вывод инструментов: оценка против
     * наблюдаемого. Каждый вызов API перечитывает историю, поэтому это
     * доля впервые пришедшего, а не всего прочитанного.
     */
    val toolShareOfInput: Double? get() = ratio(toolTokens, totalInput)

    /** Средний контекст в конце хода (наблюдаемое). */
    val avgContextTokens: Long? get() = if (contextTokensAtEnd.isEmpty()) null else contextTokensAtEnd.sum() / contextTokensAtEnd.size

    /** Доля выполненных среди завершённых задач. */
    val successShare: Double? get() = ratio(tasksDone.toLong(), (tasksDone + tasksFailed).toLong())

    /** Средняя длительность хода, мс (наблюдаемое). */
    val avgTurnMs: Long? get() = if (turns == 0) null else durationMs / turns

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
        appendLine("| выполненных среди завершённых | ${pct(successShare)} |")
        appendLine("| средний ход, с | ${avgTurnMs?.let { "%.1f".format(it / 1000.0) } ?: "—"} |")
        appendLine("| средний контекст в конце хода | ${avgContextTokens ?: "—"} |")
        appendLine("| модели, токенов | ${models.entries.sortedByDescending { it.value }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "—" }} |")
        append("| больше всех расходуют | ${topTasks.joinToString { "${it.first.value} ${it.second}" }.ifEmpty { "—" }} |")
        if (tools.turns > 0) {
            val t = tools.total
            appendLine()
            appendLine()
            appendLine("## Инструменты в ходе (замер оркестратора по потоку)")
            appendLine()
            appendLine("| Мера | Значение |")
            appendLine("|---|---|")
            appendLine("| ходов с замером / вызовов / с ошибкой | ${tools.turns} / ${t.calls} / ${t.errors} |")
            appendLine("| вывод в контекст, байт (≈токенов, оценка) | ${t.resultBytes} (≈$toolTokens) |")
            appendLine("| доля входа модели — вывод инструментов (оценка) | ${pct(toolShareOfInput)} |")
            appendLine("| повторов без изменений между вызовами (нижняя граница) / их вывод, байт | ${t.duplicates} / ${t.duplicateBytes} (${pct(duplicateShare)}) |")
            appendLine("| файлов прочитано / изменено (по ходам) | ${tools.filesRead} / ${tools.filesEdited} |")
            appendLine("| наибольший результат, байт | ${tools.largestResultBytes} |")
            appendLine("| субагенты: вызовов / вывод, байт | ${tools.subagentCalls} / ${tools.subagentResultBytes} |")
            appendLine("| больше всего вывода | ${topTools().joinToString { "${it.key} ${it.value.calls}× ${it.value.resultBytes} Б" }} |")
            append("| вызовов на сессию по режиму prompt | ${toolCallsByMode.entries.sortedBy { it.key }.joinToString { "${it.key} %.1f".format(it.value) }.ifEmpty { "—" }} |")
            if (tools.unmatchedResults > 0) append("\n\nРезультатов без известного вызова: ${tools.unmatchedResults} — суммы инструментов — нижняя граница.")
        }
    }

    /** Инструменты с наибольшим выводом в контекст. */
    fun topTools(n: Int = 5): List<Map.Entry<String, ToolCount>> =
        tools.byTool.entries.sortedWith(compareByDescending<Map.Entry<String, ToolCount>> { it.value.resultBytes }.thenBy { it.key }).take(n)

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
                tools = all.fold(ToolTotals.EMPTY) { acc, r -> acc.merge(r.usage.tools) },
                toolCallsByMode = all.filter { it.usage.tools.turns > 0 && it.usage.lastPrompt != null }
                    .groupBy { it.usage.lastPrompt!!.mode }
                    .mapValues { (_, ss) -> ss.sumOf { it.usage.tools.total.calls }.toDouble() / ss.size },
                contextTokensAtEnd = u.mapNotNull { it.lastContextTokens },
                durationMs = u.sumOf { it.durationMs },
            )
        }
    }
}

/** Сумма замеров двух наборов сессий (`turns` складываются). */
private fun ToolTotals.merge(o: ToolTotals): ToolTotals = ToolTotals(
    turns = turns + o.turns,
    byTool = o.byTool.entries.fold(byTool) { acc, (k, v) -> acc + (k to (acc[k] ?: ToolCount.ZERO) + v) },
    subagentCalls = subagentCalls + o.subagentCalls,
    subagentResultBytes = subagentResultBytes + o.subagentResultBytes,
    filesRead = filesRead + o.filesRead,
    filesEdited = filesEdited + o.filesEdited,
    largestResultBytes = maxOf(largestResultBytes, o.largestResultBytes),
    unmatchedResults = unmatchedResults + o.unmatchedResults,
)
