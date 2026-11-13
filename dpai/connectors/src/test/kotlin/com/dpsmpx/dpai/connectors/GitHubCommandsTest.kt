package com.dpsmpx.dpai.connectors

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Команды `gh` (`docs/ECOSYSTEM.md`, §3): строгие аргументы, разбор ссылки на PR. */
class GitHubCommandsTest {
    @Test
    fun createPrArguments() {
        val argv = GitHubCommands.createPr("main", "task/x", "Задача x", "описание").valueOrNull()!!
        assertEquals(listOf("gh", "pr", "create", "--base", "main", "--head", "task/x", "--title", "Задача x", "--body", "описание"), argv)
        for (bad in listOf("-x", "a..b", "a b")) {
            assertEquals(DiagCode.VALUE_INVALID, (GitHubCommands.createPr("main", bad, "t", "") as Outcome.Failure).errors.single().code)
        }
        assertIs<Outcome.Failure>(GitHubCommands.createPr("main", "task/x", " ", ""))
    }

    @Test
    fun prUrlIsParsedOrRejected() {
        assertEquals("https://github.com/o/r/pull/7", GitHubCommands.prUrl(listOf("Creating pull request", "https://github.com/o/r/pull/7")).valueOrNull())
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, (GitHubCommands.prUrl(listOf("error")) as Outcome.Failure).errors.single().code)
        assertEquals(listOf("gh", "pr", "view", "task/x", "--json", "number,state,url,mergeable"), GitHubCommands.prState("task/x").valueOrNull())
    }

    private fun corpus(name: String): List<String> {
        val root = System.getProperty("dpai.repoRoot") ?: error("нет dpai.repoRoot")
        return File(root, "tests/data/gh/$name").readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
    }

    /** D-108: список PR — строго по запрошенным полям; проверки обоих видов; внешний текст очищен (T3). */
    @Test
    fun prListFromCorpus() {
        assertEquals(listOf("gh", "pr", "list", "--state", "open", "--limit", "30", "--json", GitHubCommands.LIST_FIELDS), GitHubCommands.prList())
        val prs = GitHubCommands.parsePrList(corpus("pr-list.json")).valueOrNull()!!
        assertEquals(listOf(12, 11), prs.map { it.number })
        val first = prs[0]
        assertEquals("task/t-01", first.head)
        assertEquals("REVIEW_REQUIRED", first.review)
        assertEquals(listOf(GhCheck("build", "SUCCESS"), GhCheck("lint", "PENDING")), first.checks)
        assertTrue(first.checks[0].passed)
        val bot = prs[1]
        assertTrue(bot.draft)
        assertNull(bot.review, "пустое решение — не требуется")
        assertEquals(listOf("FAILURE", "IN_PROGRESS"), bot.checks.map { it.state })
        assertTrue(bot.checks[0].failed && !bot.checks[1].failed && !bot.checks[1].passed)
        assertFalse(bot.title.any { it == '\u202E' || it == '\u0007' }, "знаки смены направления и управляющие — прочь: ${bot.title}")
    }

    @Test
    fun prViewFromCorpus() {
        assertEquals(listOf("gh", "pr", "view", "12", "--json", GitHubCommands.VIEW_FIELDS), GitHubCommands.prView(12).valueOrNull())
        assertIs<Outcome.Failure>(GitHubCommands.prView(0))
        val v = GitHubCommands.parsePrView(corpus("pr-view.json")).valueOrNull()!!
        assertEquals("MERGEABLE", v.mergeable)
        assertEquals("APPROVED", v.pr.review)
        assertTrue(v.body.startsWith("Что сделано:\n- hello.txt"))
        assertEquals(listOf(GhNote("reviewer", null, "Посмотрю вечером", "2026-11-09T10:10:00Z"), GhNote("reviewer", "APPROVED", "", "2026-11-09T10:20:00Z")), v.notes)
    }

    /** Ответ не по формату — `CONNECTOR_REPLY_INVALID` с причинами, а не догадка (D-05). */
    @Test
    fun malformedRepliesAreRejected() {
        fun code(o: Outcome<*>) = (o as Outcome.Failure).errors.single().code
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, code(GitHubCommands.parsePrList(listOf("не json"))))
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, code(GitHubCommands.parsePrList(listOf("{}"))))
        val noTitle = corpus("pr-list.json").single().replaceFirst("\"title\":\"Задача t-01: hello.txt\",", "")
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, code(GitHubCommands.parsePrList(listOf(noTitle))))
        val extra = corpus("pr-view.json").single().replaceFirst("{", "{\"secret\":1,")
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, code(GitHubCommands.parsePrView(listOf(extra))), "неизвестное поле — ошибка")
        val http = corpus("pr-view.json").single().replace("\"url\":\"https://github.com/o/r/pull/12\"}", "\"url\":\"javascript:x\"}")
        assertEquals(DiagCode.CONNECTOR_REPLY_INVALID, code(GitHubCommands.parsePrView(listOf(http))), "адрес PR — только https")
        val long = GitHubCommands.external("а".repeat(GitHubCommands.MAX_TEXT + 10))
        assertEquals(GitHubCommands.MAX_TEXT + 1, long.length)
    }

    /** Отказы `gh` — понятной причиной: нет входа, нет удалённого, предел, нет самого `gh` (D-05). */
    @Test
    fun ghFailuresAreNamed() {
        fun code(exit: Int, vararg err: String) = GitHubCommands.failure("gh pr list", exit, err.toList()).code
        assertEquals(DiagCode.GITHUB_AUTH_EXPIRED, code(4, "To get started with GitHub CLI, please run:  gh auth login"))
        assertEquals(DiagCode.GITHUB_NOT_FOUND, code(1, "no git remotes found"))
        assertEquals(DiagCode.GITHUB_NOT_FOUND, code(1, "none of the git remotes configured for this repository point to a known GitHub host."))
        assertEquals(DiagCode.GITHUB_RATE_LIMITED, code(1, "API rate limit exceeded for user"))
        assertEquals(DiagCode.PROCESS_EXIT_NONZERO, code(127, "sh: gh: not found"))
        val other = GitHubCommands.failure("gh pr list", 1, listOf("something else"))
        assertEquals(DiagCode.PROCESS_EXIT_NONZERO, other.code)
        assertTrue("код 1" in other.message, other.message)
    }

    /** Комментарий и слияние (`DPmetaApp:D-80`): строгие аргументы; слияние — merge-коммитом и только показанного коммита. */
    @Test
    fun commentAndMergeArguments() {
        assertEquals(listOf("gh", "pr", "comment", "12", "--body", "Спасибо\nпосмотрю"), GitHubCommands.prComment(12, "Спасибо\nпосмотрю").valueOrNull())
        for (bad in listOf("", "   ", "a".repeat(GitHubCommands.MAX_COMMENT + 1), "звонок\u0007", "подмена\u202e")) {
            assertEquals(DiagCode.VALUE_INVALID, (GitHubCommands.prComment(12, bad) as Outcome.Failure).errors.single().code, bad.take(20))
        }
        val sha = "1".repeat(40)
        assertEquals(listOf("gh", "pr", "merge", "12", "--merge", "--match-head-commit", sha), GitHubCommands.prMerge(12, sha).valueOrNull())
        for (bad in listOf("HEAD", "1".repeat(39), "Z".repeat(40), "--admin")) assertIs<Outcome.Failure>(GitHubCommands.prMerge(12, bad), bad)
        assertIs<Outcome.Failure>(GitHubCommands.prMerge(0, sha))
        assertEquals("o/r", GitHubCommands.repoOf("https://github.com/o/r/pull/12"))
        assertNull(GitHubCommands.repoOf("https://example.org/o/r/pull/12"))
        assertNull(GitHubCommands.repoOf("https://github.com/o/r/pull/12/../../x"))
        assertEquals("1".repeat(40), GitHubCommands.parsePrView(corpus("pr-view.json")).valueOrNull()!!.headSha)
        val badSha = corpus("pr-view.json").single().replace("\"headRefOid\":\"${"1".repeat(40)}\"", "\"headRefOid\":\"main\"")
        assertIs<Outcome.Failure>(GitHubCommands.parsePrView(listOf(badSha)), "голова — только 40 знаков hex")
    }
}
