package com.dpsmpx.dpmeta.model

import com.dpsmpx.dpai.core.git.RepoRef

/** Проект синхронизации из списка пользователя: собственный репозиторий и выбранные пути. */
data class SyncProject(
    val name: String,
    val repo: RepoRef,
    val docs: List<String> = emptyList(),
    val checklists: List<String> = emptyList(),
)

/** Каталог текущего прогона; порядок задаёт пользователь, ролей и скрытых групп нет (D-176). */
data class ProjectCatalog(val projects: List<SyncProject>) {
    fun byName(name: String): SyncProject? = projects.firstOrNull { it.name == name }
}
