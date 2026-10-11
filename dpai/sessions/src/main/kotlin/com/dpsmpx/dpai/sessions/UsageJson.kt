package com.dpsmpx.dpai.sessions

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.measure.MeasureSource
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.claude.ModelTokens
import com.dpsmpx.dpai.execution.claude.ToolCount
import com.dpsmpx.dpai.execution.claude.TurnTools
import com.dpsmpx.dpai.execution.claude.TurnUsage
import java.math.BigDecimal

/**
 * Расход в событиях и снимке сессий (`docs/DATA_MODEL.md`, §4; D-40).
 * Неизвестное значение не пишется (а не пишется нулём): при чтении оно
 * снова `Unknown` (D-13). Чтение строгое, как у всего кодека (D-09).
 */
internal object UsageJson {
    private const val NOT_RECORDED = TurnUsage.NOT_REPORTED

    fun prompt(p: PromptFacts): JsonObject = obj(
        "mode" to JsonString(p.mode.name), "chars" to JsonNumber.of(p.chars), "estimatedTokens" to JsonNumber.of(p.estimatedTokens),
        "sources" to JsonNumber.of(p.sources), "omittedSources" to JsonNumber.of(p.omittedSources),
        "omittedTokens" to JsonNumber.of(p.omittedTokens), "reusedTokens" to JsonNumber.of(p.reusedTokens),
        "context" to str(p.context), "taskClass" to str(p.taskClass), "model" to str(p.model), "effort" to str(p.effort), "route" to str(p.route), "why" to str(p.why),
    )

    /** Поля [prompt] в [f]; закрывает чтение полей вызывающий. */
    fun readPrompt(f: JsonFields): PromptFacts? {
        val mode = f.string("mode")?.let { m -> PromptMode.entries.firstOrNull { it.name == m } ?: null.also { f.diagnostics.error(DiagCode.VALUE_INVALID, "неизвестный режим prompt", "${f.path}.mode") } }
        val chars = f.long("chars")
        val tokens = f.long("estimatedTokens")
        val sources = f.int("sources")
        val omittedSources = f.int("omittedSources")
        val omittedTokens = f.long("omittedTokens")
        val reusedTokens = f.long("reusedTokens")
        val context = f.optString("context")
        val taskClass = f.optString("taskClass")
        val model = f.optString("model")
        val effort = f.optString("effort")
        val route = f.optString("route")
        val why = f.optString("why")
        if (mode == null || chars == null || tokens == null || sources == null || omittedSources == null || omittedTokens == null || reusedTokens == null) return null
        return runCatching { PromptFacts(mode, chars, tokens, sources, omittedSources, omittedTokens, reusedTokens, context, taskClass, model, effort, route, why) }.getOrNull()
            ?: null.also { f.diagnostics.error(DiagCode.VALUE_INVALID, "сведения о prompt недопустимы", f.path) }
    }

    fun turn(u: TurnUsage): JsonObject = obj(
        "inputTokens" to num(u.inputTokens), "outputTokens" to num(u.outputTokens),
        "cacheReadTokens" to num(u.cacheReadTokens), "cacheCreationTokens" to num(u.cacheCreationTokens),
        "thinkingTokens" to num(u.thinkingTokens), "contextTokens" to num(u.contextTokens), "apiCalls" to num(u.apiCalls),
        "numTurns" to num(u.numTurns), "durationMs" to num(u.durationMs), "apiDurationMs" to num(u.apiDurationMs),
        "subagents" to num(u.subagents),
        "costUsd" to ((u.costUsd as? Measure.Known)?.let { JsonNumber(it.value.toPlainString()) } ?: JsonNull),
        "costEstimated" to ((u.costUsd as? Measure.Known)?.let { JsonBool(it.source == MeasureSource.ESTIMATED) } ?: JsonNull),
        "models" to JsonArray(u.models.map(::model)),
        "compacted" to JsonBool(u.compacted),
    )

