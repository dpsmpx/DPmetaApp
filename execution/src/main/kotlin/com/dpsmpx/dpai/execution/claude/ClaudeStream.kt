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

    /** Вызов инструмента; [input] — его вход (для замера повторов и рабочего набора файлов, D-41). */
    data class ToolUse(val id: String?, val name: String, val input: JsonObject? = null)

    /**
     * Сообщение модели: текст, вызовы инструментов; [error] — ошибка API,
     * оформленная сообщением. [subagent] — сообщение субагента
     * (`parent_tool_use_id`), а не самого хода.
     */
    data class Assistant(val texts: List<String>, val toolUses: List<ToolUse>, val error: String?, val subagent: Boolean = false) : ClaudeEvent

    /** Результат инструмента: [bytes] — UTF-8 текста, который получила модель; изображения не считаются. */
    data class ToolResult(val id: String?, val bytes: Long, val isError: Boolean)

    /** Результаты инструментов (`user` с блоками `tool_result`); [subagent] — в контексте субагента. */
    data class ToolResults(val results: List<ToolResult>, val subagent: Boolean) : ClaudeEvent

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
        val usage: TurnUsage,
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
            "user" -> toolResults(obj) ?: ClaudeEvent.Other(type, obj.str("subtype"))
            "stream_event" -> ClaudeEvent.Other(type, obj.str("subtype"))
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
        val tools = content.filter { it.str("type") == "tool_use" }.map { ClaudeEvent.ToolUse(it.str("id"), it.str("name") ?: "?", it["input"] as? JsonObject) }
        return ClaudeEvent.Assistant(texts, tools, obj.str("error"), subagent = obj.str("parent_tool_use_id") != null)
    }

    /**
     * Блоки `tool_result` сообщения `user`: размер — UTF-8 текста
     * (`content` строкой или блоками `text`). Поле `tool_use_result` — сырой
     * итог инструмента для клиента, в контекст модели оно не идёт и не
     * считается. Нет блоков — `null`.
     */
    private fun toolResults(obj: JsonObject): ClaudeEvent.ToolResults? {
        val content = ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>()
        val results = content.filter { it.str("type") == "tool_result" }.map { b ->
            val bytes = when (val c = b["content"]) {
                is JsonString -> utf8(c.value)
                is JsonArray -> c.items.filterIsInstance<JsonObject>().filter { it.str("type") == "text" }.sumOf { utf8(it.str("text").orEmpty()) }
                else -> 0L
            }
            ClaudeEvent.ToolResult(b.str("tool_use_id"), bytes, (b["is_error"] as? JsonBool)?.value == true)
        }
        return results.takeIf { it.isNotEmpty() }?.let { ClaudeEvent.ToolResults(it, subagent = obj.str("parent_tool_use_id") != null) }
    }

    private fun utf8(s: String): Long = s.toByteArray(Charsets.UTF_8).size.toLong()

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
        return ClaudeEvent.Result(
            subtype = subtype,
            isError = isError,
            terminalReason = obj.str("terminal_reason"),
            text = obj.str("result"),
            structured = obj["structured_output"]?.takeIf { it != JsonNull },
            usage = TurnUsage.fromResult(obj),
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
