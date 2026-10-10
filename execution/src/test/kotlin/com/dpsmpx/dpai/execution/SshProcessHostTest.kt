package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.Completed
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.Detacher
import com.dpsmpx.dpai.execution.ssh.RemoteShell
import com.dpsmpx.dpai.execution.ssh.SshProcessHost
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SSH к target (D-26, D-31): ключ хоста проверяется всегда (T2), вход —
 * только ключом, аргументы доходят до target как есть или отвергаются
 * (D-05), отказы SSH различаются по причине.
 */
class SshProcessHostTest {
    private val endpoint = SshEndpoint.of("build.example", "dev", 2222, identityFile = "/k/id_ed25519", knownHostsFile = "/k/known_hosts").valueOrNull()!!

    /** Хост, который не запускает процессов, а запоминает команду. */
    private class Recording : ProcessHost {
        var last: ProcessSpec? = null

        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            last = spec
            return Outcome.failure(DiagCode.PROCESS_START_FAILED, "запись")
        }
    }

    @Test
    fun argvChecksHostKeyAndNeverAsksForPassword() {
        val local = Recording()
        SshProcessHost(endpoint, TargetPlatform.POSIX, local).start(ProcessSpec(listOf("dpai", "agent", "doctor")))
        val argv = local.last!!.command
        assertEquals("ssh", argv.first())
        assertTrue("StrictHostKeyChecking=yes" in argv)
        assertTrue("BatchMode=yes" in argv)
        assertTrue("UserKnownHostsFile=/k/known_hosts" in argv)
        // Ревью A-19: путь с пробелом — одним файлом в кавычках, `%` — буквально.
        val spaced = SshEndpoint.of("build.example", "dev", 22, identityFile = "/k/my keys/id%1", knownHostsFile = "/k/my hosts/known_hosts").valueOrNull()!!
        SshProcessHost(spaced, TargetPlatform.POSIX, local).start(ProcessSpec(listOf("dpai", "agent", "doctor")))
        val spacedArgv = local.last!!.command
        assertTrue("UserKnownHostsFile=\"/k/my hosts/known_hosts\"" in spacedArgv, spacedArgv.toString())
        assertEquals("/k/my keys/id%%1", spacedArgv[spacedArgv.indexOf("-i") + 1])
        assertEquals(listOf("-p", "2222"), argv.subList(argv.indexOf("-p"), argv.indexOf("-p") + 2))
        assertEquals(listOf("--", "build.example", "dpai agent doctor"), argv.takeLast(3))
    }

    @Test
    fun environmentAndWorkDirAreAgentsBusiness() {
        val r = SshProcessHost(endpoint, TargetPlatform.POSIX, Recording()).start(ProcessSpec(listOf("x"), environment = mapOf("A" to "1")))
        assertEquals(DiagCode.UNSUPPORTED, (r as Outcome.Failure).errors.single().code)
    }

    @Test
    fun posixQuotingKeepsArgumentsIntact() {
        assertEquals("dpai --dir '/a b/c' 'it'\\''s' ''", RemoteShell.join(TargetPlatform.POSIX, listOf("dpai", "--dir", "/a b/c", "it's", "")).valueOrNull())
        assertEquals("'$(rm -rf ~)' ';' '|'", RemoteShell.join(TargetPlatform.POSIX, listOf("$(rm -rf ~)", ";", "|")).valueOrNull())
    }

    @Test
    fun windowsRejectsWhatCmdWouldReinterpret() {
        assertEquals("dpai --dir \"C:\\Program Files\\x\"", RemoteShell.join(TargetPlatform.WINDOWS, listOf("dpai", "--dir", "C:\\Program Files\\x")).valueOrNull())
        for (bad in listOf("%PATH%", "a\"b", "x^y", "")) {
            assertIs<Outcome.Failure>(RemoteShell.join(TargetPlatform.WINDOWS, listOf("dpai", bad)), bad)
        }
    }

    @Test
    fun endpointRejectsOptionLikeValues() {
        assertIs<Outcome.Failure>(SshEndpoint.of("-oProxyCommand=x", "dev"))
        assertIs<Outcome.Failure>(SshEndpoint.of("host", "-l"))
        assertIs<Outcome.Failure>(SshEndpoint.of("host", "dev", identityFile = "-i"))
        assertIs<Outcome.Failure>(SshEndpoint.of("host", "dev", port = 0))
    }

    @Test
    fun sshFailuresAreClassified() {
        fun err(text: String) = SshProcessHost.classify(Completed(255, emptyList(), listOf(text)), "target t")!!.code
        assertEquals(DiagCode.SSH_HOST_KEY_REJECTED, err("Host key verification failed."))
        assertEquals(DiagCode.SSH_AUTH_FAILED, err("dev@build.example: Permission denied (publickey)."))
        assertEquals(DiagCode.SSH_CONNECTION_FAILED, err("ssh: connect to host build.example port 2222: Connection refused"))
        assertNull(SshProcessHost.classify(Completed(1, emptyList(), listOf("Permission denied")), "target t"), "код не 255 — отказ удалённой команды")
    }

    @Test
    fun windowsCommandLineRoundTrips() {
        assertEquals(
            "java -cp \"C:\\a b\\x.jar\" \"say \\\"hi\\\"\" tail\\ \"end dir\\\\\"",
            Detacher.windowsCommandLine(listOf("java", "-cp", "C:\\a b\\x.jar", "say \"hi\"", "tail\\", "end dir\\")),
        )
    }
}
