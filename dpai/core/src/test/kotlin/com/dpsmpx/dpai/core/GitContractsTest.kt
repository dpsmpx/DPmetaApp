package com.dpsmpx.dpai.core

import com.dpsmpx.dpai.core.git.GitHubChecks
import com.dpsmpx.dpai.core.git.GitNames
import com.dpsmpx.dpai.core.git.RepoRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitContractsTest {
    @Test fun repositoryIdentityUsesTheSameValidationForNamesAndPullUrls() {
        assertEquals("owner/repo.name", RepoRef.fromPullUrl("https://github.com/owner/repo.name/pull/42").valueOrNull().toString())
        for (bad in listOf("-owner/repo", "owner/..", "owner/repo.git", "owner/repo/extra", "owner/repo?x")) {
            assertTrue(RepoRef.parse(bad).isFailure, bad)
            assertTrue(RepoRef.fromPullUrl("https://github.com/$bad/pull/42").isFailure, bad)
        }
        for (bad in listOf("http://github.com/a/b/pull/1", "https://github.com.evil/a/b/pull/1", "https://github.com/a/b/pull/1?x", "https://github.com/a/b/pull/1#x", "https://user@github.com/a/b/pull/1")) assertTrue(RepoRef.fromPullUrl(bad).isFailure, bad)
    }

    @Test fun cliBranchRejectsGitInvalidNamesBeforeLaunchingAProcess() {
        for (good in listOf("main", "feature/fix", "release/0.4-dev", "topic./child")) assertTrue(GitNames.cliBranch(good), good)
        for (bad in listOf("-x", "HEAD", "a..b", "a//b", "a/", "/a", ".hidden", "a/.hidden", "a.lock", "a.lock/b", "a.", "a b", "a@{b", "a\\b")) assertFalse(GitNames.cliBranch(bad), bad)
        assertTrue(GitNames.branchRef("исправление/ветка"))
        assertFalse(GitNames.cliBranch("исправление/ветка"))
        assertTrue(GitNames.sha("a".repeat(40)))
        assertFalse(GitNames.sha("A".repeat(40)))
    }

    @Test fun adaptersShareCheckMeaningsAndUnknownNeverPasses() {
        for (state in listOf("SUCCESS", "NEUTRAL", "SKIPPED")) {
            assertEquals(GitHubChecks.Verdict.PASSED, GitHubChecks.state(state))
            assertEquals(GitHubChecks.Verdict.PASSED, GitHubChecks.checkRun("completed", state.lowercase()))
        }
        for (state in listOf("FAILURE", "ERROR", "CANCELLED", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE")) assertEquals(GitHubChecks.Verdict.FAILED, GitHubChecks.state(state))
        assertEquals(GitHubChecks.Verdict.UNKNOWN, GitHubChecks.state("new outcome"))
        assertEquals(GitHubChecks.Verdict.UNKNOWN, GitHubChecks.state(null))
        assertEquals(GitHubChecks.Verdict.FAILED, GitHubChecks.checkRun("completed", "new outcome"))
        assertEquals(GitHubChecks.Verdict.PENDING, GitHubChecks.checkRun("in_progress", null))
        assertEquals(GitHubChecks.Verdict.FAILED, GitHubChecks.statusContext(null))
        assertEquals(GitHubChecks.Verdict.PASSED, GitHubChecks.statusContext("success"))
    }
}
