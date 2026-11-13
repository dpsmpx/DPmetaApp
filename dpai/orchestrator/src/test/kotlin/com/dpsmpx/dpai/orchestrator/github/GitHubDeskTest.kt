package com.dpsmpx.dpai.orchestrator.github

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.connectors.GitHubCommands
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.git.GitCommands
import com.dpsmpx.dpai.orchestrator.git.GitService
import com.dpsmpx.dpai.security.ApprovalLedger
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.verification.StepRunner
import java.io.File
import java.nio.file.Path
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * PR проекта через `gh` на target (D-108): `git push` — настоящим `git` в локальный «origin»,
 * вызовы `gh` через агента — подделкой [StepRunner] с ответами корпуса `tests/data/gh/`.
 * Действие уровня 1 — строкой в журнале действий, итог и отказ — тоже (`DPmetaApp:D-07`).
 */
class GitHubDeskTest {
    @TempDir
    lateinit var dir: Path

    private val clock = ManualClock()
    private val host = LocalProcessHost()
    private val git = GitService(host, GitCommands(ApprovalLedger()), clock)
    private val task = IdGenerator(clock, RandomSource.seeded(7)).next(IdKinds.TASK)
    private val calls = mutableListOf<Pair<List<String>, String>>()
    private val replies = ArrayDeque<Completed>()
    private val runner = StepRunner { command: List<String>, worktree: String, _: Duration ->
        calls += command to worktree
        Outcome.success(replies.removeFirstOrNull() ?: Completed(0, emptyList(), emptyList()))
    }
    private lateinit var repo: String
    private lateinit var origin: String
    private lateinit var journal: ActionJournal

    @BeforeTest
    fun repository() {
        assumeTrue(com.dpsmpx.dpai.testkit.Tools.gitAvailable(), "нет git в PATH")
        origin = dir.resolve("origin.git").toString()
        repo = dir.resolve("repo").toFile().apply { mkdirs() }.path
        sh("init", "-q", "--bare", origin)
        sh("init", "-q", "-b", "main", repo)
        sh("-C", repo, "config", "user.email", "dpai-test@example.org")
        sh("-C", repo, "config", "user.name", "dpai test")
        File(repo, "README.md").writeText("начало\n")
        sh("-C", repo, "add", "README.md")
        sh("-C", repo, "commit", "-q", "-m", "начало")
        sh("-C", repo, "remote", "add", "origin", origin)
        journal = ActionJournal(NioVfs(dir.resolve("app")))
    }

    private fun sh(vararg argv: String) {
        val r = host.run(ProcessSpec(listOf("git", *argv)), 1.minutes).valueOrNull()!!
        assertEquals(0, r.exitCode, (r.stderr + r.stdout).joinToString("\n"))
    }

    private fun desk() = GitHubDesk(runner, git, repo, "main", journal, clock)

    private fun corpus(name: String): List<String> {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        return File(root, "tests/data/gh/$name").readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    @Test
    fun listAndViewRunGhInTheRepository() {
        replies += Completed(0, corpus("pr-list.json"), emptyList())
        replies += Completed(0, corpus("pr-view.json"), emptyList())
        assertEquals(listOf(12, 11), desk().list().valueOrNull()!!.map { it.number })
        assertEquals("MERGEABLE", desk().view(12).valueOrNull()!!.mergeable)
        assertEquals(listOf(GitHubCommands.prList() to repo, GitHubCommands.prView(12).valueOrNull()!! to repo), calls)
        assertTrue(journal.last().isEmpty(), "чтение (уровень 0) в журнал действий не пишется")
    }

    @Test
    fun ghWithoutLoginIsNamed() {
        replies += Completed(4, emptyList(), listOf("To get started with GitHub CLI, please run:  gh auth login"))
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, (desk().list() as Outcome.Failure).errors.single().code)
    }

