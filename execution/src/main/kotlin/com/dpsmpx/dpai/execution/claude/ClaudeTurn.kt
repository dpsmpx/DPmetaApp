package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.ProviderSessionId
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.agent.ExitReason
import java.math.BigDecimal

/**
 * Итог хода: `COMPLETED` — провайдер сообщил итог без ошибки (это
 * заявление, а не завершение задачи, D-23); `FAILED` — ошибка; `INCOMPLETE`
 * — ход прерван (тайм-аут, остановка, предел ходов или бюджета) и его можно
 * продолжить (`resume`).
 */
enum class TurnStatus { COMPLETED, FAILED, INCOMPLETE }

data class ClaudeTurn(
    val status: TurnStatus,
    val providerSession: ProviderSessionId,
    val text: String?,
    val structured: JsonValue?,
    val usage: TurnUsage,
    val rateLimit: ClaudeEvent.RateLimit?,
    val exitCode: Int?,
    val diagnostics: List<Diagnostic>,
    /** Инструменты хода — замер оркестратора по потоку (D-41), есть и у хода без итога. */
    val tools: TurnTools = TurnTools.EMPTY,
    /** Файлы, которые ход читал и правил (D-41); в журнал не пишутся. */
    val files: TurnFiles = TurnFiles.EMPTY,
) {
    val costUsd: Measure<BigDecimal> get() = usage.costUsd
    val inputTokens: Measure<Long> get() = usage.inputTokens
    val outputTokens: Measure<Long> get() = usage.outputTokens
}

/**
 * Сборка итога хода из событий потока и выхода процесса (ТЗ, §53, §106).
 * Причина неудачи — из машинно читаемых полей (`error` сообщения,
 * `rate_limit_event`, `subtype` итога), а не из текста модели. Нет итога в
 * потоке — ход не завершён, что бы ни было в тексте (D-23).
 */
class ClaudeTurnAssembler(private val session: ProviderSessionId) {
    private val parser = ClaudeStreamParser(session)
    private val notes = mutableListOf<Diagnostic>()
    private var result: ClaudeEvent.Result? = null
    private var rateLimit: ClaudeEvent.RateLimit? = null
    private val apiErrors = mutableListOf<String>()
    private var eventsAfterResult = 0
    private var compacted = false
    private val tools = ToolTracker()

    /** Строка вывода; возвращает событие, если строка его несёт. */
    fun feed(line: String, truncated: Boolean = false): ClaudeEvent? {
        val p = parser.feed(line, truncated)
        notes += p.diagnostics
        when (val e = p.event) {
            is ClaudeEvent.Result -> if (result == null) result = e else eventsAfterResult++
            is ClaudeEvent.RateLimit -> rateLimit = e
            is ClaudeEvent.Assistant -> {
                e.error?.let { apiErrors += it }
                e.toolUses.forEach { tools.onUse(it, e.subagent) }
                if (result != null) eventsAfterResult++
            }
            is ClaudeEvent.ToolResults -> {
                e.results.forEach(tools::onResult)
                if (result != null) eventsAfterResult++
            }
            // Сжатие истории самим Claude Code (`docs/research/CLAUDE_CODE_CLI.md`, §11).
            is ClaudeEvent.Other -> {
                if (e.type == "system" && e.subtype == COMPACT_BOUNDARY) compacted = true
                if (result != null) eventsAfterResult++
            }
            else -> if (result != null && e != null) eventsAfterResult++
        }
        return p.event
    }