    fun readTurn(f: JsonFields): TurnUsage? {
        fun m(name: String): Measure<Long> = f.optLong(name)?.let { Measure.observed(it) } ?: Measure.unknown(NOT_RECORDED)
        val input = m("inputTokens")
        val output = m("outputTokens")
        val cacheRead = m("cacheReadTokens")
        val cacheCreation = m("cacheCreationTokens")
        val thinking = m("thinkingTokens")
        val context = m("contextTokens")
        val calls = m("apiCalls")
        val turns = m("numTurns")
        val duration = m("durationMs")
        val apiDuration = m("apiDurationMs")
        val subagents = m("subagents")
        val cost = decimal(f, "costUsd")
        val estimated = bool(f, "costEstimated") ?: false
        val models = f.array("models")?.items?.mapIndexedNotNull { i, v -> readModel(v, "${f.path}.models[$i]", f) }
        val compacted = f.boolean("compacted")
        f.finish()
        if (models == null || compacted == null) return null
        val costMeasure = cost?.let { if (estimated) Measure.estimated(it) else Measure.observed(it) } ?: Measure.unknown(NOT_RECORDED)
        return TurnUsage(input, output, cacheRead, cacheCreation, thinking, context, calls, turns, duration, apiDuration, subagents, costMeasure, models, compacted)
    }

    fun totals(t: UsageTotals): JsonObject = obj(
        "turns" to JsonNumber.of(t.turns), "unknownTurns" to JsonNumber.of(t.unknownTurns),
        "inputTokens" to JsonNumber.of(t.inputTokens), "outputTokens" to JsonNumber.of(t.outputTokens),
        "cacheReadTokens" to JsonNumber.of(t.cacheReadTokens), "cacheCreationTokens" to JsonNumber.of(t.cacheCreationTokens),
        "thinkingTokens" to JsonNumber.of(t.thinkingTokens), "apiCalls" to JsonNumber.of(t.apiCalls), "subagents" to JsonNumber.of(t.subagents),
        "durationMs" to JsonNumber.of(t.durationMs), "costUsd" to JsonNumber(t.costUsd.toPlainString()), "costEstimated" to JsonBool(t.costEstimated),
        "models" to JsonObject(t.models.toSortedMap().mapValuesTo(linkedMapOf()) { JsonNumber.of(it.value) }),
        "lastContextTokens" to (t.lastContextTokens?.let { JsonNumber.of(it) } ?: JsonNull),
        "contextWindow" to (t.contextWindow?.let { JsonNumber.of(it) } ?: JsonNull),
        "compacted" to JsonBool(t.compacted), "prompts" to JsonNumber.of(t.prompts), "promptTokens" to JsonNumber.of(t.promptTokens),
        "omittedTokens" to JsonNumber.of(t.omittedTokens), "reusedTokens" to JsonNumber.of(t.reusedTokens),
        "lastPrompt" to (t.lastPrompt?.let(::prompt) ?: JsonNull),
        "tools" to (t.tools.takeIf { it != ToolTotals.EMPTY }?.let(::toolTotals) ?: JsonNull),
    )

