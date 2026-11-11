package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.state.TaskState
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.git.Worktree
import com.dpsmpx.dpai.tasks.Priority
import com.dpsmpx.dpai.tasks.TaskPolicy
import com.dpsmpx.dpai.tasks.TaskRecord
import com.dpsmpx.dpai.tasks.VerificationRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectWorkspaceTest {
    private val clock = ManualClock()
    private val ids = IdGenerator(clock, RandomSource.seeded(132))
    private val target = TargetEntry(ids.next(IdKinds.TARGET), "Linux телефона", "", 22, "", TargetPlatform.POSIX, "dpai", inApp = true)
    private val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/project", "/root/trees", "main", emptyList())
    private val task = TaskRecord(ids.next(IdKinds.TASK), "правка", "сделать", Priority.NORMAL, emptySet(), emptySet(), TaskPolicy(), clock.now())
    private val tree = Worktree(ids.next(IdKinds.WORKTREE), project.repository, "/root/trees/feature", "task/feature")
    private fun view(tree: Worktree? = this.tree, p: ProjectEntry = project, ready: Boolean = true) =
        ProjectWorkspaceView.of(p, listOf(target), ready, listOf(task), trees = tree?.let { mapOf(task.id to it) }.orEmpty())

    @Test
    fun mainAndTaskLocationsNeverFallBackToEachOther() {
        val v = view()
        assertEquals(project.root, v.location(null)!!.path)
        assertEquals(tree.path, v.location(task.id)!!.path)
        assertNotEquals(v.main.key, v.location(task.id)!!.key)
        assertNull(view(null).location(task.id))
        assertNull(view(tree.copy(removed = true)).location(task.id))
        assertNull(view(tree.copy(repository = ids.next(IdKinds.REPOSITORY))).location(task.id))
    }

    @Test
    fun ownershipCanChangeAfterScreenSnapshotAndBlocksOnlyWrite() {
        val expected = view().location(task.id)!!
        val claimed = view(tree.copy(owner = ids.next(IdKinds.SESSION)))
        assertTrue(claimed.allow(project, expected, false).isSuccess)
        assertIs<Outcome.Failure>(claimed.allow(project, expected, true))
        assertTrue(view().allow(project, expected, true).isSuccess)
    }

    @Test
    fun staleProjectRootProfileTargetAndTreeAreRejected() {
        val expected = view().location(task.id)!!
        for (changed in listOf(tree.copy(path = "/root/elsewhere"), tree.copy(branch = "other"), tree.copy(id = ids.next(IdKinds.WORKTREE)))) {
            assertIs<Outcome.Failure>(view(changed).allow(project, expected, true))
        }
        assertIs<Outcome.Failure>(view(p = project.copy(root = "/root/new")).allow(project, expected, true))
        assertIs<Outcome.Failure>(view(p = project.copy(integrationBranch = "dev")).allow(project, expected, true))
        assertIs<Outcome.Failure>(view(p = project.copy(target = ids.next(IdKinds.TARGET))).allow(project, expected, false))
        assertIs<Outcome.Failure>(view(ready = false).allow(project, expected, false))
    }

    @Test
    fun controllerTasksKeepHistoricalVerificationSeparateFromState() {
        val pending = task.copy(state = TaskState.COMPLETION_UNVERIFIED, verification = VerificationRecord("FAILED", null))
        val v = ProjectWorkspaceView.of(project, listOf(target), true, listOf(pending))
        assertTrue(v.tasks.single().canVerify)
        assertEquals("FAILED", v.tasks.single().verification!!.verdict)
        assertFalse(ProjectWorkspaceView.of(project, listOf(target), true, listOf(task.copy(archived = true))).tasks.isNotEmpty())
    }

    @Test
    fun guestPathMappingDoesNotTreatRemoteOrTraversalAsPhoneFiles() {
        assertEquals("project/sub", WorkspacePaths.homeRelative(target, "/root/project/sub"))
        assertEquals("", WorkspacePaths.homeRelative(target, "/root"))
        for (path in listOf("/root/../elsewhere", "/root/a/./b", "/root/a//b", "/rootish/a", "/tmp/root/a", "/root/a\\b", "/root/a\u0000b")) {
            assertNull(WorkspacePaths.homeRelative(target, path), path)
        }
        assertNull(WorkspacePaths.homeRelative(target.copy(inApp = false), "/root/project"))
        assertNull(WorkspacePaths.homeRelative(null, "/root/project"))
    }
}
