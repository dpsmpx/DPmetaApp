package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ManualAutomationResultTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(217))

    @Test fun previousOrConcurrentlyFinishedSuccessDoesNotBelongToAFailedPreparation() {
        val other = ids.next(IdKinds.SESSION)
        val view = ManualRunView(null, false, "подготовка нового не удалась", "старый вывод", finished = other, complete = true)
        assertIs<Outcome.Failure>(ManualAutomationResult.evidence(null, view))
        assertIs<Outcome.Failure>(ManualAutomationResult.evidence(ids.next(IdKinds.SESSION), view))
    }

    @Test fun failedPreparationIsExplicitAndDoesNotClaimACommandWasStarted() {
        val own = ids.next(IdKinds.SESSION)
        val view = ManualRunView(null, false, "ветка не совпала", "", finished = own, complete = false)
        val evidence = ManualAutomationResult.evidence(own, view).valueOrNull()!!
        assertEquals(JsonString("PREFLIGHT"), evidence["phase"])
        assertEquals(JsonBool(false), evidence["commandStarted"])
        assertEquals(JsonString("ветка не совпала"), evidence["note"])
    }

    @Test fun completedStepIsRecordedAsExecutionEvenWhenNoStepIsCurrentlyActive() {
        val own = ids.next(IdKinds.SESSION)
        val view = ManualRunView(null, false, "проверка завершена", "", results = listOf(ManualStepResult(1, 0, "ok")),
            finished = own, complete = true)
        val evidence = ManualAutomationResult.evidence(own, view).valueOrNull()!!
        assertEquals(JsonString("EXECUTION"), evidence["phase"])
        assertEquals(JsonBool(true), evidence["commandStarted"])
    }

    @Test fun matchingFailedRunKeepsItsRealEvidenceAndFullOutputHash() {
        val own = ids.next(IdKinds.SESSION)
        val output = "x".repeat(4000)
        val view = ManualRunView(null, false, "ошибка", output, results = listOf(ManualStepResult(1, 7, "код 7")), finished = own, complete = false)
        val evidence = ManualAutomationResult.evidence(own, view).valueOrNull()!!
        assertEquals(JsonBool(false), evidence["complete"])
        assertEquals(JsonString("EXECUTION"), evidence["phase"])
        assertEquals(JsonBool(true), evidence["commandStarted"])
        assertEquals(JsonString(own.value), evidence["run"])
        assertEquals(JsonBool(true), evidence["outputTruncated"])
        assertEquals(JsonString("sha256:" + Hashing.sha256(output).hex), evidence["outputHash"])
    }
}
