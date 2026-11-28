package com.dpsmpx.dpai.tests.integration

import com.dpsmpx.dpai.automation.AutomationGate
import com.dpsmpx.dpai.automation.AutomationTrigger
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.orchestrator.efficiency.EfficiencyPolicy
import com.dpsmpx.dpai.verification.VerifyLevel
import com.dpsmpx.dpai.verification.Verdict
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Проверка владельца идёт в реальном дереве задачи; отказ сценария не допускает DONE/merge. */
class WorkflowGateTest {
    @TempDir lateinit var root: Path

    @Test fun failedAcceptanceGatePreventsCommitAndCompletion() {
        val seen = mutableListOf<AutomationTrigger>()
        val h = EfficiencyHarness(root, EfficiencyPolicy(), automationGate = AutomationGate { request, verify ->
            seen += request.trigger
            assertTrue(Path.of(request.root).startsWith(root.resolve("trees")))
            assertEquals(Verdict.PASSED, verify(VerifyLevel.NORMAL).verdict)
            Outcome.failure(DiagCode.VERIFICATION_FAILED, "обязательный сценарий не прошёл")
        })
        val task = h.orchestrator.addTask("Проверить", "Цель").valueOrNull()!!
        h.run()
        assertEquals(listOf(AutomationTrigger.BEFORE_ACCEPT), seen)
        assertEquals(TaskState.BLOCKED, h.orchestrator.tasks.tasks.getValue(task).state)
        assertTrue(h.orchestrator.mergesState.pending.isEmpty())
    }

    @Test fun commitGateRunsAfterAcceptanceAndCanStillBlockCompletion() {
        val seen = mutableListOf<AutomationTrigger>()
        val h = EfficiencyHarness(root, EfficiencyPolicy(), automationGate = AutomationGate { request, _ ->
            seen += request.trigger
            if (request.trigger == AutomationTrigger.BEFORE_COMMIT)
                Outcome.failure(DiagCode.VERIFICATION_FAILED, "проверка перед коммитом не прошла")
            else Outcome.success(Unit)
        })
        val task = h.orchestrator.addTask("Проверить", "Цель").valueOrNull()!!
        h.run()
        assertEquals(listOf(AutomationTrigger.BEFORE_ACCEPT, AutomationTrigger.BEFORE_COMMIT), seen)
        assertEquals(TaskState.BLOCKED, h.orchestrator.tasks.tasks.getValue(task).state)
        assertTrue(h.orchestrator.mergesState.pending.isEmpty())
    }
}
