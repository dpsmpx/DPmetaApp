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
 * Исполнитель действий пульта (F-12, F-14): вид команды → запросы [GitHubWrite]. Политику
 * и журнал проходит раньше `:core:actions`; сюда команда приходит уже подтверждённой.
 * Чего в пульте ещё нет (перезапись истории, настройки репозитория) — отказ `UNSUPPORTED`. Issue (F-26) —
 * с меткой [ISSUE_LABEL].
 */
object ActionExecutor {
    /** Метка issue, записанного с телефона (ТЗ, F-26). */
    const val ISSUE_LABEL: String = "from-dpmetaapp"

    /**
     * [text] — новый текст файла для правки (F-69): в команде только его SHA-256, сам текст
     * идёт сюда отдельно и сверяется с ней.
     */
    fun execute(w: GitHubWrite, c: ActionCommand, text: String? = null): Outcome<ActionResult> {
        val r: Outcome<Written> = when (c.kind) {
            ActionKind.COMMENT, ActionKind.REVIEW, ActionKind.PR_READY, ActionKind.PR_MERGE, ActionKind.PR_CLOSE -> pull(w, c)
            ActionKind.BRANCH_DELETE_MERGED, ActionKind.BRANCH_DELETE_UNMERGED -> deleteBranch(w, c)
            // F-26: метка — всегда своя, из кода, а не из параметров.
            ActionKind.ISSUE_OPEN -> w.issue(c.target.repo, c.params["title"].orEmpty(), c.params["body"].orEmpty(), listOf(ISSUE_LABEL))
            ActionKind.FILES_COMMIT_PR, ActionKind.COMMIT_DEFAULT -> edit(w, c, text)
            else -> return Outcome.failure(DiagCode.UNSUPPORTED, "в пульте этого действия пока нет: ${c.kind.what}", c.target.display)
        }
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> Outcome.success(ActionResult(done(c, r.value), r.value.link))
        }
    }

    private fun pull(w: GitHubWrite, c: ActionCommand): Outcome<Written> {
        val repo = c.target.repo
        val number = c.params["number"]?.toIntOrNull()
            ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет номера PR", c.target.display)
        return when (c.kind) {
            ActionKind.COMMENT -> w.comment(repo, number, c.params["body"].orEmpty())
            ActionKind.REVIEW -> w.review(repo, number, approve = c.params["event"] == "approve", body = c.params["body"].orEmpty())
            ActionKind.PR_READY -> w.pull(repo, number).then { w.ready(it) }
            ActionKind.PR_MERGE -> w.merge(repo, number, c.params["head"].orEmpty())
            else -> w.pull(repo, number).then { w.close(it) }
        }
    }

    /**
     * Правка файла (F-69; D-36). Текст — тот, на который дано подтверждение (SHA-256), запись —
     * поверх показанного блоба: чужая правка на GitHub — 409, отказ, ничего не затёрто. В ветку
     * с PR: ветка от головы основной, коммит, PR; коммит не удался — пустая ветка удаляется.
     */
    private fun edit(w: GitHubWrite, c: ActionCommand, text: String?): Outcome<Written> {
        if (text == null || com.dpsmpx.dpmeta.app.screens.EditActions.sha256(text) != c.params["content_sha256"]) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "текст правки не тот, что был показан — не отправлено", c.target.display)
        }
        val repo = c.target.repo
        val path = c.params["path"].orEmpty()
        val blob = c.params["blob"].orEmpty()
        val base = c.params["base"].orEmpty()
        val message = c.params["message"].orEmpty()
        val branch = c.target.branch ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет ветки", c.target.display)
        if (c.kind == ActionKind.COMMIT_DEFAULT) return w.putFile(repo, path, branch, blob, text, message)
        val head = when (val r = w.branch(repo, base)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value.sha
        }
        w.createBranch(repo, branch, head).let { if (it is Outcome.Failure) return it }
        val commit = w.putFile(repo, path, branch, blob, text, message)
        if (commit is Outcome.Failure) {
            w.deleteBranch(repo, branch)
            return commit
        }
        val title = message.lineSequence().first().take(200)
        val body = "Правка `$path` с телефона (DPmetaApp, пульт).\n\n$message"
        return w.openPull(repo, branch, base, title, body)
    }

    /**
     * Удалить ветку (F-14; D-36, D-37). Перед удалением — всё заново, а не по экрану:
     * основную и защищённую не трогаем; голова — та, что была показана (иначе в ветке
     * новое, чего человек не видел); «влитая» (уровень 2) — и сейчас без коммитов
     * впереди основной, иначе это удаление уровня 3. Итог — с SHA головы: по нему ветку
     * можно восстановить.
     */
    private fun deleteBranch(w: GitHubWrite, c: ActionCommand): Outcome<Written> {
        val repo = c.target.repo
        val name = c.target.branch ?: return Outcome.failure(DiagCode.FIELD_MISSING, "нет имени ветки", c.target.display)
        val base = when (val r = w.defaultBranch(repo)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (name == base) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "основную ветку пульт не удаляет", c.target.display)
        val branch = when (val r = w.branch(repo, name)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        if (branch.protected) return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "ветка защищена правилами — GitHub её не удалит", c.target.display)
        if (branch.sha != c.params["head"]) {
            return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "в ветке новый коммит с тех пор, как она была на экране — откройте её заново", c.target.display)
        }
        if (c.kind == ActionKind.BRANCH_DELETE_MERGED) {
            val cmp = when (val r = w.compare(repo, base, name)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> r.value
            }
            if (!cmp.merged) {
                return Outcome.failure(DiagCode.OPERATION_NOT_ALLOWED, "в ветке ${cmp.aheadBy} невлитых коммитов — удаление уровня 3, с вводом имени", c.target.display)
            }
        }
        return w.deleteBranch(repo, name).then { Outcome.success(Written(null, branch.sha)) }
    }

    private fun done(c: ActionCommand, w: Written): String = when (c.kind) {
        ActionKind.COMMENT -> "комментарий отправлен"
        ActionKind.REVIEW -> if (c.params["event"] == "approve") "PR одобрен" else "запрошены изменения"
        ActionKind.PR_READY -> "PR — из черновика"
        ActionKind.PR_MERGE -> "PR влит"
        ActionKind.PR_CLOSE -> "PR закрыт"
        ActionKind.ISSUE_OPEN -> "issue создан"
        ActionKind.FILES_COMMIT_PR -> "правка — в ветке ${c.target.branch}, открыт PR"
        ActionKind.COMMIT_DEFAULT -> "коммит в ${c.target.branch}: ${w.sha?.take(7).orEmpty()}"
        ActionKind.BRANCH_DELETE_MERGED, ActionKind.BRANCH_DELETE_UNMERGED ->
            "ветка ${c.target.branch} удалена; её последний коммит ${w.sha.orEmpty()} — по нему её можно восстановить"
        else -> "готово"
    }
}
