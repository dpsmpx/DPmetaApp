package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LatestPullRequestTest {
    @TempDir lateinit var dir: Path
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(191))
    private val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Linux", "/root/claude").valueOrNull()!!
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/repo", "/root/trees", "main", emptyList())
    private val output = LatestPullRequest.MARKER + """{"number":233,"branch":"build/selfbuild","head":"${"a".repeat(40)}","url":"https://github.com/unit/repo/pull/233"}"""

    @Test fun bindingKeepsIdentitySettingsSelectionAndIsIdempotentAfterRestart() {
        val other = project.copy(project = ids.next(IdKinds.PROJECT), repository = ids.next(IdKinds.REPOSITORY), root = "/root/other")
        val projects = Projects(listOf(project, other), other.project, project.project)
        val run = ManualRun(ids.next(IdKinds.SESSION), project, target, project.root, "main", LatestPullRequest.profile())
        val updated = LatestPullRequest.rebind(projects, run, output).valueOrNull()!!
        assertEquals(project.copy(integrationBranch = "build/selfbuild"), updated.projects.first())
        assertEquals(projects.copy(projects = updated.projects), updated)
        assertEquals(updated, LatestPullRequest.rebind(updated, run, output).valueOrNull())
        assertEquals(updated, Projects.fromJson(Projects.toJson(updated)).valueOrNull())
        assertIs<Outcome.Failure>(LatestPullRequest.rebind(projects.copy(projects = listOf(project.copy(root = "/root/new"))), run, output))
        assertIs<Outcome.Failure>(LatestPullRequest.rebind(projects, run.copy(path = "/root/tree"), output))
        assertTrue(LatestPullRequest.profile().valid)
        assertEquals(LatestPullRequest.profile(), LatestPullRequest.profile().resolve(emptyMap()).valueOrNull())
    }

    @Test fun invalidOrMissingEvidenceCannotChangeBinding() {
        assertIs<Outcome.Failure>(LatestPullRequest.ready("код выхода 0"))
        for (bad in listOf(output.replace("build/selfbuild", "--force"), output.replace("/pull/233", "/pull/1"), output.replace("a".repeat(40), "bad"))) {
            assertIs<Outcome.Failure>(LatestPullRequest.ready(bad))
        }
    }

    @Test fun actualGitCheckoutRefusesDirtyTreesDivergenceAndMovedPr() {
        val script = dir.resolve("latest.py").toFile().apply {
            writeText(this@LatestPullRequestTest.javaClass.getResource("/com/dpsmpx/dpai/ui/latest_pull_request.py")!!.readText())
        }
        val tests = dir.resolve("test_latest.py").toFile().apply {
            writeText(this@LatestPullRequestTest.javaClass.getResource("/latest-pr/test_latest_pull_request.py")!!.readText())
        }
        val r = LocalProcessHost().run(ProcessSpec(listOf("python3", tests.path, script.path), dir.toString()), 90.seconds).valueOrNull()!!
        assertTrue(r.ok, (r.stdout + r.stderr).joinToString("\n"))
    }
}
