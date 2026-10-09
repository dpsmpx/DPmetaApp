package com.dpsmpx.dpai.orchestrator.log

import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue

/** Строка журнала из события: уровень, тег и текст. */
data class EventLine(val level: LogLevel, val tag: String, val text: String)

/**
 * События журнала оркестратора → строки журнала приложения (D-43). Одно
 * место вместо записей по коду: что записано в журнал событий, то и в
 * журнале приложения, — сессии, ходы, расход, задачи, проверка, handoff,
 * аккаунты, деревья, слияния.
 *
 * Только факты: состояния, коды, id, счётчики. Не пишутся текст модели
 * (`SESSION_OUTPUT`), цель задачи, вывод процессов, сердцебиения
 * (часто и без новости) — их нет в [SKIP] и в [EXCLUDED].
 */
object EventLog {
    /** События без строки: текст модели и процессов, частые сердцебиения. */
    private val SKIP = setOf(
        EventType.SESSION_OUTPUT, EventType.SESSION_HEARTBEAT, EventType.TARGET_PROCESS_OUTPUT, EventType.TARGET_PROCESS_HEARTBEAT,
    )

    /** Поля, которые не пишутся: тексты пользователя и модели, доказательства с выводом, аргументы команд. */
    private val EXCLUDED = setOf("objective", "text", "evidence", "arguments", "prompt", "content", "message", "output", "body")

    private const val MAX_VALUE = 80

    /**
     * Длинное значение — короче [MAX_VALUE], но не посреди слова: обрезок
     * id (`tsk_01M4FP745VR6TQ0JJ9V…`) защита журнала принимала за токен, и
     * путь рабочего дерева становился `<redacted>` (Redmi Note 13, 9 октября
     * 2026). У пути важен конец — остаются последние целые сегменты.
     */
    internal fun shorten(s: String): String {
        if (s.length <= MAX_VALUE) return s
        if ('/' in s) {
            var tail = ""
            for (seg in s.split('/').asReversed()) {
                val next = if (tail.isEmpty()) seg else "$seg/$tail"
                if (next.length + 2 > MAX_VALUE) break
                tail = next
            }
            if (tail.isNotEmpty()) return "…/$tail"
        }
        val cut = s.take(MAX_VALUE).let { head -> head.substring(0, head.indexOfLast { !it.isLetterOrDigit() && it != '_' }.takeIf { it > 0 } ?: head.length) }
        return "$cut…"
    }

    fun line(e: EventEnvelope): EventLine? {
        if (e.type in SKIP) return null
        val p = e.payload
        val fields = when (e.type) {
            EventType.SESSION_TURN_USAGE -> usage(p["usage"] as? JsonObject) + scalars(p, skip = setOf("usage"))
            EventType.SESSION_TURN_TOOLS -> tools(p["tools"] as? JsonObject) + scalars(p, skip = setOf("tools"))
            // Политика задачи (D-42): что стало — класс, модель, усилие, бюджет.
            EventType.TASK_POLICY_CHANGED, EventType.TASK_CREATED -> scalars(p, skip = setOf("policy")) + (p["policy"] as? JsonObject)?.let { scalars(it) }.orEmpty()
            // Ответ на запрос (D-44): вариант и итог, без текста ответа человека.
            EventType.USER_REQUEST_ANSWERED -> scalars(p) + (p["answer"] as? JsonObject)?.let { scalars(it, skip = setOf("at")) }.orEmpty()
            else -> scalars(p)
        }
        val text = (listOf(e.type.name) + fields.map { (k, v) -> "$k=$v" }).joinToString(" ")
        return EventLine(level(e), tag(e.type), text)
    }

