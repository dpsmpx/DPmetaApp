package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.hashing.Hashing
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.github.Branch
import com.dpsmpx.dpmeta.github.CiState
import com.dpsmpx.dpmeta.github.CommitInfo
import com.dpsmpx.dpmeta.github.Issue
import com.dpsmpx.dpmeta.github.PullRequest
import com.dpsmpx.dpmeta.github.RecentCommit
import com.dpsmpx.dpmeta.model.ObservationSource
import com.dpsmpx.dpmeta.model.ProjectEntry
import com.dpsmpx.dpmeta.model.ProjectList
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.sync.Got
import com.dpsmpx.dpmeta.sync.SourceKind
import com.dpsmpx.dpmeta.sync.SourceReader
import com.dpsmpx.dpmeta.sync.Sync
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issues, последние коммиты и CI проектов пульта (D-176): CI — у открытых PR по голове и у
 * головы основной ветки; источник без них — задачи нет; лимит на CI — отказ задачи.
 * Репозитории — синтетические.
 */
class GitHubSourcesSyncTest {
    private val now = Timestamp(Instant.parse("2026-11-16T10:00:00Z").toEpochMilli())
    private val registry = ProjectList().add(RepoRef.parse("alice/app").valueOrNull()!!).valueOrNull()!!.toRegistry()

    private open class Fake(val ciLimited: Boolean = false) : SourceReader {
        override val source = ObservationSource.GITHUB
        override fun hasActivity(project: ProjectEntry) = true
        override fun pulls(project: ProjectEntry): Outcome<Got<List<PullRequest>>> =
            Outcome.success(Got(listOf(PullRequest(5, "Новое", false, "feature", "bob", Timestamp(1), headSha = "b".repeat(40)), PullRequest(6, "Без головы", true, "x", "bob", Timestamp(2))), Hashing.sha256("pulls")))
        override fun closed(project: ProjectEntry): Outcome<Got<List<PullRequest>>> = Outcome.success(Got(emptyList(), Hashing.sha256("closed")))
        override fun branches(project: ProjectEntry): Outcome<Got<List<Branch>>> = Outcome.success(Got(emptyList(), Hashing.sha256("branches")))
        override fun commit(project: ProjectEntry, sha: String): Outcome<CommitInfo> = Outcome.failure(DiagCode.UNSUPPORTED, "нет", sha)
        override fun file(project: ProjectEntry, path: String): Outcome<Got<String>> = Outcome.failure(DiagCode.GITHUB_NOT_FOUND, "нет", path)
        override fun issues(project: ProjectEntry): Outcome<Got<List<Issue>>>? =
            Outcome.success(Got(listOf(Issue(3, "Баг", "alice", listOf("bob"), listOf("bug"), Timestamp(3), 1)), Hashing.sha256("issues")))
        override fun recentCommits(project: ProjectEntry): Outcome<Got<List<RecentCommit>>>? =
            Outcome.success(Got(listOf(RecentCommit("a".repeat(40), "Голова", "alice", Timestamp(4))), Hashing.sha256("commits")))
        override fun ci(project: ProjectEntry, sha: String): Outcome<Got<CiState>>? = when {
            ciLimited -> Outcome.failure(DiagCode.GITHUB_RATE_LIMITED, "лимит", sha)
            sha.startsWith("a") -> Outcome.success(Got(CiState(CiState.Verdict.RED, 2, listOf("build")), Hashing.sha256("ci-a")))
            else -> Outcome.success(Got(CiState(CiState.Verdict.GREEN, 1, emptyList()), Hashing.sha256("ci-b")))
        }
    }

    private fun run(reader: SourceReader, vararg kinds: SourceKind) =
        Sync.run(Sync.plan(registry).filter { it.kind in kinds }, reader, ManualClock(now))

    private fun payload(r: com.dpsmpx.dpmeta.sync.SyncResult, kind: String): JsonObject = r.observations.single { it.kind == kind }.payload as JsonObject

