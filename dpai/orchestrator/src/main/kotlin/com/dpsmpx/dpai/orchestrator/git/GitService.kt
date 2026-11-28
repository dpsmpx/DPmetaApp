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
    fun run(op: GitOp, path: String, args: List<String> = emptyList(), approval: Approval? = null, maxStdoutChars: Int = 1024 * 1024,
        allowTruncated: Boolean = false, timeoutOverride: Duration? = null): Outcome<Completed> =
        commands.argv(op, path, args, clock.now(), approval).then { argv ->
            host.run(ProcessSpec(argv, stdin = if (op == GitOp.COMMIT) args.single() else null), timeoutOverride ?: timeout, maxStdoutChars = maxStdoutChars).then { done ->
                if (done.truncated && !op.writes && !allowTruncated) {
                    // Частичный diff/log не доказывает, что ревью увидело все изменения (O-05).
                    Outcome.failure(DiagCode.GIT_OUTPUT_INVALID, "вывод Git больше предела; полный результат неизвестен", path)
                } else if (done.ok) {
                    Outcome.success(done)
                } else {
                    val tail = (done.stderr + done.stdout).lastOrNull { it.isNotBlank() }?.take(300) ?: "без вывода"
                    Outcome.failure(Diagnostic.error(DiagCode.GIT_COMMAND_FAILED, "git ${op.name.lowercase()}: код ${done.exitCode}: $tail", path))
                }
            }
        }

    /**
     * Строки `путь:строка:текст` по [pattern] в путях [paths] дерева (D-171); код 1 Git —
     * «не нашлось», пустой список. [truncated] итога — вывод больше [maxChars].
     */
    fun grep(path: String, pattern: String, paths: List<String>, maxChars: Int, timeout: Duration = this.timeout): Outcome<Completed> =
        commands.argv(GitOp.GREP, path, listOf(pattern) + paths, clock.now()).then { argv ->
            host.run(ProcessSpec(argv), timeout, maxStdoutChars = maxChars).then { done ->
                when (done.exitCode) {
                    0 -> Outcome.success(done)
                    1 -> Outcome.success(done.copy(stdout = emptyList()))
                    else -> Outcome.failure(Diagnostic.error(DiagCode.GIT_COMMAND_FAILED, "git grep: код ${done.exitCode}: ${(done.stderr + done.stdout).lastOrNull { it.isNotBlank() }?.take(300) ?: "без вывода"}", path))
                }
            }
        }

    fun status(path: String): Outcome<GitStatus> = status(path, 1024 * 1024)

    /** Усечённый статус не доказывает отсутствие изменений/конфликтов (D-133). */
    fun status(path: String, maxChars: Int, timeoutOverride: Duration? = null): Outcome<GitStatus> = run(GitOp.STATUS, path, maxStdoutChars = maxChars, timeoutOverride = timeoutOverride).then {
        if (it.truncated) Outcome.failure(DiagCode.GIT_OUTPUT_INVALID, "вывод Git больше предела; полный статус неизвестен", path)
        else if (maxChars != Int.MAX_VALUE && (it.stdout.none { line -> line.startsWith("# branch.oid ") } || it.stdout.none { line -> line.startsWith("# branch.head ") })) {
            Outcome.failure(DiagCode.GIT_OUTPUT_INVALID, "Git не вернул ветку и HEAD; состояние неизвестно", path)
        }
        else GitStatus.parse(it.stdout)
    }

    /** Изменение дерева сессией: только владелец; чтение — любому. */
    fun write(state: WorktreesState, worktree: WorktreeId, session: SessionId, op: GitOp, args: List<String> = emptyList(), approval: Approval? = null): Outcome<Completed> {
        if (!op.writes) return Outcome.failure(DiagCode.VALUE_INVALID, "${op.name} не изменяет дерево: это чтение", "worktree $worktree")
        return Worktrees.checkWriter(state, worktree, session).then { w -> run(op, w.path, args, approval) }
    }
}
