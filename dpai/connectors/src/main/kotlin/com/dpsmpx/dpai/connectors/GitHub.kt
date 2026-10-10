package com.dpsmpx.dpai.connectors

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome

/**
 * GitHub через официальный клиент `gh` (ТЗ, §30; `docs/ECOSYSTEM.md`): вход
 * — у самого `gh` пользователя, оркестратор токенов не хранит (D-14).
 * Здесь — только команды; исполняет их процесс в `execution` с рабочим
 * каталогом — деревом задачи. В V1 — PR и его состояние, не замена
 * GitHub (`docs/direction/V1_SCOPE.md`, §3).
 */
object GitHubCommands {
    private val BRANCH = Regex("[A-Za-z0-9._/-]{1,200}")

    /** `gh pr create`: база, ветка, заголовок, описание; запускать в дереве задачи. */
    fun createPr(base: String, head: String, title: String, body: String): Outcome<List<String>> {
        if (!validBranch(base) || !validBranch(head)) return Outcome.failure(DiagCode.VALUE_INVALID, "имя ветки недопустимо", "github")
        if (title.isBlank() || title.length > 256 || title.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "заголовок PR — 1…256 знаков без управляющих", "github")
        }
        return Outcome.success(listOf("gh", "pr", "create", "--base", base, "--head", head, "--title", title, "--body", body))
    }

    /** `gh pr view` — состояние PR в машинно читаемом виде. */
    fun prState(head: String): Outcome<List<String>> =
        if (!validBranch(head)) {
            Outcome.failure(DiagCode.VALUE_INVALID, "имя ветки недопустимо", "github")
        } else {
            Outcome.success(listOf("gh", "pr", "view", head, "--json", "number,state,url,mergeable"))
        }

    /** Ссылка на созданный PR — строка `https://…` в выводе `gh pr create`. */
    fun prUrl(stdout: List<String>): Outcome<String> =
        stdout.lastOrNull { it.trim().startsWith("https://") }?.trim()?.let { Outcome.success(it) }
            ?: Outcome.failure(DiagCode.CONNECTOR_REPLY_INVALID, "gh не вернул ссылку на PR", "github")

    private fun validBranch(b: String) = BRANCH.matches(b) && !b.startsWith("-") && ".." !in b
}
