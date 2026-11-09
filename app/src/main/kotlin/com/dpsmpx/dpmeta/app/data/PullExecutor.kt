package com.dpsmpx.dpmeta.app.data

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionResult
import com.dpsmpx.dpmeta.github.GitHubWrite
import com.dpsmpx.dpmeta.github.Written

/**
 * Исполнитель действий с PR пульта (F-12): вид команды → запрос [GitHubWrite]. Политику
 * и журнал проходит раньше `:core:actions`; сюда команда приходит уже подтверждённой.
 * Чего в пульте ещё нет (файлы, ветки, настройки репозитория) — отказ `UNSUPPORTED`.
 */
object PullExecutor {
    fun execute(w: GitHubWrite, c: ActionCommand): Outcome<ActionResult> {
        val repo = c.target.repo
        val number = c.params["number"]?.toIntOrNull()
            ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет номера PR", c.target.display)
        val r: Outcome<Written> = when (c.kind) {
            ActionKind.COMMENT -> w.comment(repo, number, c.params["body"].orEmpty())
            ActionKind.REVIEW -> w.review(repo, number, approve = c.params["event"] == "approve", body = c.params["body"].orEmpty())
            ActionKind.PR_READY -> w.pull(repo, number).then { w.ready(it) }
            ActionKind.PR_MERGE -> w.merge(repo, number, c.params["head"].orEmpty())
            ActionKind.PR_CLOSE -> w.pull(repo, number).then { w.close(it) }
            else -> return Outcome.failure(DiagCode.UNSUPPORTED, "в пульте этого действия пока нет: ${c.kind.what}", c.target.display)
        }
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(ActionResult(done(c), r.value.link))
        }
    }

    private fun done(c: ActionCommand): String = when (c.kind) {
        ActionKind.COMMENT -> "комментарий отправлен"
        ActionKind.REVIEW -> if (c.params["event"] == "approve") "PR одобрен" else "запрошены изменения"
        ActionKind.PR_READY -> "PR — из черновика"
        ActionKind.PR_MERGE -> "PR влит"
        ActionKind.PR_CLOSE -> "PR закрыт"
        else -> "готово"
    }
}
