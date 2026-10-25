package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.sessions.UsageSummary
import com.dpsmpx.dpai.sessions.UsageTotals
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Строка расхода задачи и сессии (D-42, п. 4): свод нескольких сессий, неизвестное — не ноль. */
class UsageTextTest {
    @Test
    fun summaryOfSessionsAndText() {
        val a = UsageTotals(turns = 2, inputTokens = 12_300, outputTokens = 800, cacheReadTokens = 1_500_000, costUsd = BigDecimal("0.125"))
        val b = UsageTotals(turns = 1, unknownTurns = 1, inputTokens = 700, outputTokens = 200, costUsd = BigDecimal("0.3"), costEstimated = true)
        val s = UsageSummary.of(listOf(a, b))
        assertEquals(3, s.turns)
        assertEquals(13_000, s.inputTokens)
        assertEquals("ходов 3 (+1 без данных) · вход 13,0 тыс. · выход 1,0 тыс. · кеш 1,5 млн · $0.43 (оценка)", UsageText.of(s))
        assertNull(UsageText.of(UsageSummary.of(listOf(UsageTotals()))), "ходов не было — строки нет")
        assertEquals("ходов 0 (+2 без данных)", UsageText.of(UsageSummary.of(listOf(UsageTotals(unknownTurns = 2)))))
    }
}