    fun finish(exitCode: Int?, exitReason: ExitReason?): ClaudeTurn {
        val diags = notes.toMutableList()
        if (eventsAfterResult > 0) {
            diags += Diagnostic.warning(DiagCode.CLAUDE_STREAM_MALFORMED, "после итога ещё $eventsAfterResult событий", ClaudeStreamParser.SUBJECT)
        }
        val r = result
        val mismatch = diags.any { it.code == DiagCode.CLAUDE_SESSION_MISMATCH }
        val status = when {
            mismatch -> TurnStatus.FAILED
            r == null -> {
                diags += incomplete(exitCode, exitReason)
                if (exitReason == ExitReason.TIMEOUT || exitReason == ExitReason.STOPPED) TurnStatus.INCOMPLETE else TurnStatus.FAILED
            }
            !r.isError -> {
                if (exitCode != null && exitCode != 0) {
                    diags += Diagnostic.warning(DiagCode.PROCESS_EXIT_NONZERO, "итог без ошибки, но код выхода $exitCode", ClaudeStreamParser.SUBJECT)
                }
                TurnStatus.COMPLETED
            }
            else -> {
                val (code, partial) = classify(r)
                diags += Diagnostic.error(code, describe(r), ClaudeStreamParser.SUBJECT)
                if (partial) TurnStatus.INCOMPLETE else TurnStatus.FAILED
            }
        }
        return ClaudeTurn(
            status = status,
            providerSession = session,
            text = r?.text,
            structured = r?.structured,
            usage = (r?.usage ?: TurnUsage.unknown("нет итога хода")).copy(compacted = compacted),
            rateLimit = rateLimit,
            exitCode = exitCode,
            diagnostics = diags,
            tools = tools.result(),
            files = tools.files(),
        )
    }

    /** Код причины и «ход можно продолжить». Неизвестная причина — общий код с текстом, не догадка. */
    private fun classify(r: ClaudeEvent.Result): Pair<DiagCode, Boolean> = when {
        "authentication_failed" in apiErrors -> DiagCode.CLAUDE_AUTH_REQUIRED to false
        "rate_limit" in apiErrors || rateLimit?.rejected == true -> DiagCode.CLAUDE_RATE_LIMITED to false
        // Переполнение — продолжаемо, но не тем же объёмом (D-40, п. 4): решает оркестратор.
        r.text?.contains(PROMPT_TOO_LONG, ignoreCase = true) == true -> DiagCode.CLAUDE_CONTEXT_OVERFLOW to true
        r.subtype == "error_max_turns" -> DiagCode.CLAUDE_MAX_TURNS to true
        "budget" in r.subtype -> DiagCode.CLAUDE_BUDGET_EXCEEDED to true
        apiErrors.isNotEmpty() -> DiagCode.CLAUDE_API_ERROR to false
        else -> DiagCode.CLAUDE_RESULT_ERROR to false
    }

    private fun describe(r: ClaudeEvent.Result): String = buildString {
        append("итог с ошибкой: subtype=").append(r.subtype)
        r.terminalReason?.let { append(", terminal_reason=").append(it) }
        if (apiErrors.isNotEmpty()) append(", error=").append(apiErrors.distinct().joinToString())
        r.text?.let { append(": ").append(it.take(200)) }
    }

    private fun incomplete(exitCode: Int?, reason: ExitReason?): Diagnostic {
        val why = when (reason) {
            ExitReason.TIMEOUT -> "тайм-аут хода"
            ExitReason.STOPPED -> "ход остановлен"
            ExitReason.START_FAILED -> "процесс не запустился"
            ExitReason.LOST -> "супервизор потерян"
            else -> "процесс вышел с кодом ${exitCode ?: "?"}"
        }
        return Diagnostic.error(DiagCode.CLAUDE_STREAM_INCOMPLETE, "поток кончился без итога: $why", ClaudeStreamParser.SUBJECT)
    }

    companion object {
        /** Сжатие истории Claude Code: `system`, `subtype` — это значение (Agent SDK, `SDKCompactBoundaryMessage`). */
        const val COMPACT_BOUNDARY = "compact_boundary"

        /** Отказ API по размеру prompt: «prompt is too long: N tokens > M maximum» (`invalid_request_error`). */
        const val PROMPT_TOO_LONG = "prompt is too long"
    }
}
