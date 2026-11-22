package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.formats.CommandRegistry
import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.verification.VerifyStep
import kotlin.time.Duration.Companion.minutes

/** Только адаптер разобранного реестра; исполнение/подтверждение остаётся у CommandActions. */
object CommandAutomations {
    fun describe(registry: CommandRegistry, root: String, inApp: Boolean): List<AutomationDefinition> = registry.commands.map { c ->
        val source = JsonObject.of("id" to JsonString(c.id), "project" to JsonString(c.project), "workdir" to JsonString(c.workdir.orEmpty()),
            "path" to JsonString(c.path), "args" to JsonArray(c.args.map(::JsonString)), "plan" to JsonString(c.plan.code),
            "level" to JsonNumber.of(c.level), "packages" to JsonArray(c.packages.map(::JsonString)),
            "timeout" to JsonNumber.of(c.timeoutMinutes), "expect" to JsonString(c.expect.orEmpty()))
        AutomationDefinition("pult.${c.id}", c.title, c.expect ?: "Команда пульта в выбранном клоне проекта.",
            AutomationSource.PULT, AutomationCategory.REPOSITORY, Hashing.sha256(source).hex,
            if (c.level == 0) ActionLevel.READ else ActionLevel.REVERSIBLE, listOf(root.trimEnd('/') + c.workdir?.let { "/$it" }.orEmpty()),
            AutomationInvocation.OPEN_UI, inApp, if (!inApp) "команда пульта исполняется в Linux приложения" else "пульт проверит выбранный клон и пакеты перед запуском",
            platforms = setOf(TargetPlatform.POSIX), dependencies = c.packages, timeoutSeconds = c.timeoutMinutes * 60L,
            remote = false, cancellable = true, verification = "Фактический код выхода и журнал CommandActions",
            steps = listOf(VerifyStep(c.title, listOf(c.path) + c.args, timeout = c.timeoutMinutes.minutes)))
    }
}
