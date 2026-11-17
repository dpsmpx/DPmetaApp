package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.FollowingEventSource
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

    /** Служба агента (D-109): `service status` — ответ `service` тем же протоколом; без подкоманды — подсказка. */
    @Test
    fun serviceStatusAnswersWithTheSameProtocol() {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val cmd = AgentCommand(out = { out += it }, err = { err += it }, version = "t")
        assertEquals(Cli.OK, cmd.run(listOf("service", "status", "--home", dir.toString())))
        val reply = AgentReply.parse(out.single()).valueOrNull()!!
        assertEquals("service", reply.command)
        assertEquals("absent", reply.service!!.state)
        assertTrue(err.any { "служба: absent" in it }, err.toString())
        assertEquals(Cli.USAGE_ERROR, AgentCommand(out = {}, err = {}, version = "t").run(listOf("service")))
        assertEquals(Cli.USAGE_ERROR, AgentCommand(out = {}, err = {}, version = "t").run(listOf("service", "status", "лишнее")))
    }

    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(5))

    @Test
    fun manualProfileCapabilityAndFenceUseTheSameAgentProtocol() {
        val out = mutableListOf<String>()
        val cmd = AgentCommand(out = { out += it }, err = {}, version = "t")
        assertEquals(Cli.OK, cmd.run(listOf("profiles", "--home", dir.toString())))
        assertEquals("profiles", AgentReply.parse(out.last()).valueOrNull()!!.command)
        val run = ids.next(IdKinds.SESSION)
        assertEquals(Cli.OK, cmd.run(listOf("fence-stop", "--run", run.value, "--home", dir.toString())))
        val reply = AgentReply.parse(out.last()).valueOrNull()!!
        assertEquals(run, reply.run!!.id)
        assertEquals(RunState.EXITED, reply.run!!.state)
        assertTrue(reply.stop!!.survivors.isEmpty())
    }

    @Test
    fun usageErrorsAreCodeTwo() {
        val err = mutableListOf<String>()
        val cli = Cli(out = {}, err = { err += it }, version = "t")
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "status")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "start", "--run", "ses_bad")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "nope")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "follow")))
        assertEquals(Cli.USAGE_ERROR, cli.run(listOf("agent", "follow", "--run", ids.next(IdKinds.SESSION).value, "--after", "x")))
    }

    /** D-50: `dpai agent mcp` — сервер дерева строками JSON-RPC, пока открыт вход; без --root или с чужой оболочкой — неверный вызов. */
    @Test
    fun mcpServesTheTreeOverStdio() {
        val tree = dir.resolve("tree").toFile().apply { mkdirs() }
        File(tree, "a.txt").writeText("строка\n")
        val out = mutableListOf<String>()
        val input = listOf(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read","arguments":{"path":"a.txt"}}}""",
        ).joinToString("\n")
        val cmd = AgentCommand(out = { out += it }, err = {}, version = "t", input = { input.reader().buffered() })
        assertEquals(Cli.OK, cmd.run(listOf("mcp", "--root", tree.path)))
        assertEquals(2, out.size)
        assertTrue("строка" in out[1], out[1])
        assertEquals(Cli.USAGE_ERROR, cmd.run(listOf("mcp")))
        assertEquals(Cli.USAGE_ERROR, cmd.run(listOf("mcp", "--root", tree.path, "--shell", "fish")))
        assertEquals(AgentCommand.FAILED, cmd.run(listOf("mcp", "--root", dir.resolve("нет").toString())))
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
        val stopReply = client.stop(run, 2.seconds)
        assertTrue(stopReply.isSuccess, stopReply.diagnostics.toString())
        val stopped = stopReply.valueOrNull()!!
        assertEquals(RunState.EXITED, stopped.run!!.state)
        assertEquals(emptyList(), stopped.stop!!.survivors)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
        assertEquals(CheckStatus.OK, manager.doctor(target.id).valueOrNull()!!.checks.first { it.name == "связь" }.status)
    }

    /**
     * Поток хода через настоящий `dpai agent follow` (D-76): события идущего
     * хода канал получает из потока, а не опросом; поток кончается с ходом.
     */
    @Test
    fun localTargetStreamsTheTurnThroughFollow() {
        val manager = ExecutionTargetManager(Clock.SYSTEM)
        val target = ExecutionTarget(
            ids.next(IdKinds.TARGET), TargetType.LOCAL, "эта машина", platform(), null,
            AgentCommand.selfCommand(), agentHome = dir.toString(),
        )
        manager.register(target)
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        assertTrue(client.start(run, RunSpec(hangCommand())).isSuccess)
        val source = FollowingEventSource(client, Clock.SYSTEM)
        val channel = RunChannel(source, run)
        val seen = mutableListOf<com.dpsmpx.dpai.core.events.EventEnvelope>()
        RealTime.await(60.seconds, "вывод хода пришёл потоком") {
            seen += channel.poll().valueOrNull()!!.events
            seen.firstOrNull { it.type == EventType.TARGET_PROCESS_OUTPUT && it.payload["text"] == JsonString("ready") }?.takeIf { source.served > 0 }
        }
        assertEquals(1, source.started)
        val stopReply = client.stop(run, 2.seconds)
        assertTrue(stopReply.isSuccess, stopReply.diagnostics.toString())
        assertEquals(RunState.EXITED, stopReply.valueOrNull()!!.run!!.state)
        RealTime.await(60.seconds, "конец хода получен") {
            seen += channel.poll().valueOrNull()!!.events
            seen.lastOrNull()?.takeIf { it.type == EventType.TARGET_PROCESS_EXITED }
        }
        assertEquals((1L..seen.size).toList(), seen.map { it.sequence }, "номера подряд, без пропусков и повторов")
        RealTime.await(30.seconds, "поток кончился вместе с ходом") { channel.poll(); if (source.streaming == 0) Unit else null }
        source.close()
    }

    /** Ревью A-18: относительный --home — супервизор находит запуск и берёт ход, а не ищет его в home/home. */
    @Test
    fun relativeHomeIsResolvedForSupervisor() {
        val rel = Path.of("").toAbsolutePath().relativize(dir).toString()
        val manager = ExecutionTargetManager(Clock.SYSTEM)
        val target = ExecutionTarget(
            ids.next(IdKinds.TARGET), TargetType.LOCAL, "эта машина", platform(), null,
            AgentCommand.selfCommand(), agentHome = rel,
        )
        manager.register(target)
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        assertTrue(client.start(run, RunSpec(hangCommand())).isSuccess)
        RealTime.await(60.seconds, "супервизор взял ход") { client.status(run).valueOrNull()?.takeIf { it.state == RunState.RUNNING } }
        val stopReply = client.stop(run, 2.seconds)
        assertTrue(stopReply.isSuccess, stopReply.diagnostics.toString())
        assertEquals(RunState.EXITED, stopReply.valueOrNull()!!.run!!.state)
    }

    /**
     * Настоящий target по SSH. Задаётся системным свойством
     * `dpai.test.ssh` = `пользователь@хост:порт:posix|windows:команда агента`,
     * например `dev@build:22:posix:/opt/dpai/bin/dpai`. На target установлен
     * `dpai`, ключ хоста — в known_hosts, вход — ключом; необязательные
     * `dpai.test.ssh.identity` и `dpai.test.ssh.knownHosts` — свой ключ и
     * свой файл известных хостов.
     */
    @Test
    fun sshTargetStartsAndStopsThroughUnifiedInterface() {
        val spec = System.getProperty("dpai.test.ssh")
        assumeTrue(spec != null, "dpai.test.ssh не задан: проверка SSH — на настоящем target")
        val (userHost, port, platform, agent) = spec!!.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            identityFile = System.getProperty("dpai.test.ssh.identity"), knownHostsFile = System.getProperty("dpai.test.ssh.knownHosts"),
        ).valueOrNull()!!
        val p = if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX
        val type = if (p == TargetPlatform.WINDOWS) TargetType.WINDOWS_REMOTE else TargetType.LINUX_SSH
        val manager = ExecutionTargetManager(Clock.SYSTEM, local = LocalProcessHost())
        val target = ExecutionTarget(ids.next(IdKinds.TARGET), type, "ssh", p, endpoint, listOf(agent))
        manager.register(target)
        val link = manager.doctor(target.id).valueOrNull()!!.checks.first { it.name == "связь" }
        assertEquals(CheckStatus.OK, link.status, link.detail)
        val client = manager.client(target.id).valueOrNull()!!
        val run = ids.next(IdKinds.SESSION)
        val cmd = if (p == TargetPlatform.WINDOWS) listOf("ping", "-n", "600", "127.0.0.1") else listOf("sleep", "600")
        assertTrue(client.start(run, RunSpec(cmd)).isSuccess)
        RealTime.await(60.seconds, "запуск на target") { client.status(run).valueOrNull()?.takeIf { it.state == RunState.RUNNING } }
        // Поток хода по долгому каналу exec клиента OpenSSH (D-76).
        val source = FollowingEventSource(client, Clock.SYSTEM)
        val channel = RunChannel(source, run)
        RealTime.await(60.seconds, "строка потока по SSH") { channel.poll().valueOrNull(); if (source.served > 0) Unit else null }
        val stopReply = client.stop(run, 2.seconds)
        assertTrue(stopReply.isSuccess, stopReply.diagnostics.toString())
        val stopped = stopReply.valueOrNull()!!
        assertEquals(RunState.EXITED, stopped.run!!.state)
        assertEquals(emptyList(), stopped.stop!!.survivors)
        RealTime.await(60.seconds, "поток кончился с ходом") { channel.poll(); if (source.streaming == 0) Unit else null }
        source.close()
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
