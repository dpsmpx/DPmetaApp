package com.dpsmpx.dpai.automation

import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.verification.VerificationProfile
import com.dpsmpx.dpai.verification.VerifyLevel

/** Никаких выдуманных FAST/FULL: отсутствие уровня остаётся видимым отсутствием (D-215). */
object VerificationAutomations {
    fun describe(profile: VerificationProfile?, root: String): List<AutomationDefinition> = VerifyLevel.entries.map { level ->
        val steps = profile?.levels?.get(level).orEmpty()
        val source = JsonObject.of("project" to JsonString(profile?.project?.value.orEmpty()), "level" to JsonString(level.name),
            "steps" to JsonArray(steps.map { s -> JsonObject.of("name" to JsonString(s.name), "command" to JsonArray(s.command.map(::JsonString)),
                "required" to JsonBool(s.required), "timeout" to JsonNumber.of(s.timeout.inWholeMilliseconds)) }))
        AutomationDefinition("verification.${level.name.lowercase()}", level.name, "Проверка задачи по существующему профилю; итог не заменяет приёмку всей задачи.",
            AutomationSource.VERIFICATION, AutomationCategory.CHECK, Hashing.sha256(source).hex, ActionLevel.REVERSIBLE,
            listOf(root, "project:registered-worktrees"), if (steps.all { it.timeout.inWholeSeconds in 1..86400 && it.command.size <= 128 }) AutomationInvocation.DIRECT else AutomationInvocation.OPEN_UI,
            steps.isNotEmpty(), if (steps.isEmpty()) "уровень проверки не настроен" else null,
            timeoutSeconds = steps.sumOf { it.timeout.inWholeSeconds }.takeIf { it > 0 },
            verification = "Verifier и доказательства прежних ручных запусков; не меняет состояние задачи",
            cancellable = true, triggers = setOf("MANUAL", "AI", "TASK_VERIFICATION"), steps = steps)
    }
}