    fun readTotals(f: JsonFields): UsageTotals? {
        val turns = f.int("turns")
        val unknown = f.int("unknownTurns")
        val longs = listOf("inputTokens", "outputTokens", "cacheReadTokens", "cacheCreationTokens", "thinkingTokens", "apiCalls", "subagents", "durationMs")
            .associateWith { f.long(it) }
        val cost = decimal(f, "costUsd")
        val estimated = f.boolean("costEstimated")
        val models = when (val v = f.raw("models")) {
            null -> null
            is JsonObject -> v.fields.mapValues { (k, n) ->
                (n as? JsonNumber)?.toLongExact() ?: null.also { f.diagnostics.error(DiagCode.FIELD_TYPE, "ожидалось целое число", "${f.path}.models.$k") }
            }
            else -> null.also { f.diagnostics.error(DiagCode.FIELD_TYPE, "ожидался объект", "${f.path}.models") }
        }
        val lastContext = f.optLong("lastContextTokens")
        val window = f.optLong("contextWindow")
        val compacted = f.boolean("compacted")
        val prompts = f.int("prompts")
        val promptTokens = f.long("promptTokens")
        val omitted = f.long("omittedTokens")
        val reused = f.long("reusedTokens")
        val last = f.optObj("lastPrompt")?.let { lf -> readPrompt(lf).also { lf.finish() } }
        val toolsField = f.optObj("tools")
        val tools = toolsField?.let(::readToolTotals)
        f.finish()
        if (turns == null || unknown == null || longs.values.any { it == null } || cost == null || estimated == null || models == null ||
            models.values.any { it == null } || compacted == null || prompts == null || promptTokens == null || omitted == null || reused == null ||
            (toolsField != null && tools == null)
        ) {
            return null
        }
        return UsageTotals(
            turns, unknown, longs.getValue("inputTokens")!!, longs.getValue("outputTokens")!!, longs.getValue("cacheReadTokens")!!,
            longs.getValue("cacheCreationTokens")!!, longs.getValue("thinkingTokens")!!, longs.getValue("apiCalls")!!, longs.getValue("subagents")!!,
            longs.getValue("durationMs")!!, cost, estimated, models.mapValues { it.value!! }, lastContext, window, compacted,
            prompts, promptTokens, omitted, reused, last, tools ?: ToolTotals.EMPTY,
        )
    }

    /** Инструменты хода (D-41): счётчики, без путей файлов. */
    fun tools(t: TurnTools): JsonObject = obj(
        "byTool" to byTool(t.byTool), "subagentCalls" to JsonNumber.of(t.subagentCalls),
        "subagentResultBytes" to JsonNumber.of(t.subagentResultBytes), "filesRead" to JsonNumber.of(t.filesRead),
        "filesEdited" to JsonNumber.of(t.filesEdited), "largestResultBytes" to JsonNumber.of(t.largestResultBytes),
        "unmatchedResults" to JsonNumber.of(t.unmatchedResults),
    )

    /** Поля [tools] в [f]; закрывает чтение сам. */
    fun readTools(f: JsonFields): TurnTools? {
        val c = readToolCommon(f)
        f.finish()
        return c?.let { TurnTools(it.byTool, it.subagentCalls, it.subagentResultBytes, it.filesRead, it.filesEdited, it.largestResultBytes, it.unmatchedResults) }
    }

    private fun toolTotals(t: ToolTotals): JsonObject = JsonObject(
        linkedMapOf<String, JsonValue>("turns" to JsonNumber.of(t.turns)).apply {
            putAll(tools(TurnTools(t.byTool, t.subagentCalls, t.subagentResultBytes, t.filesRead, t.filesEdited, t.largestResultBytes, t.unmatchedResults)).fields)
        },
    )

    private fun readToolTotals(f: JsonFields): ToolTotals? {
        val turns = f.int("turns")
        val c = readToolCommon(f)
        f.finish()
        return if (turns == null || c == null) null else c.copy(turns = turns)
    }

