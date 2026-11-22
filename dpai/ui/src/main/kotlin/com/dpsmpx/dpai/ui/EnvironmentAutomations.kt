package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment

/** Операции берутся из нынешнего enum apk; второй менеджер пакетов не создаётся (D-215). */
object EnvironmentAutomations {
    fun describe(inApp: Boolean, installed: Boolean, root: String): List<AutomationDefinition> {
        val available = inApp && installed
        val reason = when { !inApp -> "диагностика Alpine относится к Linux этого приложения"; !installed -> "Linux приложения не установлен"; else -> null }
        return listOf(AutomationDefinition("environment.inspect", "Диагностика окружения", "База Alpine, несовместимости пакетов и потребности выбранного проекта без сети или процессов.",
            AutomationSource.ENVIRONMENT, AutomationCategory.ENVIRONMENT, Hashing.sha256("AlpinePackages.read/problems;ProjectNeeds:1").hex,
            ActionLevel.READ, listOf(root), AutomationInvocation.DIRECT, available, reason, platforms = setOf(TargetPlatform.POSIX),
            remote = false, deterministic = true, idempotent = true, verification = "Факты из базы apk; отсутствие данных не является успехом", triggers = setOf("MANUAL", "AI"))) +
            LinuxEnvironment.Apk.entries.map { op -> AutomationDefinition("environment.apk.${op.name.lowercase()}", op.name,
                "Прежняя операция менеджера пакетов; выбор пакетов, снимок и подтверждение — на экране окружения.",
                AutomationSource.ENVIRONMENT, AutomationCategory.ENVIRONMENT, op.descriptionHash, ActionLevel.REVERSIBLE, listOf(root),
                AutomationInvocation.OPEN_UI, available, reason, platforms = setOf(TargetPlatform.POSIX), dependencies = listOf("apk"),
                remote = false, cancellable = false, verification = "Код apk и сверка новой базы со снимком") }
    }
}
