package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.orchestrator.linux.LinuxEnvironment

/** Точный план D-216; это профиль штатного исполнителя, не собственная очередь. */
object EnvironmentPlan {
        val packageActions = setOf(LinuxEnvironment.Apk.ADD, LinuxEnvironment.Apk.FIX, LinuxEnvironment.Apk.REPAIR,
            LinuxEnvironment.Apk.UPGRADE_SOME, LinuxEnvironment.Apk.UPDATE, LinuxEnvironment.Apk.LIST_UPGRADES)

        fun create(id: String, values: Map<String, String>, broken: List<String> = emptyList()): Outcome<AutomationPlan> {
            if (id.startsWith("environment.apk.")) {
                val action = LinuxEnvironment.Apk.entries.firstOrNull { "environment.apk.${it.name.lowercase()}" == id && it in packageActions }
                    ?: return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "операция требует прежнего экрана", id)
                val packages = values["packages"].orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val spec = when (val result = LinuxEnvironment.apk(action, packages)) { is Outcome.Failure -> return result; is Outcome.Success -> result.value }
                val read = action == LinuxEnvironment.Apk.LIST_UPGRADES
                val changesPackages = action !in setOf(LinuxEnvironment.Apk.UPDATE, LinuxEnvironment.Apk.LIST_UPGRADES)
                val repair = if (changesPackages && action != LinuxEnvironment.Apk.REPAIR && broken.isNotEmpty()) {
                    val fix = when (val result = LinuxEnvironment.apk(LinuxEnvironment.Apk.REPAIR, broken)) { is Outcome.Failure -> return result; is Outcome.Success -> result.value }
                    listOf(ManualStep("Восстановить повреждённые пакеты", fix.command, 900))
                } else emptyList()
                return Outcome.success(AutomationPlan(id, "apk ${action.name.lowercase()}", if (read) ActionLevel.READ else ActionLevel.REVERSIBLE,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, repair + ManualStep("Пакеты Alpine", spec.command, 900) +
                        if (read) emptyList() else ToolchainDoctor.forPackages(packages).map { ManualStep("Проверить ${it.name}", it.command, it.seconds.toLong()) }), snapshot = !read))
            }
            if (id == "environment.android_jdk") {
                return Outcome.success(AutomationPlan(id, "Android-native JDK 21", ActionLevel.REVERSIBLE,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(
                        ManualStep("Пакеты загрузки JDK", LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, AndroidJdk.packages).valueOrNull()!!.command, 900),
                        ManualStep("Установить JDK Android ARM64 по SHA-256", AndroidJdk.install(), 1800),
                        ManualStep("Проверить запуск Java и компилятора", AndroidJdk.probe(), 60))),
                    estimatedBytes = 512L shl 20))
            }
            if (id == "environment.android_sdk") {
                val ndk = values["ndk"] == "yes"
                val api = values["api"] ?: AndroidSdk.PLATFORM.substringAfter("android-")
                val tools = values["buildTools"] ?: AndroidSdk.BUILD_TOOLS.substringAfter(';')
                if (!Regex("(?:[2-9][0-9])(?:\\.[0-9]{1,2})?").matches(api) || !Regex("[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{1,2}").matches(tools))
                    return Outcome.failure(DiagCode.VALUE_INVALID, "нужен номер API 20…99 (можно 37.0) и точная версия build-tools")
                return Outcome.success(AutomationPlan(id, "Android SDK", ActionLevel.REVERSIBLE,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(
                        ManualStep("Пакеты для Android SDK", LinuxEnvironment.apk(LinuxEnvironment.Apk.ADD, AndroidSdk.packages).valueOrNull()!!.command, 900),
                        ManualStep("Установить Android-native JDK 21", AndroidJdk.install(), 1800),
                        ManualStep("Проверить JVM для SDK", ToolchainDoctor.select("java", emptyList()).valueOrNull()!!.single().command, 30),
                        ManualStep("Установить закреплённые Android SDK tools", AndroidSdk.install(true, ndk, null, null, "platforms;android-$api", "build-tools;$tools"), 3600),
                        ManualStep("Проверить запрошенные Android SDK tools", AndroidSdk.probe("platforms;android-$api", "build-tools;$tools", ndk), 60))),
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
                return Outcome.success(AutomationPlan(id, "Команда Linux", ActionLevel.DANGEROUS,
                    ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(ManualStep("Подтверждённая команда", args, timeout.toLong()))), snapshot = values["snapshot"] != "skip"))
            }
            return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "неизвестный план окружения", id)
        }
}
