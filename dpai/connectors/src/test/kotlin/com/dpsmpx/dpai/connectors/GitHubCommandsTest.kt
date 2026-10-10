package com.dpsmpx.dpai.connectors

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}
