package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.automation.AutomationCategory
import com.dpsmpx.dpai.automation.AutomationDefinition
import com.dpsmpx.dpai.automation.AutomationInvocation
import com.dpsmpx.dpai.automation.AutomationParameter
import com.dpsmpx.dpai.automation.AutomationSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.project.DependencyTools
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.files.FileTree

/** Штатные Gradle/pip/Conan из панели IDE становятся доступными через общий контракт (D-217). */
object DependencyAutomations {
    const val RUN = "dependencies.project"
    const val REPORT = "dependencies.report"
    const val MAX_REPORT_BYTES = 8L * 1024 * 1024
    private fun python(platform: TargetPlatform) = if (platform == TargetPlatform.WINDOWS) "python" else "python3"
    private fun manager(value: String?) = DependencyTools.Manager.entries.firstOrNull { it.code == value }

    fun describe(root: String, platform: TargetPlatform, inApp: Boolean): List<AutomationDefinition> = listOf(
        AutomationDefinition(RUN, "Зависимости проекта", "Прежние анализ/закрепление Gradle, pip и Conan 2; restore только для pip. План подтверждает человек, затем тот же ход получает результат.",
            AutomationSource.MANUAL, AutomationCategory.ENVIRONMENT, Hashing.sha256(DependencyTools.sourceHash + "DependencyAutomations.run:1").hex,
            ActionLevel.REVERSIBLE, listOf(root), AutomationInvocation.DIRECT, true,
            parameters = listOf(AutomationParameter("manager", "Менеджер проекта", DependencyTools.Manager.entries.map { it.code }, required = true),
                AutomationParameter("mode", "Действие; restore только для Python", DependencyTools.Mode.entries.map { it.code }, "report"),
                AutomationParameter("python", "Буквальный путь к Python на target; для pip — интерпретатор venv проекта", emptyList(), python(platform), text = true, maxLength = 1000),
                AutomationParameter("androidSdk", "Подключить SDK приложения для Gradle", if (inApp) listOf("no", "yes") else listOf("no"), "no")),
            platforms = setOf(platform), dependencies = listOf(python(platform)), timeoutSeconds = 3600, remote = true, cancellable = true,
            verification = "ManualProfiles: id, коды, вывод, история; файлы менеджера и Git diff", triggers = setOf("MANUAL", "AI")),
        AutomationDefinition(REPORT, "Прочитать отчёт зависимостей", "Только три штатных отчёта .dp-ide/dependencies. Страницы UTF-16; при продолжении передайте sha256 предыдущей страницы. Сохранённый отчёт не доказывает свежую проверку.",
            AutomationSource.MANUAL, AutomationCategory.CHECK, Hashing.sha256("DependencyAutomations.report:1").hex,
            ActionLevel.READ, listOf(root), AutomationInvocation.DIRECT, true,
            parameters = listOf(AutomationParameter("manager", "Менеджер отчёта", DependencyTools.Manager.entries.map { it.code }, required = true),
                AutomationParameter("offset", "Смещение UTF-16 в отредактированном тексте; начало 0", emptyList(), "0", text = true, maxLength = 8),
                AutomationParameter("sha256", "SHA-256 из предыдущей страницы; при изменении чтение нужно начать заново", emptyList(), text = true, maxLength = 64)),
            platforms = setOf(platform), remote = true, deterministic = true, idempotent = true,
            verification = "Фактический текст и SHA-256 отчёта, без вывода о готовности проекта", triggers = setOf("MANUAL", "AI")))

    fun plan(values: Map<String, String>, platform: TargetPlatform, inApp: Boolean): Outcome<AutomationPlan> {
        if (values.keys.any { it !in setOf("manager", "mode", "python", "androidSdk") }) return invalid()
        val manager = manager(values["manager"]) ?: return invalid()
        val mode = DependencyTools.Mode.entries.firstOrNull { it.code == (values["mode"] ?: "report") } ?: return invalid()
        val python = values["python"] ?: python(platform)
        if (ManualVars.names(python).isNotEmpty() || python.contains("\$\${") || Redactor.STANDARD.redact(python) != python) return invalid()
        val sdk = when (values["androidSdk"] ?: "no") { "no" -> false; "yes" -> true; else -> return invalid() }
        if (sdk && !inApp) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "SDK удалённого target настраивается на самом target")
        val profile = when (val r = DependencyProfiles.create(manager, mode, python, sdk)) { is Outcome.Failure -> return r; is Outcome.Success -> r.value }
        return Outcome.success(AutomationPlan(RUN, "${manager.name}: ${mode.name}", ActionLevel.REVERSIBLE, profile,
            snapshot = false, scope = AutomationPlan.Scope.PROJECT, effect = when (mode) {
                DependencyTools.Mode.REPORT -> AutomationPlan.Effect.DEPENDENCY_REPORT
                DependencyTools.Mode.PIN -> when (manager) {
                    DependencyTools.Manager.GRADLE -> AutomationPlan.Effect.GRADLE_PIN
                    DependencyTools.Manager.PYTHON -> AutomationPlan.Effect.PYTHON_PIN
                    DependencyTools.Manager.CONAN -> AutomationPlan.Effect.CONAN_PIN
                }
                DependencyTools.Mode.RESTORE -> AutomationPlan.Effect.PYTHON_RESTORE
            }))
    }

    fun read(tree: FileTree, values: Map<String, String>): Outcome<JsonObject> {
        if (values.keys.any { it !in setOf("manager", "offset", "sha256") }) return invalid()
        val manager = manager(values["manager"]) ?: return invalid()
        val offset = (values["offset"] ?: "0").toLongOrNull()?.takeIf { it >= 0 } ?: return invalid()
        val expected = values["sha256"]?.takeIf { it.isNotEmpty() }
        if (offset != 0L && expected == null || expected != null && !Regex("[a-f0-9]{64}").matches(expected)) return invalid()
        val path = ".dp-ide/dependencies/${manager.code}-report.json"
        return when (val r = ApiTextPage.read(tree, path, offset, MAX_REPORT_BYTES, expected, redact = true)) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(JsonObject(r.value.fields + mapOf("path" to JsonString(path),
                "storedReport" to JsonBool(true), "freshnessVerified" to JsonBool(false))))
        }
    }
    private fun <T> invalid(): Outcome<T> = Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры инструмента зависимостей")
}
