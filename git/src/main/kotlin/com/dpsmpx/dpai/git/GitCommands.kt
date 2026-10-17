package com.dpsmpx.dpai.git

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.security.Approval
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.security.Proposal

/**
 * Операция Git (ТЗ, §30, §70). [destructive] — теряет данные без
 * возможности вернуть их средствами Git: такие операции — только с
 * одобрением, привязанным к хешу предложения (ТЗ, §68).
 */
enum class GitOp(val destructive: Boolean, val writes: Boolean) {
    STATUS(false, false),
    DIFF(false, false),
    LOG(false, false),
    /** База ветки задачи и ветки интеграции (D-42: изменения задачи). */
    MERGE_BASE(false, false),
    /** Изменения от ревизии до рабочего дерева или до второй ревизии — текст и счёт строк по файлам. */
    DIFF_FROM(false, false),
    DIFF_STAT_FROM(false, false),
    /** Коммиты слияния между ревизиями (`from..to`, по пути предков): чем слита ветка задачи (D-42). */
    MERGES_BETWEEN(false, false),
    WORKTREE_LIST(false, false),
    FETCH(false, false),
    ADD(false, true),
    COMMIT(false, true),
    BRANCH_CREATE(false, true),
    WORKTREE_ADD(false, true),
    WORKTREE_REMOVE(false, true),
    MERGE(false, true),
    MERGE_ABORT(false, true),
    PUSH(false, true),
    RESET_HARD(true, true),
    CLEAN_FORCE(true, true),
    PUSH_FORCE(true, true),
    BRANCH_DELETE_FORCE(true, true),
    WORKTREE_REMOVE_FORCE(true, true),
}

/**
 * Команды Git для рабочего дерева (ТЗ, §70): всегда `git -C <дерево>` —
 * так одна и та же команда работает локально и по SSH без рабочего
 * каталога процесса; аргументы, похожие на параметры, отвергаются, пути и
 * ссылки идут после `--`, где Git это позволяет. Неявных `reset --hard`,
 * `push --force`, `clean -fdx` нет: только [GitOp] с одобрением.
 */
class GitCommands(private val approvals: ApprovalLedger, private val git: String = "git") {
    fun proposal(op: GitOp, worktree: String, args: List<String>): Proposal = Proposal("git.${op.name.lowercase()}", worktree, args)

    fun argv(op: GitOp, worktree: String, args: List<String> = emptyList(), now: Timestamp, approval: Approval? = null): Outcome<List<String>> {
        if (worktree.isBlank() || worktree.startsWith("-") || worktree.any { it.isISOControl() }) {
            return Outcome.failure(DiagCode.VALUE_INVALID, "путь рабочего дерева недопустим", "worktree")
        }
        args.firstOrNull { it.startsWith("-") || it.any { c -> c.isISOControl() } || it.isEmpty() }?.let {
            return Outcome.failure(DiagCode.VALUE_INVALID, "аргумент «$it» похож на параметр или пуст: параметры задаёт операция", worktree)
        }
        if (op.destructive) {
            when (val r = approvals.consume(proposal(op, worktree, args), approval, now)) {
                is Outcome.Failure -> return r
                is Outcome.Success -> Unit
            }
        }
        val need = ARITY.getValue(op)
        if (args.size !in need) return Outcome.failure(DiagCode.VALUE_INVALID, "у ${op.name} аргументов ${need.first}…${need.last}, дано ${args.size}", worktree)
        val tail: List<String> = when (op) {
            GitOp.STATUS -> listOf("status", "--porcelain=v2", "--branch")
            GitOp.DIFF -> listOf("diff", "--no-color", "--no-ext-diff") + (if (args.isEmpty()) emptyList() else listOf("--") + args)
            GitOp.LOG -> listOf("log", "--no-color", "--format=%H %s", "-n", "50")
            GitOp.MERGE_BASE -> listOf("merge-base") + args
            // Ревизии — до `--`: имя ветки, совпавшее с файлом, не станет путём.
            GitOp.DIFF_FROM -> listOf("diff", "--no-color", "--no-ext-diff", "--find-renames") + args + "--"
            GitOp.DIFF_STAT_FROM -> listOf("diff", "--no-color", "--no-ext-diff", "--find-renames", "--numstat") + args + "--"
            GitOp.MERGES_BETWEEN -> listOf("rev-list", "--ancestry-path", "--merges", "--reverse", "${args[0]}..${args[1]}", "--")
            GitOp.WORKTREE_LIST -> listOf("worktree", "list", "--porcelain")
            GitOp.FETCH -> listOf("fetch", "--no-recurse-submodules", "--") + args
            GitOp.ADD -> listOf("add", "--") + args
            GitOp.COMMIT -> listOf("commit", "--no-verify", "-m", args.single())
            GitOp.BRANCH_CREATE -> listOf("branch", "--") + args
            GitOp.WORKTREE_ADD -> listOf("worktree", "add", "-b", args[1], "--", args[0]) + args.drop(2)
            GitOp.WORKTREE_REMOVE -> listOf("worktree", "remove", "--", args.single())
            GitOp.MERGE -> listOf("merge", "--no-ff", "--no-edit", "--") + args
            GitOp.MERGE_ABORT -> listOf("merge", "--abort")
            GitOp.PUSH -> listOf("push", "--") + args
            GitOp.RESET_HARD -> listOf("reset", "--hard", "--") + args
            GitOp.CLEAN_FORCE -> listOf("clean", "-fd", "--") + args
            GitOp.PUSH_FORCE -> listOf("push", "--force-with-lease", "--") + args
            GitOp.BRANCH_DELETE_FORCE -> listOf("branch", "-D", "--") + args
            GitOp.WORKTREE_REMOVE_FORCE -> listOf("worktree", "remove", "--force", "--", args.single())
        }
        val read = if (op in READ_ONLY) READ_OPTIONS else emptyList()
        val paths = if (op in PATH_OUTPUT) UNQUOTED_PATHS else emptyList()
        return Outcome.success(listOf(git) + read + HARDENING + paths + listOf("-C", worktree) + tail)
    }

