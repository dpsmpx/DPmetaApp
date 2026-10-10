package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.measure.MeasureSource
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.execution.claude.TurnTools
import com.dpsmpx.dpai.execution.claude.TurnUsage
import java.math.BigDecimal

/**
 * Как ушёл prompt хода (D-40, п. 4): `FULL` — весь контекст; `DELTA` —
 * продолжение `--resume` только с изменившимися источниками; `CHECKPOINT`
 * — свежая сессия провайдера с handoff вместо `--resume`.
 */
enum class PromptMode { FULL, DELTA, CHECKPOINT }

/**
 * Запрос хода глазами оркестратора (D-40, п. 1): размеры — оценка
 * (`ESTIMATED`, `docs/EFFICIENCY.md`, §2), а не счёт провайдера.
 * [omittedTokens] — не вошло по бюджету; [reusedTokens] — не послано
 * повторно, потому что уже есть в сессии провайдера. Маршрут: класс задачи,
 * модель и усилие (`null` — по умолчанию аккаунта) и почему ([route]);
 * [why] — почему такой режим (контрольная точка, разница, полный).
 */
data class PromptFacts(
    val mode: PromptMode,
    val chars: Long,
    val estimatedTokens: Long,
    val sources: Int,
    val omittedSources: Int = 0,
    val omittedTokens: Long = 0,
    val reusedTokens: Long = 0,
    val context: String? = null,
    val taskClass: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val route: String? = null,
    val why: String? = null,
) {
    init {
        require(chars >= 0 && estimatedTokens >= 0 && sources >= 0 && omittedSources >= 0 && omittedTokens >= 0 && reusedTokens >= 0) { "размеры — не отрицательные" }
    }
}

/**
 * Инструменты сессии (D-41): суммы замеров её ходов ([TurnTools]).
 * [turns] — ходов с замером; [largestResultBytes] — наибольший результат.
 */
data class ToolTotals(
    val turns: Int = 0,
    val byTool: Map<String, ToolCount> = emptyMap(),
    val subagentCalls: Long = 0,
    val subagentResultBytes: Long = 0,
    val filesRead: Long = 0,
    val filesEdited: Long = 0,
    val largestResultBytes: Long = 0,
    val unmatchedResults: Long = 0,
) {
    val total: ToolCount get() = byTool.values.fold(ToolCount.ZERO, ToolCount::plus)

    fun plus(t: TurnTools): ToolTotals = ToolTotals(
        turns = turns + 1,
        byTool = t.byTool.entries.fold(byTool) { acc, (k, v) -> acc + (k to (acc[k] ?: ToolCount.ZERO) + v) },
        subagentCalls = subagentCalls + t.subagentCalls,
        subagentResultBytes = subagentResultBytes + t.subagentResultBytes,
        filesRead = filesRead + t.filesRead,
        filesEdited = filesEdited + t.filesEdited,
        largestResultBytes = maxOf(largestResultBytes, t.largestResultBytes),
        unmatchedResults = unmatchedResults + t.unmatchedResults,
    )

    companion object {
        val EMPTY = ToolTotals()
    }
}

/**
 * Накопленный расход сессии оркестратора (D-40): суммы по ходам, о которых
 * провайдер сообщил расход. [unknownTurns] — ходы без итога: суммы тогда —
 * нижняя граница, и отчёт так и говорит (D-13). [models] — токены по
 * моделям (вход с кешем и выход).
 */
data class UsageTotals(
    val turns: Int = 0,
    val unknownTurns: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val thinkingTokens: Long = 0,
    val apiCalls: Long = 0,
    val subagents: Long = 0,
    val durationMs: Long = 0,
    val costUsd: BigDecimal = BigDecimal.ZERO,
    val costEstimated: Boolean = false,
    val models: Map<String, Long> = emptyMap(),
    val lastContextTokens: Long? = null,
    val contextWindow: Long? = null,
    val compacted: Boolean = false,
    val prompts: Int = 0,
    val promptTokens: Long = 0,
    val omittedTokens: Long = 0,
    val reusedTokens: Long = 0,
    val lastPrompt: PromptFacts? = null,
    /** Инструменты ходов — замер оркестратора (D-41), отдельно от слов провайдера. */
    val tools: ToolTotals = ToolTotals.EMPTY,
) {
    /** Свёртка расхода хода: известное — в суммы, неизвестного хода — счётчик. */
    fun plus(u: TurnUsage): UsageTotals {
        fun Measure<Long>.v(): Long = orNull() ?: 0
        if (u.inputTokens is Measure.Unknown && u.outputTokens is Measure.Unknown) {
            return copy(unknownTurns = unknownTurns + 1, compacted = compacted || u.compacted)
        }
        val cost = u.costUsd as? Measure.Known
        return copy(
            turns = turns + 1,
            inputTokens = inputTokens + u.inputTokens.v(),
            outputTokens = outputTokens + u.outputTokens.v(),
            cacheReadTokens = cacheReadTokens + u.cacheReadTokens.v(),
            cacheCreationTokens = cacheCreationTokens + u.cacheCreationTokens.v(),
            thinkingTokens = thinkingTokens + u.thinkingTokens.v(),
            apiCalls = apiCalls + u.apiCalls.v(),
            subagents = subagents + u.subagents.v(),
            durationMs = durationMs + u.durationMs.v(),
            costUsd = costUsd + (cost?.value ?: BigDecimal.ZERO),
            costEstimated = costEstimated || cost?.source == MeasureSource.ESTIMATED,
            models = u.models.fold(models) { acc, m ->
                val t = listOfNotNull(m.inputTokens, m.outputTokens, m.cacheReadTokens, m.cacheCreationTokens).sum()
                acc + (m.model to (acc[m.model] ?: 0) + t)
            },
            lastContextTokens = u.contextTokens.orNull() ?: lastContextTokens,
            contextWindow = u.contextWindow ?: contextWindow,
            compacted = compacted || u.compacted,
        )
    }

    fun plus(t: TurnTools): UsageTotals = copy(tools = tools.plus(t))

    fun plus(p: PromptFacts): UsageTotals = copy(
        prompts = prompts + 1,
        promptTokens = promptTokens + p.estimatedTokens,
        omittedTokens = omittedTokens + p.omittedTokens,
        reusedTokens = reusedTokens + p.reusedTokens,
        lastPrompt = p,
    )

    companion object {
        val EMPTY = UsageTotals()
    }
}
