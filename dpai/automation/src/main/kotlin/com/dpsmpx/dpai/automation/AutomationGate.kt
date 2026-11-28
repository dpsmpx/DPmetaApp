package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.ids.ProjectId
import com.dpsmpx.dpai.core.ids.TaskId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.verification.VerificationReport
import com.dpsmpx.dpai.verification.VerifyLevel

/** Владелец рабочего дерева предоставляет прежний Verifier, не отдаёт дерево ручному worker. */
data class AutomationGateRequest(val trigger: AutomationTrigger, val project: ProjectId, val task: TaskId,
    val root: String, val event: String, val fingerprint: String)

fun interface AutomationGate {
    fun before(request: AutomationGateRequest, verify: (VerifyLevel) -> VerificationReport): Outcome<Unit>

    companion object {
        val NONE = AutomationGate { _, _ -> Outcome.success(Unit) }
    }
}