    /** Общие поля замера хода и сумм сессии; [ToolTotals.turns] — у вызывающего. */
    private fun readToolCommon(f: JsonFields): ToolTotals? {
        val items = f.array("byTool")?.items
        val byTool = items?.mapIndexed { i, v ->
            val o = v as? JsonObject ?: return@mapIndexed null.also { f.diagnostics.error(DiagCode.FIELD_TYPE, "ожидался объект", "${f.path}.byTool[$i]") }
            val tf = JsonFields(o, "${f.path}.byTool[$i]", f.diagnostics)
            val name = tf.string("tool")
            val n = listOf("calls", "resultBytes", "errors", "duplicates", "duplicateBytes").map { tf.long(it) }
            tf.finish()
            if (name == null || n.any { it == null || it < 0 }) null else name to ToolCount(n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!)
        }
        val longs = listOf("subagentCalls", "subagentResultBytes", "filesRead", "filesEdited", "largestResultBytes", "unmatchedResults").map { f.long(it) }
        if (byTool == null || byTool.any { it == null } || longs.any { it == null || it < 0 }) {
            return null.also { f.diagnostics.error(DiagCode.VALUE_INVALID, "замер инструментов недопустим", f.path) }
        }
        val names = byTool.map { it!!.first }
        if (names.toSet().size != names.size) return null.also { f.diagnostics.error(DiagCode.VALUE_INVALID, "инструмент назван дважды", "${f.path}.byTool") }
        return ToolTotals(0, byTool.associate { it!! }, longs[0]!!, longs[1]!!, longs[2]!!, longs[3]!!, longs[4]!!, longs[5]!!)
    }

    private fun byTool(m: Map<String, ToolCount>): JsonArray = JsonArray(
        m.entries.sortedBy { it.key }.map { (k, c) ->
            obj(
                "tool" to JsonString(k), "calls" to JsonNumber.of(c.calls), "resultBytes" to JsonNumber.of(c.resultBytes),
                "errors" to JsonNumber.of(c.errors), "duplicates" to JsonNumber.of(c.duplicates), "duplicateBytes" to JsonNumber.of(c.duplicateBytes),
            )
        },
    )

    private fun model(m: ModelTokens): JsonObject = obj(
        "model" to JsonString(m.model), "inputTokens" to opt(m.inputTokens), "outputTokens" to opt(m.outputTokens),
        "cacheReadTokens" to opt(m.cacheReadTokens), "cacheCreationTokens" to opt(m.cacheCreationTokens),
        "contextWindow" to opt(m.contextWindow), "costUsd" to (m.costUsd?.let { JsonNumber(it.toPlainString()) } ?: JsonNull),
    )

    private fun readModel(v: JsonValue, path: String, parent: JsonFields): ModelTokens? {
        val o = v as? JsonObject ?: return null.also { parent.diagnostics.error(DiagCode.FIELD_TYPE, "ожидался объект", path) }
        val f = JsonFields(o, path, parent.diagnostics)
        val name = f.string("model")
        val m = ModelTokens(
            name ?: "", f.optLong("inputTokens"), f.optLong("outputTokens"), f.optLong("cacheReadTokens"),
            f.optLong("cacheCreationTokens"), f.optLong("contextWindow"), decimal(f, "costUsd"),
        )
        f.finish()
        return m.takeIf { name != null }
    }

    private fun decimal(f: JsonFields, name: String): BigDecimal? = when (val v = f.raw(name, required = false)) {
        null, JsonNull -> null
        is JsonNumber -> v.toBigDecimal()
        else -> null.also { f.diagnostics.error(DiagCode.FIELD_TYPE, "ожидалось число", "${f.path}.$name") }
    }

    private fun bool(f: JsonFields, name: String): Boolean? = when (val v = f.raw(name, required = false)) {
        null, JsonNull -> null
        is JsonBool -> v.value
        else -> null.also { f.diagnostics.error(DiagCode.FIELD_TYPE, "ожидалось true или false", "${f.path}.$name") }
    }

    private fun num(m: Measure<Long>): JsonValue = m.orNull()?.let { JsonNumber.of(it) } ?: JsonNull

    private fun opt(v: Long?): JsonValue = v?.let { JsonNumber.of(it) } ?: JsonNull

    private fun str(v: String?): JsonValue = v?.let(::JsonString) ?: JsonNull

    private fun obj(vararg pairs: Pair<String, JsonValue>): JsonObject = JsonObject(linkedMapOf(*pairs).filterValues { it != JsonNull })
}
