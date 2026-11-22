package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.project.DependencyTools

/** Одна фабрика для прежней панели IDE и общего вызова, без второго менеджера пакетов (D-217). */
object DependencyProfiles {
    fun create(manager: DependencyTools.Manager, mode: DependencyTools.Mode, python: String, androidSdk: Boolean,
        name: String = "${manager.name}: ${mode.name}"): Outcome<ManualProfile> {
        if (python.isBlank() || python.length > 1000 || python.any(Char::isISOControl))
            return Outcome.failure(DiagCode.VALUE_INVALID, "нужен путь к интерпретатору Python без управляющих знаков")
        if (mode == DependencyTools.Mode.RESTORE && manager != DependencyTools.Manager.PYTHON)
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "восстановление по lock-файлу доступно для Python")
        if (androidSdk && manager != DependencyTools.Manager.GRADLE)
            return Outcome.failure(DiagCode.VALUE_INVALID, "Android SDK подключается только для Gradle")
        val profile = ManualProfile(ManualProfileKind.DEPENDENCIES, listOf(ManualStep(name, DependencyTools.command(manager, mode, python), 3600)),
            needs = if (androidSdk) setOf(ManualNeed.ANDROID_SDK) else emptySet())
        return if (profile.valid) Outcome.success(profile) else Outcome.failure(DiagCode.VALUE_INVALID, "профиль зависимостей выходит за допустимые пределы")
    }
}
