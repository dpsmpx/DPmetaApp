package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.AgentClient
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.testkit.RealTime
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Агент в процессе приложения (D-75, шаг 4): те же вызовы `AgentClient`,
 * что по SSH, — без SSH и без отдельного процесса супервизора: супервизор
 * хода — поток ([ThreadSupervision]), опознание — по `/proc`. Процессы хода
 * здесь — обычные процессы JVM (`LocalProcessHost`): Alpine под proot и JNI
 * проверяются на телефоне (`docs/DEVICE_CHECK.md`) и `JniProcessesTest`.
 */
class InAppAgentHostTest {
    @TempDir
    lateinit var dir: Path

    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(17))
    private val started = mutableListOf<Long>()

    /** `kill` — через `ProcessHandle`: нужен только остановке сеанса без супервизора. */
    private val native = object : NativeProcesses {
        override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> =
            Outcome.failure(DiagCode.UNSUPPORTED, "в тесте процессы хода — LocalProcessHost")

        override fun kill(pid: Int, signal: Int): Boolean =
            ProcessHandle.of(kotlin.math.abs(pid).toLong()).map { if (signal == 9) it.destroyForcibly() else it.destroy() }.orElse(false)
    }

    private val home: String get() = dir.resolve("agent").toString()

    private fun host() = InAppAgentHost.create(home, Clock.SYSTEM, "test-in-app", native, LocalProcessHost())

    private fun client(host: InAppAgentHost) = AgentClient(host, listOf("dpai"), home = null, subject = "target в приложении")

    @Test
    fun profilesAndFenceWorkThroughInAppClientAndForbidDelayedStart() {
        val client = client(host())
        assertIs<Outcome.Success<Unit>>(client.profiles())
        val run = ids.next(IdKinds.SESSION)
        assertIs<Outcome.Success<AgentReply>>(client.fenceStop(run))
        assertIs<Outcome.Failure>(client.start(run, RunSpec(listOf("unused"))))
    }


    /** stdout хода; stderr JVM-потомка несёт «Picked up JAVA_TOOL_OPTIONS» окружения. */
    private fun outputs(events: List<EventEnvelope>) =
        events.filter { it.type == EventType.TARGET_PROCESS_OUTPUT && (it.payload["stream"] as JsonString).value == "stdout" }
            .map { (it.payload["text"] as JsonString).value }

    @BeforeTest
    fun linuxOnly() {
        // Опознание супервизора-потока — по `/proc/self/stat`: только Linux (как на Android).
        assumeTrue(File("/proc/self/stat").isFile, "нужен /proc")
    }

    @AfterTest
    fun cleanup() {
        started.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    @Test
    fun doctorAnswersFromTheAppProcess() {
        val report = assertIs<Outcome.Success<com.dpsmpx.dpai.execution.agent.DoctorReport>>(client(host()).doctor()).value
        assertEquals("test-in-app", report.agentVersion)
        assertTrue(File(home).isDirectory, "каталог агента создан")
    }

    @Test
    fun runStreamsOutputAndEndsLikeOverSsh() {
        val agent = client(host())
        val run = ids.next(IdKinds.SESSION)
        val status = assertIs<Outcome.Success<com.dpsmpx.dpai.execution.agent.RunStatus>>(agent.start(run, RunSpec(TestChild.command("child", "wide", "3", "10")))).value
        assertEquals(1, status.turn)
        val done = RealTime.await(60.seconds, "ход кончился") {
            agent.status(run).valueOrNull()?.takeIf { it.state == RunState.EXITED }
        }
        assertEquals(ExitReason.EXITED, done.reason)
        assertEquals(0, done.exitCode)
        val events = agent.events(run, 0, 100).valueOrNull()!!.events
        assertEquals(3, outputs(events).size, outputs(events).toString())
        assertTrue(events.first().type == EventType.TARGET_PROCESS_STARTED && events.last().type == EventType.TARGET_PROCESS_EXITED)

        // Поток `follow` (D-76): те же события строками, конец — после выхода хода.
        val replies = CopyOnWriteArrayList<AgentReply>()
        val follow = assertIs<Outcome.Success<com.dpsmpx.dpai.execution.process.RunningProcess>>(agent.follow(run, 0) { r -> r.valueOrNull()?.let(replies::add) }).value
        assertEquals(0, follow.awaitExit(30.seconds), "поток кончился сам")
        assertEquals(events.map { it.sequence }, replies.flatMap { it.events }.map { it.sequence })
    }

    @Test
    fun stopReachesTheSupervisorThreadAndTheProcess() {
        val agent = client(host())
        val run = ids.next(IdKinds.SESSION)
        assertTrue(agent.start(run, RunSpec(TestChild.command("child", "hang"))) is Outcome.Success)
        RealTime.await(60.seconds, "потомок готов") {
            agent.events(run, 0, 100).valueOrNull()?.events?.let(::outputs)?.takeIf { "ready" in it }
        }
        val pid = agent.status(run).valueOrNull()!!.pid!!
        started += pid
        val stop = agent.stop(run, 2.seconds)
        assertTrue(stop is Outcome.Success, stop.diagnostics.toString())
        assertEquals(emptyList(), stop.valueOrNull()!!.stop!!.survivors)
        val exited = agent.status(run).valueOrNull()!!
        assertEquals(RunState.EXITED, exited.state)
        assertEquals(ExitReason.STOPPED, exited.reason)
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "процесс хода остановлен")
    }

    @Test
    fun onlyAgentCallsOfTheAppAreAccepted() {
        val host = host()
        val run = ids.next(IdKinds.SESSION).value
        val supervise = host.start(ProcessSpec(listOf("dpai", "agent", "supervise", "--run", run)))
        assertEquals(DiagCode.UNSUPPORTED, (supervise as Outcome.Failure).errors.single().code, "супервизор — поток агента, снаружи не запускается")
        val other = host.start(ProcessSpec(listOf("dpai", "agent", "status", "--home", dir.resolve("other").toString(), "--run", run)))
        assertEquals(DiagCode.VALUE_INVALID, (other as Outcome.Failure).errors.single().code, "чужой каталог агента")
        assertEquals(DiagCode.VALUE_INVALID, (host.start(ProcessSpec(listOf("sh", "-c", "true"))) as Outcome.Failure).errors.single().code)
        val noRun = client(host).status(IdKinds.SESSION.let { ids.next(it) })
        assertTrue(noRun is Outcome.Failure, "запуска нет — отказ агента, а не исключение")
    }
}
