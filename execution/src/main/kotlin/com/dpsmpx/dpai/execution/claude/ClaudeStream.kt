package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import java.math.BigDecimal

/**
 * Событие потока `stream-json` Claude Code (`docs/research/CLAUDE_CODE_CLI.md`,
 * §8; корпус `tests/data/claude-stream/`). Поля провайдера читаются
 * терпимо: новые версии Claude Code добавляют поля, и это не ошибка; но
 * то, на чём строится состояние, — тип события, `session_id`, итог —
 * читается строго (ТЗ, §53, §106).
 */
sealed interface ClaudeEvent {
    data class Init(val sessionId: String?, val model: String?, val version: String?, val apiKeySource: String?) : ClaudeEvent

    data class ToolUse(val id: String?, val name: String)

    /** Сообщение модели: текст, вызовы инструментов; [error] — ошибка API, оформленная сообщением. */
    data class Assistant(val texts: List<String>, val toolUses: List<ToolUse>, val error: String?) : ClaudeEvent

    /** Окно предела подписки: загрузка (0…1) и время сброса. */
    data class Window(val utilization: Measure<Double>, val resetsAt: Timestamp?)

    /**
     * Сведения о пределах подписки (`rate_limit_event`). Статусы
     * (`docs/research/CLAUDE_CODE_CLI.md`, §8): `allowed`, `allowed_warning` —
     * предел близко, но запрос прошёл, `rejected` — отказ. Отказ — только
     * `rejected`: предупреждение, принятое за отказ, держало бы слот в
     * `RATE_LIMITED` до сброса окна, вплоть до недели.
     */
    data class RateLimit(val status: String, val type: String?, val resetsAt: Timestamp?, val windows: Map<String, Window>) : ClaudeEvent {
        val rejected: Boolean get() = status == REJECTED
        val warning: Boolean get() = status == WARNING

        companion object {
            const val ALLOWED = "allowed"
            const val WARNING = "allowed_warning"
            const val REJECTED = "rejected"
            val KNOWN: Set<String> = setOf(ALLOWED, WARNING, REJECTED)
        }
    }

    /** Итог хода. [isError] — у провайдера; `subtype` бывает `success` и при ошибке (корпус `auth-failed.jsonl`). */
    data class Result(
        val subtype: String,
        val isError: Boolean,
        val terminalReason: String?,
        val text: String?,
        val structured: JsonValue?,
        val costUsd: Measure<BigDecimal>,
        val inputTokens: Measure<Long>,
        val outputTokens: Measure<Long>,
        val numTurns: Int?,
    ) : ClaudeEvent

    /** Известное, но не нужное состоянию событие (`user`, `system/thinking_tokens`, частичные сообщения). */
    data class Other(val type: String, val subtype: String?) : ClaudeEvent
}

/**
 * Разбор потока одного хода (D-05, D-23; ТЗ, §106). Состояние — в
 * разборщике: повтор события (тот же `uuid`) не применяется, чужой
 * `session_id` — `CLAUDE_SESSION_MISMATCH`, строка не JSON —
 * предупреждение `CLAUDE_STREAM_MALFORMED` (это может быть строка журнала
 * самого CLI), неизвестный тип события — `CLAUDE_EVENT_UNKNOWN`. Догадок
 * нет: чего нет в потоке, того нет в итоге (D-13).
 */
class ClaudeStreamParser(private val expectedSession: ProviderSessionId?) {
    private val seen = HashSet<String>()
    private var mismatch = false

    /** Разобранная строка: событие (если есть) и находки. */
    data class Parsed(val event: ClaudeEvent?, val diagnostics: List<Diagnostic>)

