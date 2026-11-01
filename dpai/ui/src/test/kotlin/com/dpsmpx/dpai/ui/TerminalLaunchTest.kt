package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.TargetPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Что запускает свой терминал (D-71): `claude` слота — через оболочку входа. */
class TerminalLaunchTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(57))

    private fun entry(platform: TargetPlatform = TargetPlatform.POSIX, configDir: String? = "/home/u/.dpai/slot a") =
        TargetEntry(ids.next(IdKinds.TARGET), "пк", "10.0.0.2", 22, "u", platform, "/home/u/dpai-agent", configDir)

    @Test
    fun claudeRunsInTheSlotDirectoryThroughALoginShell() {
        val cmd = TerminalLaunch.claude(entry()).valueOrNull()!!
        assertEquals(listOf("sh", "-lc"), cmd.take(2))
        assertTrue(cmd[2].startsWith("exec env -u ") && cmd[2].endsWith(" 'CLAUDE_CONFIG_DIR=/home/u/.dpai/slot a' claude"), cmd[2])
        // Переменные, перекрывающие вход слота (D-29), сняты после профиля оболочки входа.
        for (v in com.dpsmpx.dpai.accounts.SlotEnvironment.OVERRIDING) assertTrue("-u $v " in cmd[2], v)
    }

    @Test
    fun windowsAndTargetsWithoutSlotAreRefused() {
        for (t in listOf(entry(TargetPlatform.WINDOWS), entry(configDir = null))) {
            assertEquals(DiagCode.UNSUPPORTED, (TerminalLaunch.claude(t) as Outcome.Failure).diagnostics.first().code)
        }
    }
}
