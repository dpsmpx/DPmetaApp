package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.contexts.ContextProfile

/** Разрешаем только адаптеры, доказанно использующие отфильтрованный FileTree. Числовой READ не доказывает изоляцию. */
object ContextAccess {
    fun tool(profile: ContextProfile, id: String): Boolean = !profile.restricted || id in setOf(
        "context.task", "context.constraints", "context.expand", "development.structure", "development.find_files",
        "development.duplicates", "development.tests", "development.quality", "development.measurements", "development.artifact",
        "development.diagnostics")
}
