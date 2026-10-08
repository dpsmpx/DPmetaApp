package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.target.CheckStatus
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.testkit.RealTime
import java.net.ServerSocket
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * SSH-хост на JSch (D-35): отказы — нужными кодами без сервера; путь
 * целиком — на настоящем target, как `AgentCommandTest` (`docs/TESTING.md`, §8).
 */
class JschProcessHostTest {
    @TempDir
    lateinit var dir: Path

    private fun code(o: Outcome<*>): DiagCode = (o as Outcome.Failure).diagnostics.first().code

    @Test
    fun withoutKnownHostsNothingConnects() {
        val e = SshEndpoint.of("127.0.0.1", "u", 22, knownHostsFile = dir.resolve("missing_known_hosts").toString()).valueOrNull()!!
        assertEquals(DiagCode.SSH_HOST_KEY_REJECTED, code(JschProcessHost(e, TargetPlatform.POSIX).start(ProcessSpec(listOf("true")))))
    }

    @Test
    fun refusedPortIsConnectionFailure() {
        val port = ServerSocket(0).use { it.localPort }
        val known = dir.resolve("known_hosts").createFile().toString()
        val e = SshEndpoint.of("127.0.0.1", "u", port, knownHostsFile = known).valueOrNull()!!
        assertEquals(DiagCode.SSH_CONNECTION_FAILED, code(JschProcessHost(e, TargetPlatform.POSIX, connectTimeout = 3.seconds).start(ProcessSpec(listOf("true")))))
    }

    /** Аудит F (ТЗ, §105): имя хоста не разрешается (DNS) — отказ связи, а не зависание. */
    @Test
    fun unresolvableHostIsConnectionFailure() {
        val known = dir.resolve("known_hosts").createFile().toString()
        val e = SshEndpoint.of("no-such-host.invalid", "u", 22, knownHostsFile = known).valueOrNull()!!
        assertEquals(DiagCode.SSH_CONNECTION_FAILED, code(JschProcessHost(e, TargetPlatform.POSIX, connectTimeout = 3.seconds).start(ProcessSpec(listOf("true")))))
    }

    @Test
    fun environmentIsNotSentOverSsh() {
        val e = SshEndpoint.of("127.0.0.1", "u", 22).valueOrNull()!!
        assertEquals(DiagCode.UNSUPPORTED, code(JschProcessHost(e, TargetPlatform.POSIX).start(ProcessSpec(listOf("true"), workDir = "/tmp"))))
    }

    private fun realTarget(knownHosts: String? = System.getProperty("dpai.test.ssh.knownHosts")): Triple<SshEndpoint, TargetPlatform, String> {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null, "dpai.test.ssh не задан: проверка SSH — на настоящем target")
        val (userHost, port, platform, agent) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            identityFile = System.getProperty("dpai.test.ssh.identity"), knownHostsFile = knownHosts,
        ).valueOrNull()!!
        return Triple(endpoint, if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX, agent)
    }

    /** Ключ хоста не записан — отказ, а не молчаливое добавление (T2). */
    @Test
    fun unknownHostKeyIsRejectedOnRealTarget() {
        val (endpoint, platform, _) = realTarget(dir.resolve("empty_known_hosts").createFile().toString())
        assertEquals(DiagCode.SSH_HOST_KEY_REJECTED, code(JschProcessHost(endpoint, platform).start(ProcessSpec(listOf("true")))))
    }

    /** Тот же путь, что `AgentCommandTest` через OpenSSH, — через JSch: doctor, запуск, остановка. */
    @Test
    fun agentWorksThroughJschOnRealTarget() {
        val (endpoint, platform, agent) = realTarget()
        val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(7))
        val manager = ExecutionTargetManager(Clock.SYSTEM, sshHost = { e, p -> JschProcessHost(e, p) })
        val type = if (platform == TargetPlatform.WINDOWS) TargetType.WINDOWS_REMOTE else TargetType.LINUX_SSH
        val target = ExecutionTarget(ids.next(IdKinds.TARGET), type, "ssh", platform, endpoint, listOf(agent))
        manager.register(target)
        val link = manager.doctor(target.id).valueOrNull()!!.checks.first { it.name == "связь" }
        assertEquals(CheckStatus.OK, link.status, link.detail)
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        val cmd = if (platform == TargetPlatform.WINDOWS) listOf("ping", "-n", "600", "127.0.0.1") else listOf("sleep", "600")
        val started = client.start(run, RunSpec(cmd))
        assertTrue(started.isSuccess, started.diagnostics.toString())
        RealTime.await(60.seconds, "запуск на target") { client.status(run).valueOrNull()?.takeIf { it.state == RunState.RUNNING } }
        val stopped = client.stop(run, 2.seconds).valueOrNull()!!
        assertEquals(RunState.EXITED, stopped.run!!.state)
        assertEquals(emptyList(), stopped.stop!!.survivors)
    }
}
