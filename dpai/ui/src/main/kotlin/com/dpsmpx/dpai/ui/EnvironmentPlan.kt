package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment
import com.dpsmpx.dpai.security.Proposal

/** Точный план D-216; это профиль штатного исполнителя, не собственная очередь. */
data class EnvironmentPlan(val id: String, val title: String, val level: ActionLevel, val profile: ManualProfile,
    val snapshot: Boolean = true, val sdkLicense: Boolean = false, val estimatedBytes: Long? = null) {
    fun proposal(project: ProjectEntry, contextRevision: String): Proposal = Proposal(id, project.project.value,
        listOf(project.root, project.target.value, contextRevision, CanonicalJson.write(profile.toJson()), snapshot.toString(), sdkLicense.toString()))

    companion object {
        val packageActions = setOf(LinuxEnvironment.Apk.ADD, LinuxEnvironment.Apk.FIX, LinuxEnvironment.Apk.REPAIR,
            LinuxEnvironment.Apk.UPGRADE_SOME, LinuxEnvironment.Apk.UPDATE, LinuxEnvironment.Apk.LIST_UPGRADES)

        fun create(id: String, values: Map<String, String>, broken: List<String> = emptyList()): Outcome<EnvironmentPlan> {
            if (id.startsWith("environment.apk.")) {
                val action = LinuxEnvironment.Apk.entries.firstOrNull { "environment.apk.${it.name.lowercase()}" == id && it in packageActions }
                    ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "операция требует прежнего экрана", id)
                val packages = values["packages"].orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val spec = when (val result = LinuxEnvironment.apk(action, packages)) { is Outcome.Failure -> return result; is Outcome.Success -> result.value }
                val read = action in setOf(LinuxEnvironment.Apk.UPDATE, LinuxEnvironment.Apk.LIST_UPGRADES)
                val repair = if (!read && action != LinuxEnvironment.Apk.REPAIR && broken.isNotEmpty()) {
                    val fix = when (val result = LinuxEnvironment.apk(LinuxEnvironment.Apk.REPAIR, broken)) { is Outcome.Failure -> return result; is Outcome.Success -> result.value }
                    listOf(ManualStep("Восстановить повреждённые пакеты", fix.command, 900))
                } else emptyList()
                return Outcome.success(EnvironmentPlan(id, "apk ${action.name.lowercase()}", if (read) ActionLevel.READ else ActionLevel.REVERSIBLE,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, repair + ManualStep("Пакеты Alpine", spec.command, 900)), snapshot = !read))
            }
            if (id == "environment.android_sdk") {
                val ndk = values["ndk"] == "yes"
                val api = values["api"] ?: AndroidSdk.PLATFORM.substringAfter("android-")
                val tools = values["buildTools"] ?: AndroidSdk.BUILD_TOOLS.substringAfter(';')
                if (api.toIntOrNull() !in 21..99 || !Regex("[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}").matches(tools))
                    return Outcome.failure(DiagCode.VALUE_INVALID, "нужны номер API 21…99 и точная версия build-tools")
                return Outcome.success(EnvironmentPlan(id, "Android SDK", ActionLevel.REVERSIBLE,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(
                        ManualStep("Пакеты для Android SDK", LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, AndroidSdk.packages).valueOrNull()!!.command, 900),
                        ManualStep("Установить закреплённые Android SDK tools", AndroidSdk.install(true, ndk, null, null, "platforms;android-$api", "build-tools;$tools"), 3600))),
                    sdkLicense = true, estimatedBytes = AndroidSdk.spaceBytes(ndk)))
            }
            if (id == "environment.command") {
                val text = values["argv"].orEmpty()
                val array = JsonParser().parse(text).valueOrNull() as? JsonArray
                    ?: return Outcome.failure(DiagCode.VALUE_INVALID, "argv — JSON-массив строк, например [\"java\",\"-version\"]")
                val args = array.items.map { (it as? JsonString)?.value ?: return Outcome.failure(DiagCode.FIELD_TYPE, "в argv допустимы только строки") }
                if (args.isEmpty() || args.size > 64 || args.first().isBlank() || args.any { it.length > 4096 || '\u0000' in it } ||
                    Redactor.STANDARD.redact(text) != text) return Outcome.failure(DiagCode.VALUE_INVALID, "неверные аргументы или секрет в командной строке")
                val timeout = values["timeoutSeconds"]?.toIntOrNull() ?: if ("timeoutSeconds" in values) return Outcome.failure(DiagCode.VALUE_INVALID, "таймаут — целое число секунд") else 120
                if (timeout !in 1..3600) return Outcome.failure(DiagCode.VALUE_INVALID, "таймаут — от 1 до 3600 секунд")
                return Outcome.success(EnvironmentPlan(id, "Команда Linux", ActionLevel.DANGEROUS,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(ManualStep("Подтверждённая команда", args, timeout.toLong()))), snapshot = values["snapshot"] != "skip"))
            }
            return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "неизвестный план окружения", id)
        }
    }
}
