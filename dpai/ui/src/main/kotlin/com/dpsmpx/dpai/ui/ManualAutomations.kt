package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationParameter
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonNull
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.verification.VerifyStep
import kotlin.time.Duration.Companion.seconds

/** Адаптер прежних профилей; не меняет их документы и не исполняет argv (D-215). */
object ManualAutomations {
    fun describe(profiles: Map<ManualProfileKind, ManualProfile?>, run: RunProfile?, root: String,
        platform: TargetPlatform, selections: Map<ManualProfileKind, Map<String, String>> = emptyMap()): List<AutomationDefinition> = ManualProfileKind.entries.map { kind ->
        val profile = profiles[kind]
        val direct = kind in setOf(ManualProfileKind.CHECK, ManualProfileKind.BUILD)
        val title = when (kind) { ManualProfileKind.CHECK -> "Проверки"; ManualProfileKind.BUILD -> "Сборка"; ManualProfileKind.DEPENDENCIES -> "Зависимости" }
        AutomationDefinition("manual.${kind.name.lowercase()}", title, "Штатный профиль проекта. В Linux приложения Gradle/JVM проверяются живым запуском Java до команд; другие инструменты проверяйте через environment.probe.",
            AutomationSource.MANUAL, when (kind) { ManualProfileKind.CHECK -> AutomationCategory.CHECK; ManualProfileKind.BUILD -> AutomationCategory.BUILD; ManualProfileKind.DEPENDENCIES -> AutomationCategory.ENVIRONMENT },
            Hashing.sha256(JsonObject.of("profile" to (profile?.toJson() ?: JsonNull), "selections" to JsonObject(selections[kind].orEmpty().mapValues { com.dpsmpx.dpai.core.serialization.JsonString(it.value) }))).hex, ActionLevel.REVERSIBLE, listOf(root),
            if (direct) AutomationInvocation.DIRECT else AutomationInvocation.OPEN_UI, profile != null,
            if (profile == null) "профиль не настроен и не найден готовый вариант" else null,
            parameters = profile?.params.orEmpty().filter(::safe).map { AutomationParameter(it.name, Redactor.STANDARD.redact(it.title), it.choices.toList(), selections[kind]?.get(it.name)?.takeIf { value -> value in it.choices } ?: it.choices.first()) },
            platforms = setOf(platform), dependencies = profile?.needs.orEmpty().map { it.code }.sorted(),
            timeoutSeconds = profile?.steps?.sumOf { it.timeoutSeconds }, cancellable = true,
            verification = "Итоги шагов, id запуска, хеш вывода и история ManualProfiles",
            triggers = if (direct) setOf("MANUAL", "AI") else setOf("MANUAL"),
            steps = profile?.steps.orEmpty().map { VerifyStep(it.name, it.command, timeout = it.timeoutSeconds.seconds) })
    } + AutomationDefinition("manual.run", "Запуск", "Прежний запуск в реальном терминале, с вводом и остановкой пользователем.",
        AutomationSource.MANUAL, AutomationCategory.RUN, Hashing.sha256(run?.toJson() ?: JsonNull).hex, ActionLevel.REVERSIBLE,
        listOf(root), AutomationInvocation.OPEN_UI, run != null, if (run == null) "профиль запуска не настроен" else null,
        platforms = setOf(platform), cancellable = true, verification = "Вывод и состояние реального терминала")

    /** Секреты в вариантах не выдаются API. При пропуске параметра сохраняется прежний выбор пользователя. */
    private fun safe(p: ManualParam): Boolean = p.choices.all { value ->
        ManualVars.envProblem(p.name, value) == null && Redactor.STANDARD.redact(value) == value &&
            Redactor.STANDARD.redact(CanonicalJson.write(JsonObject.of(p.name to com.dpsmpx.dpai.core.serialization.JsonString(value)))) ==
            CanonicalJson.write(JsonObject.of(p.name to com.dpsmpx.dpai.core.serialization.JsonString(value)))
    }
}