    fun feed(line: String, truncated: Boolean = false): Parsed {
        if (line.isBlank()) return Parsed(null, emptyList())
        if (truncated) return Parsed(null, listOf(malformed("строка обрезана по пределу длины")))
        val obj = when (val v = JsonParser().parse(line)) {
            is Outcome.Failure -> return Parsed(null, listOf(malformed("строка не JSON: ${line.take(80)}")))
            is Outcome.Success -> v.value as? JsonObject ?: return Parsed(null, listOf(malformed("строка не объект JSON")))
        }
        val type = obj.str("type") ?: return Parsed(null, listOf(malformed("событие без type")))
        val notes = mutableListOf<Diagnostic>()
        obj.str("uuid")?.let { uuid ->
            if (!seen.add(uuid)) {
                return Parsed(null, listOf(Diagnostic.info(DiagCode.EVENT_DUPLICATE, "повтор события $type $uuid", SUBJECT)))
            }
        }
        val sid = obj.str("session_id")
        if (sid != null && expectedSession != null && sid != expectedSession.value && !mismatch) {
            mismatch = true
            notes += Diagnostic.error(DiagCode.CLAUDE_SESSION_MISMATCH, "session_id $sid, ожидался ${expectedSession.value}", SUBJECT)
        }
        val event: ClaudeEvent = when (type) {
            "system" -> when (val sub = obj.str("subtype")) {
                "init" -> ClaudeEvent.Init(sid, obj.str("model"), obj.str("claude_code_version"), obj.str("apiKeySource"))
                else -> ClaudeEvent.Other(type, sub)
            }
            "assistant" -> assistant(obj)
            "rate_limit_event" -> (
                rateLimit(obj["rate_limit_info"] as? JsonObject)
                    ?: return Parsed(null, notes + malformed("rate_limit_event без rate_limit_info"))
                ).also {
                // Неизвестный статус — не отказ и не догадка (D-05): запись, чтобы его увидели.
                if (it.status !in ClaudeEvent.RateLimit.KNOWN) notes += malformed("неизвестный статус предела: ${it.status}")
            }
            "result" -> result(obj) ?: return Parsed(null, notes + malformed("result без is_error или subtype"))
            "user", "stream_event" -> ClaudeEvent.Other(type, obj.str("subtype"))
            else -> {
                notes += Diagnostic.info(DiagCode.CLAUDE_EVENT_UNKNOWN, "неизвестный тип события $type", SUBJECT)
                ClaudeEvent.Other(type, obj.str("subtype"))
            }
        }
        return Parsed(event, notes)
    }

    private fun assistant(obj: JsonObject): ClaudeEvent.Assistant {
        val content = ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>()
        val texts = content.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }
        val tools = content.filter { it.str("type") == "tool_use" }.map { ClaudeEvent.ToolUse(it.str("id"), it.str("name") ?: "?") }
        return ClaudeEvent.Assistant(texts, tools, obj.str("error"))
    }

    private fun rateLimit(info: JsonObject?): ClaudeEvent.RateLimit? {
        info ?: return null
        val status = info.str("status") ?: return null
        val windows = (info["unifiedWindows"] as? JsonObject)?.fields.orEmpty().mapNotNull { (name, w) ->
            (w as? JsonObject)?.let {
                name to ClaudeEvent.Window(
                    it.decimal("utilization")?.let { u -> Measure.observed(u.toDouble()) } ?: Measure.unknown("нет utilization"),
                    it.long("resetsAt")?.let { s -> Timestamp(s * 1000) },
                )
            }
        }.toMap()
        return ClaudeEvent.RateLimit(status, info.str("rateLimitType"), info.long("resetsAt")?.let { Timestamp(it * 1000) }, windows)
    }

    private fun result(obj: JsonObject): ClaudeEvent.Result? {
        val subtype = obj.str("subtype") ?: return null
        val isError = (obj["is_error"] as? JsonBool)?.value ?: return null
        val usage = obj["usage"] as? JsonObject
        // Стоимость при подписке — расчёт по прейскуранту (costBasis = list), а не списание: оценка (D-13).
        val listBasis = (obj["modelUsage"] as? JsonObject)?.fields?.values.orEmpty()
            .any { (it as? JsonObject)?.str("costBasis") == "list" }
        val cost = obj.decimal("total_cost_usd")?.let { if (listBasis) Measure.estimated(it) else Measure.observed(it) }
            ?: Measure.unknown("нет total_cost_usd")
        return ClaudeEvent.Result(
            subtype = subtype,
            isError = isError,
            terminalReason = obj.str("terminal_reason"),
            text = obj.str("result"),
            structured = obj["structured_output"]?.takeIf { it != JsonNull },
            costUsd = cost,
            inputTokens = usage?.long("input_tokens")?.let { Measure.observed(it) } ?: Measure.unknown("нет usage.input_tokens"),
            outputTokens = usage?.long("output_tokens")?.let { Measure.observed(it) } ?: Measure.unknown("нет usage.output_tokens"),
            numTurns = obj.long("num_turns")?.toInt(),
        )
    }

    private fun malformed(why: String) = Diagnostic.warning(DiagCode.CLAUDE_STREAM_MALFORMED, why, SUBJECT)

    companion object {
        const val SUBJECT = "claude stream-json"
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonString)?.value

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonNumber)?.toLongExact()

private fun JsonObject.decimal(key: String): BigDecimal? = (this[key] as? JsonNumber)?.toBigDecimal()
