package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.verification.VerifyStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/** Копия настроек приложения (D-51): targets, проект и ключи хостов переживают переустановку. */
class SettingsBackupTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(7))

    private fun snapshot(): SettingsSnapshot {
        val phone = TargetForm.validate(ids.next(IdKinds.TARGET), "телефон", "127.0.0.1", "8022", "u0_a1", false, "/data/data/com.termux/files/home/dpai/bin/dpai", "/data/data/com.termux/files/home/.claude-slot-1").valueOrNull()!!
        val pc = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "pc.tail", "22", "dev", true, "C:/dpai/bin/dpai.bat", null).valueOrNull()!!
        val project = ProjectEntry(ids.next(IdKinds.PROJECT), ids.next(IdKinds.REPOSITORY), pc.id, "C:/src/app", "C:/src/trees", "main", listOf(VerifyStep("проверка", listOf("python", "-m", "unittest"))))
        return SettingsSnapshot(listOf(phone, pc), project, "[127.0.0.1]:8022 ssh-ed25519 AAAA1\npc.tail ssh-ed25519 AAAA2\n")
    }

    @Test
    fun roundTripKeepsEverything() {
        val s = snapshot()
        val text = SettingsBackup.write(s, 1_700_000_000_000, "0.1.0-dev.1")
        assertEquals(s, SettingsBackup.read(text).valueOrNull())
        val bare = s.copy(project = null)
        assertEquals(bare, SettingsBackup.read(SettingsBackup.write(bare, 1, null)).valueOrNull())
    }

    @Test
    fun digestIgnoresTimeButSeesChanges() {
        val s = snapshot()
        assertEquals(SettingsBackup.digest(s), SettingsBackup.digest(SettingsBackup.read(SettingsBackup.write(s, 42, "x")).valueOrNull()!!))
        assertNotEquals(SettingsBackup.digest(s), SettingsBackup.digest(s.copy(knownHosts = s.knownHosts + "x ssh-ed25519 AAAA3\n")))
    }

    @Test
    fun brokenOrForeignFilesAreRefused() {
        val s = snapshot()
        val orphan = s.copy(targets = s.targets.take(1))
        assertIs<Outcome.Failure>(SettingsBackup.read(SettingsBackup.write(orphan, 1, null)), "проект на target, которого нет")
        assertIs<Outcome.Failure>(SettingsBackup.read("""{"format":"dpai.app-targets","version":2,"targets":[]}"""))
        assertIs<Outcome.Failure>(SettingsBackup.read("не json"))
    }
}
