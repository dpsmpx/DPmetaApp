package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.clock.Timestamp

/** Существующая служба подаёт время; пропущенные интервалы не запускаются пачкой (D-222). */
object WorkflowSchedule {
    fun events(workflow: Workflow, fingerprint: String, now: Timestamp): List<AutomationEvent> =
        if (!workflow.enabled || now.epochMillis < 0) emptyList() else workflow.triggers.mapNotNull { t ->
            val seconds = t.everySeconds ?: return@mapNotNull null
            if (t.type != AutomationTrigger.SCHEDULE || seconds !in 60..31_536_000) return@mapNotNull null
            val bucket = now.epochMillis / (seconds * 1000)
            AutomationEvent("schedule:" + workflow.id + ":" + seconds + ":" + bucket,
                AutomationTrigger.SCHEDULE, workflow.context, fingerprint)
        }
}
