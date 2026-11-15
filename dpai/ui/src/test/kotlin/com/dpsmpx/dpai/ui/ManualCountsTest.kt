package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals

/** Счётчики виджета (DPmetaApp:D-153): сутки, итоги, идущий запуск, неизвестное — не ноль. */
class ManualCountsTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(153))
    private val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Linux", "/root/.claude-slot-1").valueOrNull()!!
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/repo", "/root/trees", "main", emptyList())
    private val build = ManualProfile.form(ManualProfileKind.BUILD, "make", "60").valueOrNull()!!
    private val now = Timestamp(10L * 24 * 60 * 60 * 1000)

    private fun entry(kind: ManualProfileKind, hoursAgo: Long, outcome: ManualOutcome) = ManualHistoryEntry(
        ids.next(IdKinds.SESSION), kind, null, Timestamp(now.epochMillis - hoursAgo * 3_600_000), "Linux", "main", null, emptyList(), outcome, "", "",
    )

    @Test
    fun dayWindowAcrossProjectsAndRunningKind() {
        val a = listOf(entry(ManualProfileKind.BUILD, 1, ManualOutcome.OK), entry(ManualProfileKind.CHECK, 2, ManualOutcome.FAILED), entry(ManualProfileKind.BUILD, 30, ManualOutcome.FAILED))
        val b = listOf(entry(ManualProfileKind.CHECK, 23, ManualOutcome.OK), entry(ManualProfileKind.BUILD, 3, ManualOutcome.STOPPED))
        val run = ManualRun(ids.next(IdKinds.SESSION), project, target, "/root/repo", "main", build)
        assertEquals(ManualCounts(ManualProfileKind.BUILD, ok = 2, failed = 1), ManualCounts.of(run, listOf(Outcome.success(a), Outcome.success(b)), now))
        assertEquals(ManualCounts(null, 0, 0), ManualCounts.of(null, emptyList(), now))
    }

    @Test
    fun unreadableHistoryIsUnknownNotZero() {
        val broken = Outcome.failure(DiagCode.STORAGE_CORRUPTED, "история испорчена")
        assertEquals(ManualCounts(null, null, null), ManualCounts.of(null, listOf(Outcome.success(emptyList()), broken), now))
    }
}
