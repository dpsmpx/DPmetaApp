package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.TargetPlatform
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Восстановление после переустановки (D-54): команда, код, разбор, слияние настроек, ключ на ПК. */
class TermuxRestoreTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(5))
    private val key = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTY= dpai-android"
    private val nonce = "0123456789abcdef0123456789abcdef"
    private val home = "/data/data/com.termux/files/home"
    private val hostLine = "[127.0.0.1]:8022 ssh-ed25519 NEWKEY"

    @Test
    fun commandReplacesOldAppKeyAndLeavesTheCode() {
        val cmd = assertNotNull(TermuxRestore.command(key, nonce))
        assertTrue('\n' !in cmd)
        assertTrue("sed -i '/ dpai-android\$/d' ~/.ssh/authorized_keys" in cmd, cmd)
        assertTrue("echo 'from=\"127.0.0.1,::1\" $key' >> ~/.ssh/authorized_keys" in cmd, cmd)
        assertTrue("printf %s $nonce > ~/.dpai/restore-code" in cmd, cmd)
        assertNull(TermuxRestore.command("ssh-ed25519 AAAA' ; rm -rf ~ ; ' dpai-android", nonce))
        assertNull(TermuxRestore.command(key, "short"))
    }

    @Test
    fun parseAndCodeCheck() {
        val settings = SettingsBackup.write(SettingsSnapshot(emptyList(), null, ""), 1, null).trim()
        val out = listOf(
            "code=$nonce", "user=u0_a321", "agent=$home/dpai/bin/dpai",
            "slot=$home/.claude-slot-1", "slot=$home/.claude-slot-2", "workspace=yes",
            "settings-begin", settings, "", "settings-end",
        )
        val f = TermuxRestore.parse(out)
        assertEquals("u0_a321", f.user)
        assertEquals("$home/dpai/bin/dpai", f.agent)
        assertEquals(listOf("$home/.claude-slot-1", "$home/.claude-slot-2"), f.slots)
        assertTrue(f.workspace)
        assertEquals(settings, f.settings)
        assertTrue(TermuxRestore.codeMatches(f, nonce))
        assertFalse(TermuxRestore.codeMatches(f.copy(code = ""), nonce), "подставной сервер кода не знает")
        assertFalse(TermuxRestore.codeMatches(f.copy(code = ""), ""), "пустой код не совпадает с пустым")
        assertFalse(TermuxRestore.codeMatches(f.copy(code = "0123456789abcdef0123456789abcdee"), nonce))
    }

    @Test
    fun emptySettingsComeFromTheTermuxCopyAndSlotsBecomeTargets() {
        val pc = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "100.1.2.3", "22", "dev", true, "C:/Users/dev/dpai-agent.cmd", null).valueOrNull()!!
        val oldTermux = TargetForm.validate(ids.next(IdKinds.TARGET), "Termux", "127.0.0.1", "8022", "u0_a100", false, "$home/dpai/bin/dpai", "$home/.claude-slot-1").valueOrNull()!!
        val copy = SettingsSnapshot(listOf(oldTermux, pc), null, "[127.0.0.1]:8022 ssh-ed25519 OLDKEY\n[127.0.0.1]:8022 ecdsa-sha2-nistp256 OLD2\n100.1.2.3 ssh-ed25519 PCKEY\n")
        val found = TermuxRestore.Found(nonce, "u0_a321", "$home/dpai/bin/dpai", listOf("$home/.claude-slot-1", "$home/.claude-slot-2"), true, SettingsBackup.write(copy, 1, null))
        val (s, report) = TermuxRestore.merge(SettingsSnapshot(emptyList(), null, ""), found, hostLine, ids)
        assertEquals(listOf("Termux", "ПК", "Termux (claude-slot-2)"), s.targets.map { it.label })
        assertEquals("u0_a321", s.targets.first().user, "пользователь Termux — из ответа")
        assertEquals(oldTermux.id, s.targets.first().id, "прежний target с прежним id: журнал и слоты сходятся")
        assertEquals("$home/.claude-slot-2", s.targets.last().configDir)
        assertEquals(setOf(hostLine, "100.1.2.3 ssh-ed25519 PCKEY"), s.knownHosts.trim().lines().toSet(), "прежние ключи Termux заменены, ключ ПК сохранён")
        assertTrue(report.first().startsWith("настройки — из копии в Termux"))
    }

    @Test
    fun withoutCopyOneTargetPerSlotOrABareOne() {
        val bare = TermuxRestore.Found(nonce, "u0_a321", null, emptyList(), false, null)
        val (s, _) = TermuxRestore.merge(SettingsSnapshot(emptyList(), null, ""), bare, hostLine, ids)
        assertEquals(1, s.targets.size)
        assertNull(s.targets.single().configDir)
        assertEquals(SetupGuide.AGENT, s.targets.single().agent)
        val current = s
        val again = TermuxRestore.merge(current, bare.copy(slots = listOf("$home/.claude-slot-1")), hostLine, ids).first
        assertEquals(2, again.targets.size, "текущие настройки не заменяются копией, слот добавляется")
    }

    @Test
    fun pcReauthorizeGoesThroughTheWorkspaceKey() {
        val win = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "100.1.2.3", "22", "dev", true, "C:/Users/dev/dpai-agent.cmd", null).valueOrNull()!!
        val cmd = TermuxRestore.pcReauthorize(win, key)
        assertIs<Outcome.Success<String>>(RemoteShell.join(TargetPlatform.POSIX, cmd))
        assertEquals(listOf("22", "dev@100.1.2.3"), cmd.subList(cmd.size - 3, cmd.size - 1))
        val encoded = cmd.last().substringAfterLast(' ')
        val script = Base64.getDecoder().decode(encoded).toString(Charsets.UTF_16LE)
        assertTrue("'from=\"100.64.0.0/10\" $key'" in script && "-notmatch ' dpai-android$'" in script, script)
        val linux = win.copy(platform = TargetPlatform.POSIX)
        val sh = Base64.getDecoder().decode(TermuxRestore.pcReauthorize(linux, key).last().split(' ')[1]).toString(Charsets.UTF_8)
        assertTrue("echo 'from=\"100.64.0.0/10\" $key' >> ~/.ssh/authorized_keys" in sh, sh)
        assertTrue(TermuxRestore.reauthorized(listOf("x", "dpai-reauthorized")))
    }
}