    companion object {
        /**
         * Каждая команда — без исполнения кода из настроек репозитория (T4;
         * аудит 2026-10-07, A-2): `core.fsmonitor` исполнил бы программу уже
         * при `git status`, хуки — при слиянии и коммите. Каталог хуков —
         * абсолютный и вне любого дерева (`/dev/null`): относительный путь Git
         * считает от корня рабочего дерева, и хук, закоммиченный в ветку в
         * этот каталог, исполнился бы при слиянии (ревью 8 октября 2026).
         */
        val HARDENING: List<String> = listOf("-c", "core.fsmonitor=false", "-c", "core.hooksPath=/dev/null")

        /**
         * Чтение не берёт необязательный `index.lock` (ревью 17 октября 2026):
         * `git status` и `git diff` с экрана задачи шли бы вне такта и срывали
         * `git add`/`git commit` хода в том же дереве.
         */
        private val READ_OPTIONS = listOf("--no-optional-locks")
        private val READ_ONLY = setOf(
            GitOp.STATUS, GitOp.DIFF, GitOp.LOG, GitOp.MERGE_BASE, GitOp.DIFF_FROM, GitOp.DIFF_STAT_FROM, GitOp.MERGES_BETWEEN, GitOp.WORKTREE_LIST,
        )

        /** Пути — как есть, в UTF-8: при `core.quotePath=true` кириллица шла бы «\320\227…» в кавычках. */
        private val UNQUOTED_PATHS = listOf("-c", "core.quotePath=false")
        private val PATH_OUTPUT = setOf(GitOp.STATUS, GitOp.DIFF, GitOp.DIFF_FROM, GitOp.DIFF_STAT_FROM)

        /**
         * Хуки проекта при коммите оркестратора не исполняются (`--no-verify`, T4):
         * проверка проекта — отдельный шаг профиля (этап 10), а не побочный эффект коммита.
         */
        private val ARITY: Map<GitOp, IntRange> = mapOf(
            GitOp.STATUS to 0..0, GitOp.DIFF to 0..50, GitOp.LOG to 0..0, GitOp.MERGE_BASE to 2..2, GitOp.DIFF_FROM to 1..2, GitOp.DIFF_STAT_FROM to 1..2, GitOp.MERGES_BETWEEN to 2..2, GitOp.WORKTREE_LIST to 0..0, GitOp.FETCH to 0..2,
            GitOp.ADD to 1..1000, GitOp.COMMIT to 1..1, GitOp.BRANCH_CREATE to 1..2, GitOp.WORKTREE_ADD to 2..3,
            GitOp.WORKTREE_REMOVE to 1..1, GitOp.MERGE to 1..1, GitOp.MERGE_ABORT to 0..0, GitOp.PUSH to 1..2, GitOp.RESET_HARD to 0..1,
            GitOp.CLEAN_FORCE to 0..50, GitOp.PUSH_FORCE to 1..2, GitOp.BRANCH_DELETE_FORCE to 1..1, GitOp.WORKTREE_REMOVE_FORCE to 1..1,
        )
    }
}
