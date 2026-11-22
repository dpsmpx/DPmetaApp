package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCatalog
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString

/** Одинаковый путь/профиль на другом SSH-host не является прежней автоматизацией (D-215). */
object AutomationContext {
    fun bind(entries: List<AutomationDefinition>, project: ProjectEntry, target: TargetEntry): Outcome<AutomationCatalog> {
        if (project.target != target.id) return Outcome.failure(DiagCode.STATE_CONFLICT, "проект относится к другому target")
        val context = Hashing.sha256(JsonObject.of("project" to ProjectBook.toJson(project), "target" to TargetBook.toJson(listOf(target)))).hex
        return AutomationCatalog.of(entries.map { entry -> entry.copy(sourceHash = Hashing.sha256(JsonObject.of(
            "source" to JsonString(entry.sourceHash), "context" to JsonString(context))).hex) })
    }
}
