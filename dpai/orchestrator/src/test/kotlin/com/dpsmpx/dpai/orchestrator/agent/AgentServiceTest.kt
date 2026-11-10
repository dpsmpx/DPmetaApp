package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunEventSource
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.ServiceStatus
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/** Служба агента на ПК (D-109): просьбы о супервизоре, отказ к прежнему пути, состояние, установка. */
class AgentServiceTest {
    @TempDir
    lateinit var dir: Path

    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(19))
    private val stop = AtomicBoolean(false)
    private val threads = mutableListOf<Thread>()

    @AfterTest
    fun cleanup() {
        stop.set(true)
        threads.forEach { it.join(5_000) }
    }

    private fun run(): SessionId = ids.next(IdKinds.SESSION)

    private val me: ProcessMark get() = ProcessHandle.current().let { ProcessMark(it.pid(), com.dpsmpx.dpai.execution.process.ProcessTree.startedAtMillis(it), null) }

    private fun serve(service: AgentService, launch: (SessionId) -> Outcome<Unit>) {
        threads += thread(isDaemon = true) { service.serve(me, launch) { stop.get() } }
    }

    private fun service(clock: Clock = Clock.SYSTEM) = AgentService(dir.toString(), clock, "test", beatEvery = 200.milliseconds, pollEvery = 20.milliseconds)

    @Test
    fun runningServiceTakesTheRequestAndAnswers() {
        val launched = mutableListOf<SessionId>()
        val s = service()
        serve(s) { id -> synchronized(launched) { launched += id }; Outcome.success(Unit) }
        val id = run()
        val asked = s.ask(id, running = true, take = 5.seconds)
        assertEquals(Outcome.success(Unit), (asked as AgentService.Asked.Answered).result)
        assertEquals(listOf(id), synchronized(launched) { launched.toList() })
        assertTrue(File(dir.toFile(), "service/requests").list().orEmpty().isEmpty(), "просьбы не остались")
        assertTrue(File(dir.toFile(), "service/answers").list().orEmpty().isEmpty(), "ответ прочитан и убран")
    }

    @Test
    fun noServiceMeansStartItself() {
        val s = service()
        assertEquals(AgentService.Asked.Nobody, s.ask(run(), running = false))
        // Признак есть, а служба просьбу не берёт: просьба отзывается, двойного запуска нет.
        val id = run()
        assertEquals(AgentService.Asked.Nobody, s.ask(id, running = true, take = 200.milliseconds))
        assertTrue(File(dir.toFile(), "service/requests").list().orEmpty().none { id.value in it }, "просьба отозвана")
    }

    @Test
    fun serviceFailureIsTheStartFailure() {
        val s = service()
        serve(s) { Outcome.failure(DiagCode.PROCESS_START_FAILED, "нет java", "run") }
        val asked = s.ask(run(), running = true, take = 5.seconds)
        val r = assertIs<Outcome.Failure>((asked as AgentService.Asked.Answered).result)
        assertEquals("нет java", r.errors.single().message)
    }

    @Test
    fun statusFromHeartbeat() {
        val clock = ManualClock(Timestamp(1_800_000_000_000))
        val s = AgentService(dir.toString(), clock, "1.2.3", beatEvery = 5.seconds, pollEvery = 20.milliseconds)
        assertEquals(ServiceStatus.ABSENT, s.status { true }.state)
        val stopOnce = AtomicBoolean(false)
        s.serve(me, { Outcome.success(Unit) }) { stopOnce.getAndSet(true) }
        val up = s.status { true }
        assertEquals(ServiceStatus.RUNNING, up.state)
        assertEquals("1.2.3", up.version)
        assertEquals(ProcessHandle.current().pid(), up.pid)
        assertEquals(ServiceStatus.SILENT, s.status { false }.state, "процесса нет — молчит")
        clock.advance(16.seconds)
        assertEquals(ServiceStatus.SILENT, s.status { true }.state, "три признака пропущено — молчит")
        // Ответ `service` — тем же протоколом.
        val reply = AgentReply("service", ok = true, service = up)
        assertEquals(up, AgentReply.parse(CanonicalJson.write(reply.toJson())).valueOrNull()!!.service)
    }

    /** Служба умерла, взяв просьбу: новая служба отвечает на неё отказом, а не молчанием. */
    @Test
    fun takenByDeadServiceIsAnsweredOnRestart() {
        val id = run()
        File(dir.toFile(), "service/taken").mkdirs()
        File(dir.toFile(), "service/taken/${id.value}").writeText("{}")
        val s = service()
        val stopOnce = AtomicBoolean(false)
        s.serve(me, { Outcome.success(Unit) }) { stopOnce.getAndSet(true) }
        val answer = File(dir.toFile(), "service/answers/${id.value}").readText()
        assertTrue("\"ok\":false" in answer, answer)
    }

    /**
     * Ход через службу на настоящих процессах: супервизор запускает служба (а не `start`),
     * ход идёт и кончается, состояние службы — ответом `service`.
     */
    @Test
    fun turnStartedThroughTheServiceRuns() {
        val home = dir.toString()
        val sup = ProcessSupervision(home, TestChild.selfCommand(), com.dpsmpx.dpai.execution.process.Detacher(), Clock.SYSTEM, AgentService(home, Clock.SYSTEM, "test"))
        val rt = AgentRuntime(home, Clock.SYSTEM, "test", TestChild.selfCommand(), supervision = sup)
        val viaService = mutableListOf<SessionId>()
        serve(AgentService(home, Clock.SYSTEM, "test", pollEvery = 20.milliseconds)) { id ->
            synchronized(viaService) { viaService += id }
            sup.spawn(id)
        }
        val status = waitFor(15.seconds) { rt.serviceStatus().service?.takeIf { it.running } }
        assertEquals(ServiceStatus.RUNNING, status.state)
        val id = run()
        val reply = rt.start(id, RunSpec(TestChild.command("child", "auth-status")))
        assertTrue(reply.ok, reply.diagnostics.toString())
        assertEquals(listOf(id), synchronized(viaService) { viaService.toList() }, "супервизор запустила служба")
        val channel = RunChannel(RunEventSource { r, after, limit -> rt.events(r, after, limit).toOutcome() }, id)
        val done = waitFor(60.seconds) { channel.poll().valueOrNull()?.status?.takeIf { st -> st.state.name == "EXITED" } }
        assertEquals(0, done.exitCode)
    }

    private fun <T : Any> waitFor(limit: Duration, what: () -> T?): T = com.dpsmpx.dpai.testkit.RealTime.await(limit, "служба агента", probe = what)

    /** Хост без процессов: записывает команды, отвечает кодом по правилу. */
    private class Recorder(private val code: (List<String>) -> Int) : ProcessHost {
        val calls = mutableListOf<List<String>>()

        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            calls += spec.command
            val c = code(spec.command)
            return Outcome.success(object : RunningProcess {
                override val pid: Long? = null
                override val isAlive: Boolean = false

                override fun awaitExit(timeout: Duration): Int = c

                override fun stop(grace: Duration): StopReport = StopReport(c, false, emptyList())
            })
        }
    }

    @Test
    fun unitKeepsTurnsAndQuotesArguments() {
        val unit = AgentServiceSetup.unit(listOf("/opt/java 21/bin/java", "-cp", "/a/lib/*:/b%x", "com.dpsmpx.dpai.cli.MainKt"), "/home/u/.dpai-agent")
        assertTrue("KillMode=process" in unit, "остановка службы не убивает ходы")
        assertTrue("Restart=on-failure" in unit)
        assertTrue("ExecStart=\"/opt/java 21/bin/java\" -cp /a/lib/*:/b%%x com.dpsmpx.dpai.cli.MainKt agent serve --home /home/u/.dpai-agent" in unit, unit)
        assertEquals("\"a\\\\b \\\"c\\\"\"", AgentServiceSetup.systemdArg("a\\b \"c\""))
        assertEquals("\$\$HOME", AgentServiceSetup.systemdArg("\$HOME"))
        val args = AgentServiceSetup.argFile(listOf("-cp", "C:\\Users\\u\\dpai\\lib\\*", "agent"))
        assertEquals("\"-cp\"\r\n\"C:/Users/u/dpai/lib/*\"\r\n\"agent\"\r\n", args)
        val ps = AgentServiceSetup.registerScript("C:\\Program Files\\Java\\bin\\javaw.exe", "C:\\Users\\u'x\\.dpai-agent\\service.args", "C:\\Users\\u'x\\.dpai-agent")
        assertTrue("-ExecutionTimeLimit ([TimeSpan]::Zero)" in ps, "без предела времени: иначе задача встанет через 3 суток")
        assertTrue("'\"@C:/Users/u''x/.dpai-agent/service.args\"'" in ps, ps)
        assertTrue("-AtLogOn" in ps)
    }

    @Test
    fun installOnLinuxWritesUnitEnablesAndWarnsWithoutLinger() {
        val cfg = dir.resolve("cfg").toString()
        val host = Recorder { argv -> if (argv.first() == "loginctl") 1 else 0 }
        val setup = AgentServiceSetup(dir.resolve("home").toString(), listOf("/usr/bin/java", "-cp", "x", "Main"), host, windows = false, env = mapOf("XDG_CONFIG_HOME" to cfg, "USER" to "u"))
        val done = setup.install().valueOrNull()!!
        assertEquals(
            listOf(listOf("systemctl", "--user", "daemon-reload"), listOf("systemctl", "--user", "enable", "--now", "dpai-agent.service"), listOf("loginctl", "enable-linger", "u")),
            host.calls,
        )
        assertTrue(File(cfg, "systemd/user/dpai-agent.service").readText().contains("agent serve"))
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, done.warnings.single().code)
        val removed = AgentServiceSetup(dir.resolve("home").toString(), listOf("java"), Recorder { 0 }, windows = false, env = mapOf("XDG_CONFIG_HOME" to cfg)).remove()
        assertTrue(removed is Outcome.Success)
        assertTrue(!File(cfg, "systemd/user/dpai-agent.service").exists())
        val failing = AgentServiceSetup(dir.resolve("home").toString(), listOf("java"), Recorder { argv -> if ("enable" in argv) 1 else 0 }, windows = false, env = mapOf("XDG_CONFIG_HOME" to cfg))
        assertEquals(DiagCode.PROCESS_EXIT_NONZERO, assertIs<Outcome.Failure>(failing.install()).errors.single().code)
        val termux = AgentServiceSetup(dir.resolve("home").toString(), listOf("java"), Recorder { 0 }, windows = false, env = mapOf("PREFIX" to "/data/data/com.termux/files/usr"))
        assertEquals(DiagCode.UNSUPPORTED, assertIs<Outcome.Failure>(termux.install()).errors.single().code)
    }

    @Test
    fun installOnWindowsRegistersALogonTaskWithArgFile() {
        val home = dir.resolve("home").toFile().apply { mkdirs() }
        val host = Recorder { 0 }
        val setup = AgentServiceSetup(home.path, listOf("C:\\Java\\bin\\java.exe", "-cp", "C:\\dpai\\lib\\*", "com.dpsmpx.dpai.cli.MainKt"), host, windows = true, env = emptyMap())
        assertTrue(setup.install() is Outcome.Success)
        val call = host.calls.single()
        assertEquals("powershell.exe", call.first())
        assertTrue("javaw.exe" in call.last() && "Register-ScheduledTask -TaskName 'dpai-agent'" in call.last(), call.last())
        val args = File(home, "service.args").readText()
        assertTrue("\"serve\"" in args && "\"C:/dpai/lib/*\"" in args, args)
    }
}
