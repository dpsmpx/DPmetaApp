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

    private fun entry(
        platform: TargetPlatform = TargetPlatform.POSIX,
        configDir: String? = "/home/u/.dpai/slot a",
        executor: com.dpsmpx.dpai.core.ids.Executor = com.dpsmpx.dpai.core.ids.Executor.CLAUDE,
    ) = TargetEntry(ids.next(IdKinds.TARGET), "пк", "10.0.0.2", 22, "u", platform, "/home/u/dpai-agent", configDir, executor = executor)

    @Test
    fun claudeRunsInTheSlotDirectoryThroughALoginShell() {
        val cmd = TerminalLaunch.claude(entry()).valueOrNull()!!
        assertEquals(listOf("sh", "-lc"), cmd.take(2))
        assertTrue(cmd[2].startsWith("exec env -u ") && cmd[2].endsWith(" 'CLAUDE_CONFIG_DIR=/home/u/.dpai/slot a' claude"), cmd[2])
        // Переменные, перекрывающие вход слота (D-29), сняты после профиля оболочки входа.
        for (v in com.dpsmpx.dpai.accounts.SlotEnvironment.OVERRIDING) assertTrue("-u $v " in cmd[2], v)
        // 24-битный цвет (терминал:A-23).
        assertTrue(" COLORTERM=truecolor " in cmd[2], cmd[2])
    }

    @Test
    fun windowsAndTargetsWithoutSlotAreRefused() {
        for (t in listOf(entry(TargetPlatform.WINDOWS), entry(configDir = null))) {
            assertEquals(DiagCode.UNSUPPORTED, (TerminalLaunch.claude(t) as Outcome.Failure).diagnostics.first().code)
        }
    }
    /** D-100: вход Codex — по коду устройства в каталоге слота; ключи API сняты. */
    @Test
    fun codexLoginUsesDeviceCodeInSlotHome() {
        val t = entry(configDir = "/root/.codex-slot-1", executor = com.dpsmpx.dpai.core.ids.Executor.CODEX)
        val login = TerminalLaunch.login(t).valueOrNull()!!
        assertTrue(login[2].endsWith(" CODEX_HOME=/root/.codex-slot-1 codex login --device-auth"), login[2])
        assertTrue("-u OPENAI_API_KEY " in login[2], login[2])
        assertTrue(TerminalLaunch.claude(t).valueOrNull()!![2].endsWith(" codex"), "без входа — интерактивный codex")
    }

    /** D-100: Gemini — без браузера, способ входа «аккаунт Google» засевается, если его нет. */
    @Test
    fun geminiLoginSeedsGoogleAuthWithoutBrowser() {
        val t = entry(configDir = "/root/.gemini-slot-1", executor = com.dpsmpx.dpai.core.ids.Executor.GEMINI)
        val cmd = TerminalLaunch.login(t).valueOrNull()!![2]
        assertTrue("GEMINI_CLI_HOME=/root/.gemini-slot-1 NO_BROWSER=true sh -c " in cmd, cmd)
        assertTrue("oauth-personal" in cmd && cmd.endsWith("exec gemini'"), cmd)
        assertTrue("-u GEMINI_API_KEY " in cmd && "-u GOOGLE_API_KEY " in cmd, cmd)
    }

    /** Имя сессии tmux (D-111): латиница, цифры, `-`; target, вид и номер. */
    @Test
    fun tmuxNameIsSafeAndStable() {
        val n = TerminalLaunch.tmuxName("a1b2", "tgt_01HX9ABCDEF", "Claude", 2)
        assertTrue(Regex("dpai-a1b2-[a-z0-9-]+-claude-2").matches(n), n)
        assertEquals(n, TerminalLaunch.tmuxName("a1b2", "tgt_01HX9ABCDEF", "Claude", 2), "то же имя — та же сессия")
        assertTrue(n != TerminalLaunch.tmuxName("c3d4", "tgt_01HX9ABCDEF", "Claude", 2), "другое устройство — другая сессия")
        assertTrue(TerminalLaunch.tmuxName("..", "x;rm -rf /", "sh", 1).all { it.isLetterOrDigit() || it == '-' })
    }

    /** Команда с tmux (D-111): та же, что у `TmuxSession` (проверка настоящим `sh` — в `execution`). */
    @Test
    fun inTmuxDelegatesToTmuxSession() {
        val name = TerminalLaunch.tmuxName("d", "t1", "sh", 1)
        val cmd = TerminalLaunch.inTmux(emptyList(), name).valueOrNull()!!
        assertEquals(listOf("sh", "-lc"), cmd.take(2))
        assertTrue("tmux -u new -A -s $name && exit 0" in cmd[2] && TerminalLaunch.NO_TMUX in cmd[2], cmd[2])
    }
}
