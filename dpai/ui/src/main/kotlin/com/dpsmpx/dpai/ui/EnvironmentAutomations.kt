package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.automation.AutomationParameter
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
                AutomationSource.ENVIRONMENT, AutomationCategory.ENVIRONMENT, Hashing.sha256(op.descriptionHash + ToolchainDoctor.sourceHash + "EnvironmentPlan:2").hex,
                if (op in setOf(LinuxEnvironment.Apk.UPDATE, LinuxEnvironment.Apk.LIST_UPGRADES)) ActionLevel.READ else ActionLevel.REVERSIBLE, listOf(root),
                if (op in EnvironmentPlan.packageActions) AutomationInvocation.DIRECT else AutomationInvocation.OPEN_UI, available, reason,
                parameters = if (op in setOf(LinuxEnvironment.Apk.ADD, LinuxEnvironment.Apk.FIX, LinuxEnvironment.Apk.REPAIR, LinuxEnvironment.Apk.UPGRADE_SOME))
                    listOf(AutomationParameter("packages", "Имена пакетов через пробел; например openjdk21-jdk python3 git", emptyList(), text = true, required = true)) else emptyList(),
                platforms = setOf(TargetPlatform.POSIX), dependencies = listOf("apk"),
                remote = false, cancellable = op in EnvironmentPlan.packageActions, verification = "Код apk и сверка новой базы со снимком") } + listOf(
                AutomationDefinition("environment.probe", "Проверить инструменты запуском", "Живые Java/JDK/Python/C++ и другие пробы. Не выдаёт наличие пакета за работающий инструмент.",
                    AutomationSource.ENVIRONMENT, AutomationCategory.ENVIRONMENT, ToolchainDoctor.sourceHash, ActionLevel.READ, listOf(root),
                    AutomationInvocation.DIRECT, available, reason, parameters = listOf(AutomationParameter("tool", "Какие инструменты проверить", ToolchainDoctor.choices, "project")),
                    platforms = setOf(TargetPlatform.POSIX), remote = false, cancellable = true, verification = "Реальный код и вывод каждой пробы"),
                AutomationDefinition("environment.command", "Команда в Linux приложения", "Произвольная команда с расширенным доступом, только после красного подтверждения и ввода имени проекта. Proot не песочница.",
                    AutomationSource.ENVIRONMENT, AutomationCategory.ENVIRONMENT, Hashing.sha256("EnvironmentPlan.command:1").hex, ActionLevel.DANGEROUS, listOf(root),
                    AutomationInvocation.DIRECT, available, reason, parameters = listOf(
                        AutomationParameter("argv", "JSON-массив аргументов, например [\"java\",\"-version\"]; для shell явно [\"/bin/sh\",\"-c\",\"...\"]", emptyList(), text = true, required = true),
                        AutomationParameter("timeoutSeconds", "Предел ожидания в секундах: 1…3600", emptyList(), "120", text = true, maxLength = 4),
                        AutomationParameter("snapshot", "Снимок обязателен; skip — только для восстановления при нечитаемой базе с отдельным опасным подтверждением", listOf("required", "skip"), "required")),
                    platforms = setOf(TargetPlatform.POSIX), remote = false, cancellable = true, verification = "История, реальный id и коды шагов подтверждённой команды"),
                AutomationDefinition("environment.android_sdk", "Установить Android SDK", "Закреплённый установщик; лицензию принимает человек. Официальные aapt2/NDK для Linux arm64 недоступны — это явно указано в выводе.",
                    AutomationSource.ENVIRONMENT, AutomationCategory.ENVIRONMENT, Hashing.sha256(AndroidSdk.CMDLINE_SHA256).hex, ActionLevel.REVERSIBLE, listOf(root),
                    AutomationInvocation.DIRECT, available, reason, parameters = listOf(AutomationParameter("ndk", "Также запросить NDK", listOf("no", "yes"), "no"),
                        AutomationParameter("api", "compileSdk проекта: номер Android API", emptyList(), AndroidSdk.PLATFORM.substringAfter("android-"), text = true, maxLength = 2),
                        AutomationParameter("buildTools", "Точная версия build-tools проекта", emptyList(), AndroidSdk.BUILD_TOOLS.substringAfter(';'), text = true, maxLength = 8)),
                    platforms = setOf(TargetPlatform.POSIX), remote = false, cancellable = true, verification = "Закреплённая сумма, код установщика и повторная диагностика")
            )
    }
}
