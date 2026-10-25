package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.testkit.RealTime
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Канал своего терминала (D-71): отказы — кодами без сервера; оболочка
 * с PTY — на настоящем target (`docs/TESTING.md`, §8).
 */
class JschShellTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun withoutKnownHostsNoTerminalOpens() {
        val e = SshEndpoint.of("127.0.0.1", "u", 22, knownHostsFile = dir.resolve("missing").toString()).valueOrNull()!!
        val o = JschShell(e, TargetPlatform.POSIX).open(80, 24, onOutput = { _, _ -> }, onClosed = {})
        assertEquals(DiagCode.SSH_HOST_KEY_REJECTED, (o as Outcome.Failure).diagnostics.first().code)
    }

    private fun realTarget(knownHosts: String? = System.getProperty("dpai.test.ssh.knownHosts")): Pair<SshEndpoint, TargetPlatform> {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null, "dpai.test.ssh не задан: проверка SSH — на настоящем target")
        val (userHost, port, platform) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            identityFile = System.getProperty("dpai.test.ssh.identity"), knownHostsFile = knownHosts,
        ).valueOrNull()!!
        return endpoint to (if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX)
    }

    @Test
    fun unknownHostKeyIsRejectedOnRealTarget() {
        val (endpoint, platform) = realTarget(dir.resolve("empty_known_hosts").createFile().toString())
        val o = JschShell(endpoint, platform).open(80, 24, onOutput = { _, _ -> }, onClosed = {})
        assertEquals(DiagCode.SSH_HOST_KEY_REJECTED, (o as Outcome.Failure).diagnostics.first().code)
    }

    /** Оболочка с PTY: размер окна виден программе и меняется; выход — с кодом. */
    @Test
    fun shellWithPtyOnRealTarget() {
        val (endpoint, platform) = realTarget()
        assumeTrue(platform == TargetPlatform.POSIX, "stty — на POSIX target")
        val out = ByteArrayOutputStream()
        val exit = AtomicReference<Int?>(-1)
        fun text() = synchronized(out) { out.toString(Charsets.UTF_8) }
        val ch = JschShell(endpoint, platform).open(
            100, 30,
            onOutput = { b, n -> synchronized(out) { out.write(b, 0, n) } },
            onClosed = { exit.set(it) },
        ).valueOrNull()!!
        ch.write("stty size; echo \"T=\$TERM\"\n".toByteArray())
        RealTime.await(20.seconds, "размер окна в PTY") { text().takeIf { "30 100" in it && "T=xterm-256color" in it } }
        ch.resize(120, 40)
        ch.write("stty size\n".toByteArray())
        RealTime.await(20.seconds, "новый размер окна") { text().takeIf { "40 120" in it } }
        ch.write("exit 3\n".toByteArray())
        assertTrue(ch.awaitClosed(20.seconds), "канал не закрылся после exit")
        assertEquals(3, exit.get())
    }

    /** Команда вместо оболочки (как `claude` слота) — тоже в PTY. */
    @Test
    fun commandWithPtyOnRealTarget() {
        val (endpoint, platform) = realTarget()
        assumeTrue(platform == TargetPlatform.POSIX, "test -t — на POSIX target")
        val out = ByteArrayOutputStream()
        val exit = AtomicReference<Int?>(-1)
        val ch = JschShell(endpoint, platform).open(
            80, 24, listOf("sh", "-c", "test -t 0 && echo tty-yes; exit 5"),
            onOutput = { b, n -> synchronized(out) { out.write(b, 0, n) } },
            onClosed = { exit.set(it) },
        ).valueOrNull()!!
        assertTrue(ch.awaitClosed(20.seconds), "команда не завершилась")
        assertTrue("tty-yes" in synchronized(out) { out.toString(Charsets.UTF_8) })
        assertEquals(5, exit.get())
    }
}
