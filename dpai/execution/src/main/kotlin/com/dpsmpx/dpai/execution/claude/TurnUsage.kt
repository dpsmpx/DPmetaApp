package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import java.math.BigDecimal

/** Расход одной модели в ходе (`modelUsage` итога): токены, окно контекста, стоимость. */
data class ModelTokens(
    val model: String,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheCreationTokens: Long?,
    val contextWindow: Long?,
    val costUsd: BigDecimal?,
)

/**
 * Расход хода со слов провайдера (D-40, п. 1; `docs/research/CLAUDE_CODE_CLI.md`,
 * §11): всё, что есть в итоге, — `OBSERVED`, стоимость по прейскуранту —
 * `ESTIMATED`, чего нет — `Unknown` с причиной, а не ноль (D-13).
 *
 * Токены `usage` — сумма по всем вызовам API хода. [contextTokens] — вход
 * последнего вызова (`usage.iterations`) с кешем: столько контекста было у
 * модели в конце хода; по нему решается, продолжать ли сессию провайдера
 * (`docs/EFFICIENCY.md`, §4). [compacted] — Claude Code сжимал историю в
 * ходе (`system`/`compact_boundary`): прежний prompt мог из неё уйти.
 */
data class TurnUsage(
    val inputTokens: Measure<Long>,
    val outputTokens: Measure<Long>,
    val cacheReadTokens: Measure<Long>,
    val cacheCreationTokens: Measure<Long>,
    val thinkingTokens: Measure<Long>,
    val contextTokens: Measure<Long>,
    val apiCalls: Measure<Long>,
    val numTurns: Measure<Long>,
    val durationMs: Measure<Long>,
    val apiDurationMs: Measure<Long>,
    val subagents: Measure<Long>,
    val costUsd: Measure<BigDecimal>,
    val models: List<ModelTokens>,
    val compacted: Boolean = false,
) {
    /**
     * Для журнала: причины неизвестного сводятся к одной — [NOT_REPORTED];
     * подробная причина нужна разбору, а событие — факт «провайдер не
     * сообщил» (так оно и читается обратно).
     */
    fun recorded(): TurnUsage {
        fun <T> Measure<T>.r(): Measure<T> = if (this is Measure.Unknown) Measure.unknown(NOT_REPORTED) else this
        return copy(
            inputTokens = inputTokens.r(), outputTokens = outputTokens.r(), cacheReadTokens = cacheReadTokens.r(),
            cacheCreationTokens = cacheCreationTokens.r(), thinkingTokens = thinkingTokens.r(), contextTokens = contextTokens.r(),
            apiCalls = apiCalls.r(), numTurns = numTurns.r(), durationMs = durationMs.r(), apiDurationMs = apiDurationMs.r(),
            subagents = subagents.r(), costUsd = costUsd.r(),
        )
    }

    /** Окно контекста: наибольшее среди моделей хода; неизвестно — `null`. */
    val contextWindow: Long? get() = models.mapNotNull { it.contextWindow }.maxOrNull()

    companion object {
        const val NOT_REPORTED = "провайдер не сообщил"

        /** Итога нет (поток оборвался): расход неизвестен, а не нулевой. */
        fun unknown(why: String): TurnUsage {
            val u = Measure.unknown(why)
            return TurnUsage(u, u, u, u, u, u, u, u, u, u, u, u, emptyList())
        }

        /** Расход из события `result` (терпимо: поля добавляются версиями Claude Code). */
        fun fromResult(obj: JsonObject): TurnUsage {
            val usage = obj["usage"] as? JsonObject
            fun tokens(o: JsonObject?, key: String, where: String): Measure<Long> =
                o?.long(key)?.let { Measure.observed(it) } ?: Measure.unknown("нет $where.$key")
            val models = (obj["modelUsage"] as? JsonObject)?.fields.orEmpty().mapNotNull { (name, v) ->
                (v as? JsonObject)?.let {
                    ModelTokens(
                        name, it.long("inputTokens"), it.long("outputTokens"), it.long("cacheReadInputTokens"),
                        it.long("cacheCreationInputTokens"), it.long("contextWindow"), it.decimal("costUSD"),
                    )
                }
            }
            // Стоимость при подписке — расчёт по прейскуранту (costBasis = list), а не списание: оценка (D-13).
            val listBasis = (obj["modelUsage"] as? JsonObject)?.fields?.values.orEmpty()
                .any { (it as? JsonObject)?.str("costBasis") == "list" }
            val cost = obj.decimal("total_cost_usd")?.let { if (listBasis) Measure.estimated(it) else Measure.observed(it) }
                ?: Measure.unknown("нет total_cost_usd")
            val iterations = (usage?.get("iterations") as? JsonArray)?.items?.filterIsInstance<JsonObject>()
            val last = iterations?.lastOrNull()
            val context = last?.let { l ->
                val parts = listOf("input_tokens", "cache_read_input_tokens", "cache_creation_input_tokens").map { l.long(it) }
                if (parts.any { it == null }) null else parts.sumOf { it!! }
            }
            val subagents = (obj["subagent_stats"] as? JsonObject)?.long("spawned")
            return TurnUsage(
                inputTokens = tokens(usage, "input_tokens", "usage"),
                outputTokens = tokens(usage, "output_tokens", "usage"),
                cacheReadTokens = tokens(usage, "cache_read_input_tokens", "usage"),
                cacheCreationTokens = tokens(usage, "cache_creation_input_tokens", "usage"),
                thinkingTokens = tokens(usage?.get("output_tokens_details") as? JsonObject, "thinking_tokens", "usage.output_tokens_details"),
                contextTokens = context?.let { Measure.observed(it) } ?: Measure.unknown("нет usage.iterations"),
                apiCalls = iterations?.let { Measure.observed(it.size.toLong()) } ?: Measure.unknown("нет usage.iterations"),
                numTurns = tokens(obj, "num_turns", "result"),
                durationMs = tokens(obj, "duration_ms", "result"),
                apiDurationMs = tokens(obj, "duration_api_ms", "result"),
                subagents = subagents?.let { Measure.observed(it) } ?: Measure.unknown("нет subagent_stats.spawned"),
                costUsd = cost,
                models = models,
            )
        }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonString)?.value

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonNumber)?.toLongExact()

private fun JsonObject.decimal(key: String): BigDecimal? = (this[key] as? JsonNumber)?.toBigDecimal()
