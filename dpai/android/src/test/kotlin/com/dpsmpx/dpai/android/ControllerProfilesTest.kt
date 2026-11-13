package com.dpsmpx.dpai.android

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.ui.ManualProfile
import com.dpsmpx.dpai.ui.ManualProfileKind
import com.dpsmpx.dpai.ui.ProjectEntry
import com.dpsmpx.dpai.ui.ProjectWorkspaceView
import com.dpsmpx.dpai.ui.SettingsSnapshot
import com.dpsmpx.dpai.ui.TargetForm
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ControllerProfilesTest {
    @Test
    fun runningProfilePreventsConfigReplacementAndKeepsItsAnchorOutsideCloudData() {
        val dir = Files.createTempDirectory("profile-controller").toFile()
        try {
            val ids = IdGenerator(ManualClock(), RandomSource.seeded(135))
            val target = TargetForm.inApp(ids.next(IdKinds.TARGET), "Linux", "/root/.claude-slot-1").valueOrNull()!!
            val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), target.id, "/root/repo", "/root/trees", "main", emptyList())
            val controller = Controller(java.io.File(dir, "controller"), "test")
            assertIs<Outcome.Success<Unit>>(controller.setProject(project))
            val profile = ManualProfile.form(ManualProfileKind.BUILD, "make", "60").valueOrNull()!!
            assertTrue(controller.manualProfiles.prepare(project, target, ProjectWorkspaceView.of(project, listOf(target), true).main, profile) is Outcome.Success)
            assertIs<Outcome.Failure>(controller.setProject(null))
            assertIs<Outcome.Failure>(controller.rename(target, "other"))
            assertIs<Outcome.Failure>(controller.restore(SettingsSnapshot(emptyList(), null, "changed")))
            assertEquals(project, controller.project())
            assertEquals(null, java.io.File(controller.knownHostsFile).takeIf { it.exists() })
            assertTrue(java.io.File(dir, "manual-profiles/docs/active").exists())
            assertTrue(Controller(java.io.File(dir, "controller"), "test").manualProfiles.blocked)
        } finally { dir.deleteRecursively() }
    }
}
