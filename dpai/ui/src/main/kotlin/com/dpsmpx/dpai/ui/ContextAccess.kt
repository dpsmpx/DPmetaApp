package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextProfile

/** Разрешаем только адаптеры, доказанно использующие отфильтрованный FileTree. Числовой READ не доказывает изоляцию. */
object ContextAccess {
    /** Старые сценарии сохраняют ревизии до первой явной настройки профиля человеком. */
    fun bind(entries: List<com.dpsmpx.dpai.automation.AutomationDefinition>, settings: ContextSettings,
        disabled: Set<String> = emptySet()): List<com.dpsmpx.dpai.automation.AutomationDefinition> = entries.map { entry ->
        val denied = !tool(settings.profile, entry.id)
        entry.copy(sourceHash = if (settings.explicit) com.dpsmpx.dpai.core.hashing.Hashing.sha256(entry.sourceHash + ":" + settings.profile.revision).hex else entry.sourceHash,
            available = entry.available && entry.id !in disabled && !denied,
            steps = if (denied) emptyList() else entry.steps, parameters = if (denied) emptyList() else entry.parameters,
            reason = if (entry.id in disabled) "инструмент выключен пользователем" else if (denied)
                "исполнитель не обеспечивает ограниченную область контекста; используйте файловые инструменты или измените профиль вручную" else entry.reason)
    }
    fun tool(profile: ContextProfile, id: String): Boolean = !profile.restricted || id in setOf(
        "context.task", "context.constraints", "context.expand", "development.structure", "development.find_files",
        "development.duplicates", "development.tests", "development.quality", "development.measurements", "development.artifact",
        "development.diagnostics")
}