    @Test
    fun sendPushesTaskBranchAndOpensPr() {
        val branch = "task/${task.value}"
        sh("-C", repo, "checkout", "-q", "-b", branch)
        File(repo, "hello.txt").writeText("привет\n")
        sh("-C", repo, "add", "hello.txt")
        sh("-C", repo, "commit", "-q", "-m", "hello")
        replies += Completed(0, listOf("Creating pull request for $branch into main", "https://github.com/o/r/pull/7"), emptyList())
        val plan = desk().plan(task)
        assertEquals(GitHubDesk.SendPlan(branch, "origin", "main", repo), plan)

        val url = desk().send(task, "Задача: hello", "Создан hello.txt")
        assertEquals("https://github.com/o/r/pull/7", url.valueOrNull(), url.toString())
        // Ветка — в origin; PR — командой gh в каталоге репозитория.
        val remote = host.run(ProcessSpec(listOf("git", "--git-dir", origin, "branch", "--list", branch)), 1.minutes).valueOrNull()!!
        assertEquals(listOf("  $branch"), remote.stdout)
        assertEquals(listOf(GitHubCommands.createPr("main", branch, "Задача: hello", "Создан hello.txt").valueOrNull()!! to repo), calls)
        val log = journal.last()
        assertEquals(listOf("branch.push" to true, "pr.create" to true), log.map { it.action to it.ok })
        assertTrue(log.all { it.level == 1 && it.subject == branch && it.repository == repo })
        assertEquals("https://github.com/o/r/pull/7", log.last().detail)
    }

    /** Повтор отправки: PR ветки уже открыт — его адрес, второй PR не создаётся. */
    @Test
    fun repeatedSendAnswersWithExistingPr() {
        val branch = "task/${task.value}"
        sh("-C", repo, "branch", branch)
        replies += Completed(1, emptyList(), listOf("a pull request for branch \"$branch\" into branch \"main\" already exists:"))
        replies += Completed(0, listOf("""{"mergeable":"MERGEABLE","number":7,"state":"OPEN","url":"https://github.com/o/r/pull/7"}"""), emptyList())
        assertEquals("https://github.com/o/r/pull/7", desk().send(task, "t", "b").valueOrNull())
        assertEquals(listOf("gh", "pr", "view", branch, "--json", "number,state,url,mergeable"), calls.last().first)
    }

    /** Ветки нет — отказ `git push` с причиной в журнале; до `gh` дело не доходит. */
    @Test
    fun failedPushIsJournaledAndStops() {
        val r = desk().send(task, "t", "b")
        assertEquals(DiagCode.GIT_COMMAND_FAILED, assertIs<Outcome.Failure>(r).errors.single().code)
        assertTrue(calls.isEmpty(), "gh не вызывался")
        val log = journal.last().single()
        assertEquals("branch.push", log.action)
        assertTrue(!log.ok && log.detail.startsWith("GIT_COMMAND_FAILED"), log.detail)
    }

    @Test
    fun journalSkipsForeignLines() {
        journal.append(ActionRecord(1, 1, "pr.create", repo, "task/x", true, "https://github.com/o/r/pull/1"))
        NioVfs(dir.resolve("app")).append(ActionJournal.PATH, "не json\n{\"format\":\"другое\"}\n".toByteArray())
        journal.append(ActionRecord(2, 1, "branch.push", repo, "task/y", false, "GIT_COMMAND_FAILED: …"))
        assertEquals(listOf("task/x", "task/y"), journal.last().map { it.subject })
    }

    /** Комментарий — уровень 2 (`DPmetaApp:D-80`): команда `gh pr comment`, строка в журнале. */
    @Test
    fun commentIsLevelTwoAndJournaled() {
        assertIs<Outcome.Success<Unit>>(desk().comment(12, "Посмотрю"))
        assertEquals(listOf(GitHubCommands.prComment(12, "Посмотрю").valueOrNull()!! to repo), calls)
        val rec = journal.last().single()
        assertEquals(listOf("pr.comment", "#12", "готово"), listOf(rec.action, rec.subject, rec.detail))
        assertEquals(2, rec.level)
        assertIs<Outcome.Failure>(desk().comment(12, " "))
        assertEquals(1, calls.size, "пустой комментарий до gh не доходит")
    }

    /** «Слить» — уровень 3: имя `владелец/имя` сверяется и здесь; слияние — только показанного коммита. */
    @Test
    fun mergeNeedsExactRepositoryNameAndShownCommit() {
        replies += Completed(0, corpus("pr-view.json"), emptyList())
        val shown = desk().view(12).valueOrNull()!!
        calls.clear()
        val wrong = desk().merge(shown, "o/other")
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, assertIs<Outcome.Failure>(wrong).errors.single().code)
        assertTrue(calls.isEmpty(), "при неверном имени gh не вызывается")
        assertIs<Outcome.Success<Unit>>(desk().merge(shown, " o/r "))
        assertEquals(listOf("gh", "pr", "merge", "12", "--merge", "--match-head-commit", "1".repeat(40)), calls.single().first)
        assertEquals(listOf(3 to false, 3 to true), journal.last().map { it.level to it.ok })
    }
}
