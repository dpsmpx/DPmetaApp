package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.model.RepoRef

/**
 * Issue из пульта (F-26; D-07): задача, записанная с телефона, — уровень 1, окно «что
 * уйдёт» и одно нажатие. Метку `from-dpmetaapp` ставит исполнитель. Чистый Kotlin.
 */
object IssueActions {
    /** Предел заголовка: GitHub принимает до 256 знаков. */
    const val MAX_TITLE: Int = 256

    /** Почему отправить нельзя; `null` — можно. */
    fun invalid(title: String): String? = when {
        title.isBlank() -> "нужен заголовок"
        title.trim().length > MAX_TITLE -> "заголовок длиннее $MAX_TITLE знаков"
        else -> null
    }

    fun command(repo: RepoRef, title: String, body: String): ActionCommand =
        ActionCommand(ActionKind.ISSUE_OPEN, ActionTarget(repo), mapOf("title" to title.trim(), "body" to body.trim()))

    fun preview(c: ActionCommand): List<String> = listOfNotNull(
        "Новый issue в ${c.target.repo} с меткой «from-dpmetaapp»:",
        c.params["title"],
        c.params["body"]?.takeIf { it.isNotEmpty() },
    )
}
