package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.claude.UsageReport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ответ `/usage` (D-48) на записанном образце: окна, доли, сброс до секунды; чужой вывод — отказ. */
class UsageReportTest {
    private fun corpus(name: String): List<String> {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        return File(root, "tests/data/claude-stream/$name").readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    @Test
    fun recordedUsageGivesSessionAndWeekWindows() {
        val windows = UsageReport.parse(corpus("usage-local.jsonl")).valueOrNull()!!
        assertEquals(
            listOf(
                UsageReport.Window("session", 0.76, Timestamp(1791534600_000)),
                UsageReport.Window("weekly", 0.91, Timestamp(1792022400_000)),
            ),
            windows,
        )
    }

    /** Сброс «08:29:59.9» и «08:30:00.2» — одно время: иначе каждый опрос писал бы «новое» событие. */
    @Test
    fun resetTimeNoiseRoundsToTheMinute() {
        fun at(v: String) = UsageReport.parse(listOf("""{"usage_report":{"rate_limits":{"limits":[{"kind":"session","percent":5,"resets_at":"$v"}]}}}""")).valueOrNull()!!.single().resetsAt
        assertEquals(at("2026-10-09T08:30:00.211818+00:00"), at("2026-10-09T08:29:59.902117+00:00"))
        assertEquals(Timestamp(1791534600_000), at("2026-10-09T08:29:59.902117+00:00"))
    }

    @Test
    fun ordinaryTurnIsNotAUsageReport() {
        val r = UsageReport.parse(corpus("success-structured.jsonl"))
        assertEquals(DiagCode.CLAUDE_USAGE_REPORT_INVALID, (r as Outcome.Failure).diagnostics.single().code)
    }
}