    private fun level(e: EventEnvelope): LogLevel {
        val to = (e.payload["to"] as? JsonString)?.value
        return when {
            e.type == EventType.SESSION_TOOL_STARTED -> LogLevel.DEBUG
            e.type == EventType.SESSION_STATE_CHANGED && to in setOf("FAILED", "BLOCKED") -> LogLevel.WARN
            e.type == EventType.TASK_STATE_CHANGED && to in setOf("FAILED", "BLOCKED", "COMPLETION_UNVERIFIED") -> LogLevel.WARN
            e.type in setOf(EventType.ACCOUNT_RATE_LIMITED, EventType.ACCOUNT_AUTH_REQUIRED, EventType.ACCOUNT_ERROR_RECORDED, EventType.ACCOUNT_NEAR_LIMIT) -> LogLevel.WARN
            e.type == EventType.SESSION_TURN_FINISHED && (e.payload["codes"] as? JsonArray)?.items?.isNotEmpty() == true -> LogLevel.WARN
            e.type == EventType.USER_REQUEST_FAILED || e.type == EventType.USER_REQUEST_EXPIRED -> LogLevel.WARN
            else -> LogLevel.INFO
        }
    }

    private fun tag(t: EventType): String {
        val n = t.name
        return when {
            n.startsWith("SESSION_") -> "сессия"
            n.startsWith("TASK_MERGE") || n.startsWith("WORKTREE_") || n.startsWith("GIT_") || n.startsWith("REPOSITORY_") || n == "MERGE_CONFLICT_DETECTED" -> "git"
            n.startsWith("TASK_") || n.startsWith("CONTINUATION_") -> "задача"
            n.startsWith("ACCOUNT_") -> "аккаунт"
            n.startsWith("EXECUTION_TARGET") || n.startsWith("TARGET_") -> "target"
            n.startsWith("VERIFICATION_") -> "проверка"
            n.startsWith("HANDOFF_") -> "handoff"
            n.startsWith("USER_REQUEST_") -> "запрос"
            else -> "событие"
        }
    }

    /** Поля-скаляры и списки скаляров верхнего уровня, кроме [EXCLUDED]; длинное — обрезано. */
    private fun scalars(o: JsonObject, skip: Set<String> = emptySet()): List<Pair<String, String>> =
        o.fields.entries.filter { it.key !in EXCLUDED && it.key !in skip }.mapNotNull { (k, v) -> value(v)?.let { k to it } }

    private fun value(v: JsonValue): String? = when (v) {
        is JsonString -> shorten(v.value).let { if (' ' in it) "«$it»" else it }
        is JsonNumber -> v.toBigDecimal().toPlainString()
        is JsonBool -> v.value.toString()
        is JsonArray -> v.items.mapNotNull { (it as? JsonString)?.value ?: (it as? JsonNumber)?.toBigDecimal()?.toPlainString() }
            .takeIf { it.size == v.items.size && it.size <= 10 }?.joinToString(",", "[", "]")
        else -> null
    }

    /** Расход хода — главные меры (наблюдаемое; нет поля — провайдер не сообщил). */
    private fun usage(u: JsonObject?): List<Pair<String, String>> {
        u ?: return emptyList()
        val keys = listOf("inputTokens", "outputTokens", "cacheReadTokens", "cacheCreationTokens", "contextTokens", "apiCalls", "durationMs", "costUsd", "compacted")
        return keys.mapNotNull { k -> u[k]?.let(::value)?.let { k to it } } +
            ((u["models"] as? JsonArray)?.items?.mapNotNull { (it as? JsonObject)?.get("model")?.let(::value) }?.takeIf { it.isNotEmpty() }
                ?.let { listOf("models" to it.joinToString(",")) }.orEmpty())
    }

    /** Инструменты хода — суммы (D-41). */
    private fun tools(t: JsonObject?): List<Pair<String, String>> {
        t ?: return emptyList()
        val rows = (t["byTool"] as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>()
        fun sum(k: String) = rows.sumOf { (it[k] as? JsonNumber)?.toLongExact() ?: 0 }
        return listOf("toolCalls" to sum("calls").toString(), "toolBytes" to sum("resultBytes").toString(), "toolErrors" to sum("errors").toString(), "duplicates" to sum("duplicates").toString()) +
            listOf("subagentCalls", "filesRead", "filesEdited", "largestResultBytes").mapNotNull { k -> t[k]?.let(::value)?.let { k to it } }
    }
}
