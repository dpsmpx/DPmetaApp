package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunEventSource
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.testkit.RealTime
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Агент на target с настоящими процессами (критерии этапа 3): запуск и
 * полная остановка дерева, тайм-аут и обрыв связи без сирот, потерянный
 * супервизор, отдача пропущенного после переподключения (ТЗ, §56, §119).
 */
class AgentRuntimeTest {
    @TempDir
    lateinit var dir: Path

    private val ids = IdGenerator(Clock.SYSTEM, RandomSource.seeded(11))
    private val started = mutableListOf<Long>()

    private fun runtime() = AgentRuntime(dir.toString(), Clock.SYSTEM, "test", TestChild.selfCommand())

    private fun source(rt: AgentRuntime) = RunEventSource { run, after, limit -> rt.events(run, after, limit).toOutcome() }

    private fun outputs(events: List<EventEnvelope>) =
        events.filter { it.type == EventType.TARGET_PROCESS_OUTPUT }.map { (it.payload["text"] as JsonString).value }

    /** Запуск `spawn-hang`: идентификатор внука из вывода. */
    private fun startTree(rt: AgentRuntime, run: SessionId, timeoutSeconds: Long? = null): Pair<RunChannel, Long> {
        val reply = rt.start(run, RunSpec(TestChild.command("child", "spawn-hang"), timeout = timeoutSeconds?.seconds))
        assertTrue(reply.ok, reply.diagnostics.toString())
        val channel = RunChannel(source(rt), run)
        val seen = mutableListOf<EventEnvelope>()
        val grandchild = RealTime.await(60.seconds, "внук запущен") {
            seen += channel.poll().valueOrNull()!!.events
            outputs(seen).firstOrNull { it.startsWith("grandchild ") }?.removePrefix("grandchild ")?.toLong()
        }
        started += grandchild
        return channel to grandchild
    }

    @AfterTest
    fun cleanup() {
        // Тест, упавший посередине, не оставляет процессов на машине.
        started.forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
    }

    private fun alive(pid: Long) = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    @Test
    fun startStreamAndStopWholeTree() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        val (channel, grandchild) = startTree(rt, run)
        val pid = rt.status(run).run!!.pid!!
        started += pid
        val stop = rt.stop(run, 2.seconds)
        assertTrue(stop.ok, stop.diagnostics.toString())
        assertEquals(emptyList(), stop.stop!!.survivors)
        assertFalse(alive(pid), "процесс жив")
        assertFalse(alive(grandchild), "внук жив")
        val tail = channel.poll().valueOrNull()!!.events
        val exit = tail.last { it.type == EventType.TARGET_PROCESS_EXITED }
        assertEquals(JsonString(ExitReason.STOPPED.name), exit.payload["reason"])
        assertEquals(RunState.EXITED, rt.status(run).run!!.state)
    }

    @Test
    fun timeoutLeavesNoOrphans() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        val (_, grandchild) = startTree(rt, run, timeoutSeconds = 2)
        val status = RealTime.await(60.seconds, "тайм-аут сработал") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        assertEquals(ExitReason.TIMEOUT, status.reason)
        RealTime.await(10.seconds, "внук остановлен") { if (alive(grandchild)) null else Unit }
    }

    @Test
    fun connectionDropLosesNothing() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        var connected = true
        val flaky = RunEventSource { r, after, limit ->
            if (connected) rt.events(r, after, limit).toOutcome() else Outcome.failure(DiagCode.SSH_CONNECTION_FAILED, "обрыв")
        }
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "spawn-hang"))).ok)
        val channel = RunChannel(flaky, run)
        val got = mutableListOf<EventEnvelope>()
        RealTime.await(60.seconds, "первые события") { got += channel.poll().valueOrNull()!!.events; got.takeIf { it.isNotEmpty() } }
        connected = false
        assertTrue(channel.poll() is Outcome.Failure)
        // Пока связи нет, запуск работает: внук запущен и пишет вывод в журнал агента.
        val grandchild = RealTime.await(60.seconds, "внук запущен без связи") {
            rt.events(run, 0, 1000).events.let(::outputs).firstOrNull { it.startsWith("grandchild ") }?.removePrefix("grandchild ")?.toLong()
        }
        started += grandchild
        connected = true
        RealTime.await(30.seconds, "пропущенное получено") {
            got += channel.poll().valueOrNull()!!.events
            got.takeIf { outputs(it).any { o -> o.startsWith("grandchild ") } }
        }
        assertEquals((1L..got.size).toList(), got.map { it.sequence }, "номера подряд, без пропусков и повторов")
        assertTrue(rt.stop(run, 2.seconds).ok)
        assertFalse(alive(grandchild))
    }

    @Test
    fun lostSupervisorIsDetectedAndStopKillsTheTree() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        val (_, grandchild) = startTree(rt, run)
        val doc = RunDoc.read(DocumentStore(NioVfs(dir), "runs/${run.value}").read(AgentRuntime.RUN_KEY).valueOrNull()!!.content)!!
        val pid = doc.pid!!
        started += pid
        ProcessHandle.of(doc.supervisorPid!!).ifPresent { it.destroyForcibly().let { _ -> it.onExit().get() } }
        assertEquals(RunState.LOST, rt.status(run).run!!.state)
        assertTrue(alive(pid), "без супервизора процесс ещё жив — его остановит stop")
        val stop = rt.stop(run, 2.seconds)
        assertTrue(stop.ok, stop.diagnostics.toString())
        assertFalse(alive(pid))
        assertFalse(alive(grandchild))
        assertEquals(RunState.EXITED, rt.status(run).run!!.state)
    }

    /** Вывод Claude проходит `Redactor` до журнала: ключ в выводе не попадает на диск (D-14, T8; критерий этапа 4). */
    @Test
    fun secretInOutputNeverReachesDisk() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "secret"))).ok)
        val status = RealTime.await(60.seconds, "процесс вышел") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        assertEquals(0, status.exitCode)
        val texts = outputs(rt.events(run, 0, 1000).events)
        assertTrue(texts.any { "<redacted>" in it }, "строка с ключом есть, но ключ скрыт: $texts")
        val onDisk = dir.toFile().walkTopDown().filter { it.isFile }.joinToString(" ") { it.readText(Charsets.UTF_8) }
        assertFalse(TestChild.FAKE_KEY in onDisk, "ключ попал на диск")
    }

    @Test
    fun nextTurnContinuesTheSameJournal() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "secret"))).ok)
        RealTime.await(60.seconds, "первый ход") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        val firstLast = rt.status(run).run!!.lastSequence
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "secret"))).ok)
        val second = RealTime.await(60.seconds, "второй ход") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED && it.turn == 2 } }
        assertTrue(second.lastSequence > firstLast)
        val events = rt.events(run, 0, 1000).events
        assertEquals((1L..events.size).toList(), events.map { it.sequence }, "номера подряд через ходы")
        assertEquals(2, events.count { it.type == EventType.TARGET_PROCESS_STARTED })
    }
}
