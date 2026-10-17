package com.dpsmpx.dpai.ui

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.target.TargetPlatform
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.dpsmpx.dpai.core.result.Outcome

/** Связка «Claude на телефоне — дерево на ПК» (D-53): команды, разбор вывода, ключ хоста. */
class WorkspaceLinkTest {
    private val ids = IdGenerator(ManualClock(), RandomSource.seeded(3))
    private val pc = TargetForm.validate(ids.next(IdKinds.TARGET), "ПК", "100.101.102.103", "22", "dev", true, "C:/Users/dev/dpai-agent.cmd", null).valueOrNull()!!
    private val b64 = "AAAAC3NzaC1lZDI1NTE5AAAAIGq4"

    @Test
    fun publicKeyGetsTheLinkComment() {
        assertEquals("ssh-ed25519 $b64 dpai-workspace", WorkspaceLink.publicKey(listOf("apk: ok", "ssh-ed25519 $b64 root@localhost")))
        assertEquals("ssh-ed25519 $b64 dpai-workspace", WorkspaceLink.publicKey(listOf("ssh-ed25519 $b64")))
        assertNull(WorkspaceLink.publicKey(listOf("ssh-rsa AAAA x", "ошибка")))
    }

    @Test
    fun pcCommandPassesWindowsQuotingAndReplacesOldLink() {
        val cmd = WorkspaceLink.pcAuthorize("ssh-ed25519 $b64 dpai-workspace")
        assertIs<Outcome.Success<String>>(RemoteShell.join(TargetPlatform.WINDOWS, cmd), "без \", %, ^, ! — проходит в cmd.exe")
        val script = Base64.getDecoder().decode(cmd.last()).toString(Charsets.UTF_16LE)
        assertTrue("'from=\"100.64.0.0/10\" ssh-ed25519 $b64 dpai-workspace'" in script, script)
        assertTrue("-notmatch ' dpai-workspace$'" in script, script)
        assertFailsWith<IllegalArgumentException> { WorkspaceLink.pcAuthorize("ssh-ed25519 $b64' ; Remove-Item C:\\ ; ' dpai-workspace") }
    }

    /** ПК с Linux (D-56): ключ связки — в authorized_keys пользователя, из Tailscale, прежняя строка заменяется. */
    @Test
    fun linuxPcGetsTheLinkKeyInItsOwnAuthorizedKeys() {
        val home = java.nio.file.Files.createTempDirectory("dpai-link").toFile()
        try {
            java.io.File(home, ".ssh").mkdirs()
            java.io.File(home, ".ssh/authorized_keys").writeText("ssh-ed25519 AAAAother me@pc\nfrom=\"100.64.0.0/10\" ssh-ed25519 AAAAold dpai-workspace\n")
            val cmd = WorkspaceLink.pcAuthorize("ssh-ed25519 $b64 dpai-workspace", TargetPlatform.POSIX)
            assertIs<Outcome.Success<String>>(RemoteShell.join(TargetPlatform.POSIX, cmd))
            val env = HashMap(System.getenv()).apply { put("HOME", home.path) }
            val r = com.dpsmpx.dpai.execution.process.LocalProcessHost().run(com.dpsmpx.dpai.execution.process.ProcessSpec(cmd, environment = env), kotlin.time.Duration.parse("1m"))
            val done = assertIs<Outcome.Success<com.dpsmpx.dpai.execution.process.Completed>>(r).value
            assertEquals(listOf("dpai-linked"), done.stdout)
            assertEquals(
                listOf("ssh-ed25519 AAAAother me@pc", "from=\"100.64.0.0/10\" ssh-ed25519 $b64 dpai-workspace"),
                java.io.File(home, ".ssh/authorized_keys").readLines(),
            )
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun onlyConfirmedHostLinesOfThisPcGoToTermux() {
        val known = "[127.0.0.1]:8022 ssh-ed25519 T1\n100.101.102.103 ssh-ed25519 P1\n100.101.102.103 ecdsa-sha2-nistp256 P2\n100.101.102.1030 ssh-ed25519 X\n"
        assertEquals(listOf("100.101.102.103 ssh-ed25519 P1", "100.101.102.103 ecdsa-sha2-nistp256 P2"), WorkspaceLink.hostLines(known, pc))
        val other = pc.copy(port = 2222)
        assertEquals("[100.101.102.103]:2222", WorkspaceLink.hostName(other))
        assertEquals(emptyList(), WorkspaceLink.hostLines(known, other))
    }

    @Test
    fun termuxCommandsArePlainArgvForPosix() {
        for (c in listOf(WorkspaceLink.termuxKey(), WorkspaceLink.termuxKnownHosts(pc), WorkspaceLink.termuxCheck(pc))) {
            assertEquals(listOf("proot-distro", "login", "alpine", "--", "sh", "-c"), c.take(6))
            assertIs<Outcome.Success<String>>(RemoteShell.join(TargetPlatform.POSIX, c))
        }
        assertEquals(listOf("22", "dev@100.101.102.103", "C:/Users/dev/dpai-agent.cmd"), WorkspaceLink.termuxCheck(pc).takeLast(3))
        assertTrue(WorkspaceLink.checked(listOf("{\"ok\":false}")))
        assertTrue(!WorkspaceLink.checked(listOf("Permission denied (publickey).")))
    }
}
