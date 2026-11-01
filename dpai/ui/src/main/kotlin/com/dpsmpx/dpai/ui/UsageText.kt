package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.sessions.UsageSummary
import java.math.RoundingMode

/**
 * Строка расхода для листа задачи и экрана сессии (D-42, п. 4): ходы,
 * токены входа, выхода и чтения кеша, стоимость. Ходов не было — `null`:
 * пустая строка лучше «0» (D-13).
 */
object UsageText {
    fun of(u: UsageSummary): String? {
        if (u.empty) return null
        val turns = "ходов ${u.turns}" + if (u.unknownTurns > 0) " (+${u.unknownTurns} без данных)" else ""
        val parts = mutableListOf(turns)
        if (u.turns > 0) {
            parts += "вход ${compact(u.inputTokens)}"
            parts += "выход ${compact(u.outputTokens)}"
            if (u.cacheReadTokens > 0) parts += "кеш ${compact(u.cacheReadTokens)}"
            if (u.costUsd.signum() > 0) parts += "$" + u.costUsd.setScale(2, RoundingMode.HALF_UP).toPlainString() + if (u.costEstimated) " (оценка)" else ""
        }
        return parts.joinToString(" · ")
    }

    /** Токены коротко: 950, 12,3 тыс., 4,1 млн. */
    fun compact(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1f", n / 1_000.0).replace('.', ',') + " тыс."
        else -> String.format(java.util.Locale.ROOT, "%.1f", n / 1_000_000.0).replace('.', ',') + " млн"
    }
}
