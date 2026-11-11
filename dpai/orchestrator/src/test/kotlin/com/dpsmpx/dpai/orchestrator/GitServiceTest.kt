package com.dpsmpx.dpai.orchestrator

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.ids.WorktreeId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.git.GitOp
import com.dpsmpx.dpai.git.Repository
import com.dpsmpx.dpai.git.WorktreeEvent
import com.dpsmpx.dpai.git.Worktrees
import com.dpsmpx.dpai.git.WorktreesState
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.security.Approval
import com.dpsmpx.dpai.security.ApprovalLedger
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.io.TempDir

/**
 * Git и рабочие деревья на настоящем `git` (критерии этапа 9; ТЗ, §29–§31,
 * §70, §118): параллельные сессии в своих деревьях не мешают друг другу,
 * чужое дерево не изменить, разрушительное — только с одобрением.
 */
class GitServiceTest {
    @TempDir
    lateinit var dir: Path

    /** Класс — про настоящий `git`: нет его в `PATH` — пропуск с причиной, а не NPE (аудит, стабильность H-1). */
    @org.junit.jupiter.api.BeforeEach
    fun needsGit() = org.junit.jupiter.api.Assumptions.assumeTrue(
        com.dpsmpx.dpai.testkit.Tools.gitAvailable(),
        "нет git в PATH: проверка на настоящем git — на машине с git",
    )

    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(51))
    private val host = LocalProcessHost()
    private val approvals = ApprovalLedger()
    private val git = GitService(host, GitCommands(approvals), clock)
    private var state = WorktreesState.EMPTY

    private fun ok(out: Outcome<List<WorktreeEvent>>) {
        state = Worktrees.applyAll(state, assertIs<Outcome.Success<List<WorktreeEvent>>>(out, out.diagnostics.toString()).value)
    }

    /** Подготовка репозитория — не операция оркестратора, поэтому прямым вызовом git. */
    private fun sh(vararg argv: String) {
        val r = host.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    @Test
    fun boundedStatusObservesRealBranchDetachedHeadAndDirtyFiles() {
        val repo = repo()
        val initial = git.status(repo.root, 256 * 1024).valueOrNull()!!
        assertEquals("main", initial.branch)
        assertTrue(initial.head!!.matches(Regex("[a-f0-9]{40}")))
        File(repo.root, "README.md").appendText("правка\n")
        File(repo.root, "new.txt").writeText("новое")
        sh("-C", repo.root, "switch", "-q", "-c", "actual")
        val dirty = git.status(repo.root, 256 * 1024).valueOrNull()!!
        assertEquals("actual", dirty.branch)
        assertTrue(dirty.changed.contains("README.md"))
        assertTrue(dirty.untracked.contains("new.txt"))
        assertIs<Outcome.Failure>(git.status(repo.root, 20), "усечённый вывод не успешен")
        sh("-C", repo.root, "checkout", "-q", "--detach")
        val detached = git.status(repo.root, 256 * 1024).valueOrNull()!!
        assertEquals(initial.head, detached.head)
        assertEquals(null, detached.branch)
    }

    private fun repo(): Repository {
        val root = dir.resolve("repo").toFile().apply { mkdirs() }.path
        sh("init", "-q", "-b", "main", root)
        sh("-C", root, "config", "user.email", "dpai-test@example.org")
        sh("-C", root, "config", "user.name", "dpai test")
        File(root, "README.md").writeText("начало\n")
        sh("-C", root, "add", "README.md")
        sh("-C", root, "commit", "-q", "-m", "начало")
        val r = Repository(ids.next(IdKinds.REPOSITORY), ids.next(IdKinds.TARGET), root)
        ok(Worktrees.register(state, r, clock.now()))
        return r
    }

    private fun worktree(r: Repository, name: String, session: SessionId): WorktreeId {
        val id = ids.next(IdKinds.WORKTREE)
        val path = dir.resolve("wt-$name").toString()
        ok(Worktrees.create(state, id, r.id, path, "task/$name", clock.now()))
        assertTrue(git.run(GitOp.WORKTREE_ADD, r.root, listOf(path, "task/$name")).isSuccess)
        ok(Worktrees.claim(state, id, session, clock.now()))
        return id
    }

    @Test
    fun parallelSessionsDoNotCorruptEachOthersWorktrees() {
        val r = repo()
        val sessions = List(4) { ids.next(IdKinds.SESSION) }
        val trees = sessions.mapIndexed { i, s -> worktree(r, "s$i", s) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = sessions.indices.map { i ->
                Callable {
                    val w = state.worktrees.getValue(trees[i])
                    repeat(3) { n ->
                        File(w.path, "s$i-$n.txt").writeText("сессия $i, правка $n\n")
                        assertTrue(git.write(state, trees[i], sessions[i], GitOp.ADD, listOf("s$i-$n.txt")).isSuccess)
                        val c = git.write(state, trees[i], sessions[i], GitOp.COMMIT, listOf("сессия $i правка $n"))
                        assertTrue(c.isSuccess, c.diagnostics.toString())
                    }
                }
            }
            pool.invokeAll(jobs).forEach { it.get(5, TimeUnit.MINUTES) }
        } finally {
            pool.shutdownNow()
        }
        for ((i, t) in trees.withIndex()) {
            val w = state.worktrees.getValue(t)
            val status = git.status(w.path).valueOrNull()!!
            assertTrue(status.clean, "дерево $i: $status")
            assertEquals("task/s$i", status.branch)
            val log = git.run(GitOp.LOG, w.path).valueOrNull()!!.stdout.map { it.substringAfter(' ') }
            assertEquals(listOf("сессия $i правка 2", "сессия $i правка 1", "сессия $i правка 0", "начало"), log, "в ветке только свои коммиты")
            assertEquals(setOf("README.md") + (0..2).map { "s$i-$it.txt" }, File(w.path).list()!!.filter { !it.startsWith(".") }.toSet())
        }
    }

    @Test
    fun oneWritableOwnerPerWorktree() {
        val r = repo()
        val a = ids.next(IdKinds.SESSION)
        val b = ids.next(IdKinds.SESSION)
        val tree = worktree(r, "a", a)
        assertEquals(DiagCode.WORKTREE_OWNED, (Worktrees.claim(state, tree, b, clock.now()) as Outcome.Failure).errors.single().code)
        File(state.worktrees.getValue(tree).path, "x.txt").writeText("x")
        assertEquals(DiagCode.WORKTREE_NOT_OWNED, (git.write(state, tree, b, GitOp.ADD, listOf("x.txt")) as Outcome.Failure).errors.single().code)
        ok(Worktrees.release(state, tree, a, clock.now()))
        ok(Worktrees.claim(state, tree, b, clock.now()))
        assertTrue(git.write(state, tree, b, GitOp.ADD, listOf("x.txt")).isSuccess)
        // Ветка не выписывается во второе дерево.
        assertEquals(DiagCode.BRANCH_IN_USE, (Worktrees.create(state, ids.next(IdKinds.WORKTREE), r.id, dir.resolve("other").toString(), "task/a", clock.now()) as Outcome.Failure).errors.single().code)
    }

    @Test
    fun destructiveOperationNeedsApproval() {
        val r = repo()
        val s = ids.next(IdKinds.SESSION)
        val tree = worktree(r, "d", s)
        val path = state.worktrees.getValue(tree).path
        File(path, "README.md").writeText("испорчено\n")
        val denied = git.write(state, tree, s, GitOp.RESET_HARD)
        assertEquals(DiagCode.APPROVAL_REQUIRED, (denied as Outcome.Failure).errors.single().code)
        assertEquals("испорчено\n", File(path, "README.md").readText(), "без одобрения ничего не тронуто")
        // Одобрение другого дерева не подходит.
        val foreign = approval(GitCommands(approvals).proposal(GitOp.RESET_HARD, r.root, emptyList()).hash)
        assertEquals(DiagCode.APPROVAL_INVALID, (git.write(state, tree, s, GitOp.RESET_HARD, approval = foreign) as Outcome.Failure).errors.single().code)
        val right = approval(GitCommands(approvals).proposal(GitOp.RESET_HARD, path, emptyList()).hash)
        assertTrue(git.write(state, tree, s, GitOp.RESET_HARD, approval = right).isSuccess)
        assertEquals("начало\n", File(path, "README.md").readText().replace("\r\n", "\n"))
        // Одобрение одноразовое.
        assertEquals(DiagCode.APPROVAL_INVALID, (git.write(state, tree, s, GitOp.RESET_HARD, approval = right) as Outcome.Failure).errors.single().code)
    }

    @Test
    fun optionLikeArgumentsAreRejected() {
        val r = repo()
        val s = ids.next(IdKinds.SESSION)
        val tree = worktree(r, "o", s)
        val out = git.write(state, tree, s, GitOp.ADD, listOf("--force"))
        assertEquals(DiagCode.VALUE_INVALID, (out as Outcome.Failure).errors.single().code)
    }

    /** T4 (аудит 2026-10-07, A-2): настройки и хуки репозитория не исполняют код при командах оркестратора. */
    @Test
    fun repositoryHooksAndFsmonitorDoNotRun() {
        val r = repo()
        val marker = dir.resolve("pwned").toFile()
        val script = dir.resolve("evil.sh").toFile()
        script.writeText("#!/bin/sh\necho pwned > '${marker.path.replace('\\', '/')}'\n")
        script.setExecutable(true)
        val hooks = File(r.root, ".git/hooks").apply { mkdirs() }
        for (h in listOf("post-merge", "pre-commit", "post-commit", "post-checkout")) {
            File(hooks, h).apply { writeText(script.readText()); setExecutable(true) }
        }
        sh("-C", r.root, "config", "core.fsmonitor", script.path.replace('\\', '/'))
        sh("-C", r.root, "checkout", "-q", "-b", "side")
        File(r.root, "side.txt").writeText("x\n")
        sh("-C", r.root, "-c", "core.hooksPath=.none", "add", "side.txt")
        sh("-C", r.root, "-c", "core.hooksPath=.none", "-c", "core.fsmonitor=false", "commit", "-q", "-m", "side")
        sh("-C", r.root, "-c", "core.hooksPath=.none", "-c", "core.fsmonitor=false", "checkout", "-q", "main")
        marker.delete()
        assertTrue(git.status(r.root).isSuccess)
        assertTrue(git.run(GitOp.MERGE, r.root, listOf("side")).isSuccess)
        assertTrue(!marker.exists(), "хук или fsmonitor репозитория исполнился")
    }

    /**
     * Ревью 8 октября 2026 (security-1): относительный `core.hooksPath` Git
     * считает от корня рабочего дерева — хук, закоммиченный в ветку в этот
     * каталог, исполнился бы при слиянии. Каталог хуков — вне любого дерева.
     */
    @Test
    fun hooksCommittedInTheTreeDoNotRun() {
        val r = repo()
        val marker = dir.resolve("pwned-tree").toFile()
        val evil = "#!/bin/sh\necho pwned > '${marker.path.replace('\\', '/')}'\n"
        sh("-C", r.root, "checkout", "-q", "-b", "side")
        for (d in listOf(".dpai-no-hooks", ".none")) {
            for (h in listOf("post-merge", "post-checkout", "post-commit", "pre-merge-commit")) {
                File(r.root, "$d/$h").apply { parentFile.mkdirs(); writeText(evil); setExecutable(true) }
            }
        }
        sh("-C", r.root, "-c", "core.hooksPath=/dev/null", "add", "-A")
        sh("-C", r.root, "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", "хуки в дереве")
        sh("-C", r.root, "-c", "core.hooksPath=/dev/null", "checkout", "-q", "main")
        marker.delete()
        assertTrue(git.run(GitOp.MERGE, r.root, listOf("side")).isSuccess)
        assertTrue(!marker.exists(), "хук из содержимого ветки исполнился при слиянии")
    }

    /** Строки матрицы восстановления «конфликт Git» и «worktree удалён» (`docs/RECOVERY.md`, §4). */
    @Test
    fun probeFindsConflictAndMissingWorktree() {
        val r = repo()
        val s = ids.next(IdKinds.SESSION)
        val tree = worktree(r, "c", s)
        val path = state.worktrees.getValue(tree).path
        val probe = com.dpsmpx.dpai.orchestrator.recovery.WorktreeProbe(git)
        assertEquals(null, probe.check(path))
        // Одна строка изменена по-разному в main и в ветке задачи.
        File(r.root, "README.md").writeText("из main\n")
        sh("-C", r.root, "commit", "-q", "-am", "main")
        File(path, "README.md").writeText("из задачи\n")
        assertTrue(git.write(state, tree, s, GitOp.ADD, listOf("README.md")).isSuccess)
        assertTrue(git.write(state, tree, s, GitOp.COMMIT, listOf("задача")).isSuccess)
        assertTrue(git.write(state, tree, s, GitOp.MERGE, listOf("main")) is Outcome.Failure)
        assertEquals(DiagCode.MERGE_CONFLICT, probe.check(path)!!.code)
        File(path).deleteRecursively()
        assertEquals(DiagCode.WORKTREE_MISSING, probe.check(path)!!.code)
    }

    private fun approval(hash: com.dpsmpx.dpai.core.hashing.ContentHash) =
        Approval(ids.next(IdKinds.APPROVAL), hash, "автор", clock.now(), clock.now() + 10.minutes)

    /**
     * Аудит I (ТЗ, §108): удалённая ветка и сбой push — отказ
     * `GIT_COMMAND_FAILED` с причиной, а репозиторий и ветка интеграции
     * не тронуты (полуслияния нет).
     */
    @Test
    fun deletedBranchAndPushFailureAreReportedAndRepositoryIsIntact() {
        val r = repo()
        val head = host.run(ProcessSpec(listOf("git", "-C", r.root, "rev-parse", "HEAD")), 1.minutes).valueOrNull()!!.stdout
        val merge = git.run(GitOp.MERGE, r.root, listOf("task/удалена"))
        assertEquals(DiagCode.GIT_COMMAND_FAILED, (merge as Outcome.Failure).diagnostics.first().code)
        val push = git.run(GitOp.PUSH, r.root, listOf("нет-такого-remote", "main"))
        assertEquals(DiagCode.GIT_COMMAND_FAILED, (push as Outcome.Failure).diagnostics.first().code)
        assertEquals(head, host.run(ProcessSpec(listOf("git", "-C", r.root, "rev-parse", "HEAD")), 1.minutes).valueOrNull()!!.stdout)
        assertEquals(emptyList(), host.run(ProcessSpec(listOf("git", "-C", r.root, "status", "--porcelain")), 1.minutes).valueOrNull()!!.stdout)
    }

    /** Изменения задачи (D-42): от базы ветки интеграции — до дерева с незакоммиченным и до ветки после уборки дерева. */
    @Test
    fun taskDiffFromBaseToTreeAndToBranch() {
        val r = repo()
        val session = ids.next(IdKinds.SESSION)
        val wt = worktree(r, "diff", session)
        val path = state.worktrees.getValue(wt).path
        // На main после ответвления — свой коммит: в изменениях задачи его быть не должно.
        File(r.root, "MAIN.md").writeText("только в main\n")
        sh("-C", r.root, "add", "MAIN.md")
        sh("-C", r.root, "commit", "-q", "-m", "main впереди")
        File(path, "README.md").writeText("начало\nстрока задачи\n")
        File(path, "new.txt").writeText("новый\n")
        val tree = assertIs<Outcome.Success<com.dpsmpx.dpai.orchestrator.git.TaskDiff>>(com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(git, path, "main", null)).value
        assertEquals(listOf(com.dpsmpx.dpai.orchestrator.git.FileChange("README.md", 1, 0)), tree.files)
        assertEquals(listOf("new.txt"), tree.untracked)
        assertTrue("+строка задачи" in tree.patch && "MAIN.md" !in tree.patch, tree.patch)
        // Коммит в ветке задачи, дерево убрано: изменения — до ветки.
        sh("-C", path, "add", "README.md", "new.txt")
        sh("-C", path, "commit", "-q", "-m", "задача")
        sh("-C", r.root, "worktree", "remove", path)
        val branch = assertIs<Outcome.Success<com.dpsmpx.dpai.orchestrator.git.TaskDiff>>(com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(git, r.root, "main", "task/diff")).value
        assertEquals(setOf("README.md", "new.txt"), branch.files.map { it.path }.toSet())
        assertTrue(branch.untracked.isEmpty())
        // Предел текста — с пометкой, по строке.
        val cut = assertIs<Outcome.Success<com.dpsmpx.dpai.orchestrator.git.TaskDiff>>(com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(git, r.root, "main", "task/diff", maxChars = 40)).value
        assertTrue(cut.truncated && "… обрезано:" in cut.patch, cut.patch)
        // Ревизия, похожая на параметр, — отказ команды, а не флаг Git (T4).
        assertTrue(com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(git, r.root, "--output=/tmp/x", "task/diff") is Outcome.Failure)
        // Кириллица в пути — как есть, а не «\320…» в кавычках (ревью 17 октября 2026).
        val ru = worktree(r, "ru", ids.next(IdKinds.SESSION))
        val ruPath = state.worktrees.getValue(ru).path
        File(ruPath, "Заметки.md").writeText("текст\n")
        sh("-C", ruPath, "add", "Заметки.md")
        val ruDiff = assertIs<Outcome.Success<com.dpsmpx.dpai.orchestrator.git.TaskDiff>>(com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(git, ruPath, "main", null)).value
        assertEquals(listOf("Заметки.md"), ruDiff.files.map { it.path })
        assertTrue("Заметки.md" in ruDiff.patch, ruDiff.patch)
    }

    /** Слитая задача (ревью 17 октября 2026): изменения — до слияния, а не пусто. */
    @Test
    fun mergedTaskStillShowsItsChanges() {
        val r = repo()
        val wt = worktree(r, "merged", ids.next(IdKinds.SESSION))
        val path = state.worktrees.getValue(wt).path
        File(path, "feature.txt").writeText("сделано\n")
        sh("-C", path, "add", "feature.txt")
        sh("-C", path, "commit", "-q", "-m", "задача")
        File(r.root, "MAIN.md").writeText("только в main\n")
        sh("-C", r.root, "add", "MAIN.md")
        sh("-C", r.root, "commit", "-q", "-m", "main впереди")
        sh("-C", r.root, "worktree", "remove", path)
        sh("-C", r.root, "merge", "-q", "--no-ff", "--no-edit", "task/merged")
        val d = assertIs<Outcome.Success<com.dpsmpx.dpai.orchestrator.git.TaskDiff>>(com.dpsmpx.dpai.orchestrator.git.TaskDiff.load(git, r.root, "main", "task/merged")).value
        assertEquals(listOf("feature.txt"), d.files.map { it.path })
        assertTrue("+сделано" in d.patch && "MAIN.md" !in d.patch, d.patch)
    }
}
