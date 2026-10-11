package com.dpsmpx.dpai.orchestrator.recovery

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.git.GitStatus
import com.dpsmpx.dpai.orchestrator.git.GitService

/**
 * Проба рабочего дерева задачи (`docs/RECOVERY.md`, §4): дерево пропало
 * — `WORKTREE_MISSING`; в дереве конфликт слияния — `MERGE_CONFLICT`.
 * Оба — причина `BLOCKED` задачи; дерево не «чинится» само: незакоммиченное
 * можно потерять (решение пользователя).
 */
class WorktreeProbe(private val git: GitService) {
    /** `null` — дерево в порядке; иначе — причина остановить задачу. */
    fun check(path: String): Diagnostic? = when (val s = git.status(path)) {
        is Outcome.Failure -> Diagnostic.of(DiagCode.WORKTREE_MISSING, "рабочее дерево недоступно", path, causes = s.errors)
        is Outcome.Success -> conflicts(s.value, path)
    }

    private fun conflicts(s: GitStatus, path: String): Diagnostic? =
        if (s.conflicted.isEmpty()) null else Diagnostic.error(DiagCode.MERGE_CONFLICT, "конфликт слияния: ${s.conflicted.joinToString()}", path)
}
