package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * «Опубликовать на GitHub» (D-150): проект с устройства получает закрытый репозиторий
 * владельца токена — действие уровня 1 с журналом (D-07) — и отправку ветки `main`
 * Git самого проекта. Чистый Kotlin.
 */
object PublishActions {
    private val NAME = Regex("[A-Za-z0-9._-]{1,100}")

    /** Почему имя репозитория не годится; `null` — годится. */
    fun invalid(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "нужно имя репозитория"
            !NAME.matches(n) || n == "." || n == ".." || n.endsWith(".git") -> "имя — латиница, цифры, «.», «_» и «-», до 100 знаков, без .git в конце"
            else -> null
        }
    }

    fun command(repo: RepoRef, project: Screens.LocalProject): ActionCommand =
        ActionCommand(ActionKind.REPO_CREATE, ActionTarget(repo), mapOf("project" to project.name, "place" to project.place))

    /** Адрес для `git remote` — только https GitHub. */
    fun url(repo: RepoRef): String = "https://github.com/$repo.git"

    /** Описание репозитория на GitHub. */
    fun description(c: ActionCommand): String = "Проект «${c.params["project"].orEmpty()}», созданный в DP_IDE"

    /** Что произойдёт — для окна подтверждения уровня 1. */
    fun preview(project: Screens.LocalProject, owner: String?, name: String): List<String> = listOf(
        "На GitHub — закрытый репозиторий ${owner ?: "<владелец токена>"}/${name.trim()}, пустой.",
        "В проекте «${project.name}» (${project.place}) — remote origin и отправка ветки main.",
        "Токену нужны права: создание репозиториев (Administration: write) и запись содержимого (Contents: write).",
    )

    /** Итог: ссылка на репозиторий и что с веткой. */
    data class Published(val link: String?, val note: String)
}
