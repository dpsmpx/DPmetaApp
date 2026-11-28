package com.dpsmpx.dpai.ui

/** Прежние имена страниц остаются маршрутами и после группировки (DPmetaApp:D-194). */
object WorkspacePresentation {
    val primary = listOf("FILES", "AI", "CHANGES")
    val operations = listOf("CHECKS", "BUILD", "RUN", "DEPENDENCIES", "AUTOMATIONS")

    data class Place(val primary: String, val operation: String? = null)

    fun restore(name: String): Place = when (name) {
        in primary -> Place(name)
        in operations -> Place("FILES", name)
        else -> Place("FILES")
    }
}
