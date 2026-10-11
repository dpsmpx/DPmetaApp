package com.dpsmpx.dpai.execution.claude

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import java.time.OffsetDateTime

/**
 * Загрузка окон подписки из локальной команды `/usage` Claude Code (D-48):
 * `echo /usage | claude -p --verbose --output-format stream-json` отвечает
 * без запроса к модели (модель `<synthetic>`, `total_cost_usd: 0`) и несёт
 * `usage_report.rate_limits.limits` — вид окна, процент и время сброса
 * (образец `tests/data/claude-stream/usage-local.jsonl`, Claude Code
 * 2.1.294). Неизвестный вид окна пропускается, а не угадывается (D-05).
 */
object UsageReport {
    /** Окно: [window] — `session` (5 часов) или `weekly` (неделя), доля 0…1, сброс — до минуты. */
    data class Window(val window: String, val utilization: Double, val resetsAt: Timestamp?)

    /** Окна из строк вывода; нет строки с `usage_report` — отказ. */
    fun parse(lines: List<String>): Outcome<List<Window>> {
        for (line in lines) {
            val o = JsonParser().parse(line.trim()).valueOrNull() as? JsonObject ?: continue
            val report = o["usage_report"] as? JsonObject ?: continue
            val limits = ((report["rate_limits"] as? JsonObject)?.get("limits") as? JsonArray)?.items.orEmpty().filterIsInstance<JsonObject>()
            return Outcome.success(limits.mapNotNull(::window))
        }
        return Outcome.failure(DiagCode.CLAUDE_USAGE_REPORT_INVALID, "в ответе /usage нет usage_report.rate_limits", "claude /usage")
    }

    private fun window(l: JsonObject): Window? {
        // «weekly_all» — неделя по всем моделям; отдельные недельные (по модели) — не то окно, что у подписки.
        val window = when ((l["kind"] as? JsonString)?.value) {
            "session" -> "session"
            "weekly_all" -> "weekly"
            else -> return null
        }
        val percent = (l["percent"] as? JsonNumber)?.toBigDecimal()?.toDouble() ?: return null
        // Время сброса приходит с шумом от момента запроса (08:30:00.21, 08:29:59.9 — Redmi Note 13, 9 октября
        // 2026): окна сбрасываются по целым минутам — округление до минуты, иначе «новое» время на каждом опросе.
        val resets = (l["resets_at"] as? JsonString)?.value?.let { v ->
            runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
        }?.let { ms -> Timestamp(Math.round(ms / 60_000.0) * 60_000) }
        return Window(window, percent / 100.0, resets)
    }
}
