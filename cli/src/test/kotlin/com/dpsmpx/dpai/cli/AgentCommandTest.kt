package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.target.CheckStatus
import com.dpsmpx.dpai.execution.target.ExecutionTarget
import com.dpsmpx.dpai.execution.target.ExecutionTargetManager
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.execution.target.TargetType
import com.dpsmpx.dpai.testkit.RealTime
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * `dpai agent` и единый интерфейс к target (критерий этапа 3): локальный
 * target — та же команда `dpai`, что на удалённом, вызванная без SSH; SSH —
 * при заданном хосте (`docs/TESTING.md`, §1).
 */
class AgentCommandTest {
    @TempDir
    lateinit var dir: Path

    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(5))

    @Test
    fun usageErrorsAreCodeTwo() {
        val err = mutableListOf<String>()
        val cli = Cli(out = {}, err = { err += it }, version = "t")
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "status")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "start", "--run", "ses_bad")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "nope")))
    }

    @Test
    fun doctorRepliesWithOneJsonLine() {
        val out = mutableListOf<String>()
        val code = Cli(out = { out += it }, err = {}, version = "t").run(listOf("agent", "doctor", "--home", dir.toString()))
        assertEquals(Cli.OK, code)
        val reply = AgentReply.parse(out.single()).valueOrNull()!!
        assertEquals("t", reply.doctor!!.agentVersion)
    }

    @Test
    fun localTargetStartsAndStopsThroughUnifiedInterface() {
        val clock = Clock.SYSTEM
        val manager = ExecutionTargetManager(clock)
        val target = ExecutionTarget(
            ids.next(IdKinds.TARGET), TargetType.LOCAL, "эта машина", platform(), null,
            AgentCommand.selfCommand(), agentHome = dir.toString(),
        )
        manager.register(target)
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        val started = client.start(run, RunSpec(hangCommand()))
        assertTrue(started.isSuccess, started.diagnostics.toString())
        val channel = RunChannel(client, run)
        val seen = mutableListOf<com.dpsmpx.dpai.core.events.EventEnvelope>()
        RealTime.await(60.seconds, "процесс запущен и пишет вывод") {
            seen += channel.poll().valueOrNull()!!.events
            seen.firstOrNull { it.type == EventType.TARGET_PROCESS_OUTPUT && it.payload["text"] == JsonString("ready") }
        }
        val pid = client.status(run).valueOrNull()!!.pid!!
        val stopped = client.stop(run, 2.seconds).valueOrNull()!!
        assertEquals(RunState.EXITED, stopped.run!!.state)
        assertEquals(emptyList(), stopped.stop!!.survivors)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(CheckStatus.OK, manager.doctor(target.id).valueOrNull()!!.checks.first { it.name == "связь" }.status)
    }

    /**
     * Настоящий target по SSH. Задаётся системным свойством
     * `dpai.test.ssh` = `пользователь@хост:порт:posix|windows:команда агента`,
     * например `dev@build:22:posix:/opt/dpai/bin/dpai`. На target установлен
     * `dpai`, ключ хоста — в known_hosts, вход — ключом.
     */
    @Test
    fun sshTargetStartsAndStopsThroughUnifiedInterface() {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null, "dpai.test.ssh не задан: проверка SSH — на настоящем target")
        val (userHost, port, platform, agent) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt()).valueOrNull()!!
        val p = if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX
        val type = if (p == TargetPlatform.WINDOWS) TargetType.WINDOWS_REMOTE else TargetType.LINUX_SSH
        val manager = ExecutionTargetManager(Clock.SYSTEM, local = LocalProcessHost())
        val target = ExecutionTarget(ids.next(IdKinds.TARGET), type, "ssh", p, endpoint, listOf(agent))
        manager.register(target)
        assertEquals(CheckStatus.OK, manager.doctor(target.id).valueOrNull()!!.checks.first { it.name == "связь" }.status)
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        val cmd = if (p == TargetPlatform.WINDOWS) listOf("ping", "-n", "600", "127.0.0.1") else listOf("sleep", "600")
        assertTrue(client.start(run, RunSpec(cmd)).isSuccess)
        RealTime.await(60.seconds, "запуск на target") { client.status(run).valueOrNull()?.takeIf { it.state == RunState.RUNNING } }
        val stopped = client.stop(run, 2.seconds).valueOrNull()!!
        assertEquals(RunState.EXITED, stopped.run!!.state)
        assertEquals(emptyList(), stopped.stop!!.survivors)
    }

    private fun platform() = if (File.separatorChar == '\\') TargetPlatform.WINDOWS else TargetPlatform.POSIX

    private fun hangCommand(): List<String> {
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), exe).path
        return listOf(java, "-cp", System.getProperty("java.class.path"), Hang::class.java.name)
    }
}

/** Долгий процесс для тестов: строка `ready` и ожидание без конца. */
object Hang {
    @JvmStatic
    fun main(args: Array<String>) {
        println("ready")
        CountDownLatch(1).await()
    }
}
