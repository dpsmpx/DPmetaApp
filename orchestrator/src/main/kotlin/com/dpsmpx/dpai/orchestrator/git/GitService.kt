package com.dpsmpx.dpai.orchestrator.git

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.WorktreeId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.git.GitStatus
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.security.Approval
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Git на target (ТЗ, §29–§31, §70): команды строит [GitCommands],
 * исполняет [host] — локальный или SSH (`git -C <дерево>`, рабочий
 * каталог процесса не нужен). Изменяющая операция — только от владельца
 * дерева ([Worktrees.checkWriter]); разрушительная — только с одобрением.
 */
class GitService(private val host: ProcessHost, private val commands: GitCommands, private val clock: Clock, private val timeout: Duration = 5.minutes) {
    /** Операция над деревом по пути; ненулевой код Git — `GIT_COMMAND_FAILED` с хвостом stderr. */
    fun run(op: GitOp, path: String, args: List<String> = emptyList(), approval: Approval? = null, maxStdoutChars: Int = Int.MAX_VALUE): Outcome<Completed> =
        commands.argv(op, path, args, clock.now(), approval).then { argv ->
            host.run(ProcessSpec(argv), timeout, maxStdoutChars = maxStdoutChars).then { done ->
                if (done.ok) {
                    Outcome.success(done)
                } else {
                    val tail = (done.stderr + done.stdout).lastOrNull { it.isNotBlank() }?.take(300) ?: "без вывода"
                    Outcome.failure(Diagnostic.error(DiagCode.GIT_COMMAND_FAILED, "git ${op.name.lowercase()}: код ${done.exitCode}: $tail", path))
                }
            }
        }

    fun status(path: String): Outcome<GitStatus> = run(GitOp.STATUS, path).then { GitStatus.parse(it.stdout) }

    /** Изменение дерева сессией: только владелец; чтение — любому. */
    fun write(state: WorktreesState, worktree: WorktreeId, session: SessionId, op: GitOp, args: List<String> = emptyList(), approval: Approval? = null): Outcome<Completed> {
        if (!op.writes) return Outcome.failure(DiagCode.VALUE_INVALID, "${op.name} не изменяет дерево: это чтение", "worktree $worktree")
        return Worktrees.checkWriter(state, worktree, session).then { w -> run(op, w.path, args, approval) }
    }
}