    @Test
    fun issuesCommitsAndCiArePlannedAndStored() {
        assertTrue(Sync.plan(registry).map { it.kind }.containsAll(listOf(SourceKind.ISSUES, SourceKind.COMMITS)))
        val r = run(Fake(), SourceKind.PULLS, SourceKind.ISSUES, SourceKind.COMMITS)
        assertEquals(emptyList(), r.failures)
        val pulls = (payload(r, "pulls")["pulls"] as JsonArray).items.map { it as JsonObject }
        assertEquals("green", ((pulls.single { (it["number"] as com.dpsmpx.dpai.core.serialization.JsonNumber).toLongExact() == 5L }["ci"] as JsonObject)["verdict"] as JsonString).value)
        assertNull(pulls.single { (it["number"] as com.dpsmpx.dpai.core.serialization.JsonNumber).toLongExact() == 6L }["ci"], "без головы — CI неизвестен")
        val commits = payload(r, "commits")
        assertEquals("red", ((commits["ci"] as JsonObject)["verdict"] as JsonString).value)
        assertEquals("build", (((commits["ci"] as JsonObject)["failing"] as JsonArray).items.single() as JsonString).value)
        assertEquals(1, (payload(r, "issues")["issues"] as JsonArray).items.size)
        assertEquals("github.issues.v1", r.observations.single { it.kind == "issues" }.schema)
    }

    @Test
    fun sourcesWithoutThemSkipSilently() {
        val bare = object : Fake() {
            override fun issues(project: ProjectEntry): Outcome<Got<List<Issue>>>? = null
            override fun recentCommits(project: ProjectEntry): Outcome<Got<List<RecentCommit>>>? = null
            override fun ci(project: ProjectEntry, sha: String): Outcome<Got<CiState>>? = null
        }
        val r = run(bare, SourceKind.PULLS, SourceKind.ISSUES, SourceKind.COMMITS)
        assertEquals(emptyList(), r.failures)
        assertEquals(listOf("pulls"), r.observations.map { it.kind })
        assertTrue((payload(r, "pulls")["pulls"] as JsonArray).items.all { (it as JsonObject)["ci"] == null })
    }

    @Test
    fun hiddenIssuesAreNoAccessNotAFailure() {
        val hidden = object : Fake() {
            override fun issues(project: ProjectEntry): Outcome<Got<List<Issue>>>? = Outcome.failure(DiagCode.GITHUB_AUTH_EXPIRED, "у токена нет прав на это", "alice/app:issues")
        }
        val r = run(hidden, SourceKind.ISSUES)
        assertEquals(emptyList(), r.failures)
        assertEquals(false, (payload(r, "issues")["access"] as com.dpsmpx.dpai.core.serialization.JsonBool).value)
    }

    @Test
    fun rateLimitOnCiFailsTheTaskAndStopsTheRun() {
        val r = run(Fake(ciLimited = true), SourceKind.PULLS, SourceKind.ISSUES, SourceKind.COMMITS)
        assertEquals("pulls" to DiagCode.GITHUB_RATE_LIMITED, r.failures.first().let { it.kind to it.diagnostic.code })
        assertTrue(r.observations.isEmpty(), "лимит — остальное откладывается")
        assertEquals(2, r.deferred)
    }

    @Test
    fun ciChangeAloneChangesTheFingerprint() {
        val first = run(Fake(), SourceKind.PULLS).observations.single()
        val changed = object : Fake() {
            override fun ci(project: ProjectEntry, sha: String): Outcome<Got<CiState>>? =
                Outcome.success(Got(CiState(CiState.Verdict.PENDING, 1, emptyList()), Hashing.sha256("ci-b2")))
        }
        val second = run(changed, SourceKind.PULLS).observations.single()
        assertTrue(first.rawSha256 != second.rawSha256, "проверки дошли без новых PR — новое наблюдение")
    }
}
