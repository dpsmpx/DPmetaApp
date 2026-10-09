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
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunChannel
import com.dpsmpx.dpai.execution.agent.RunEventSource
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpai.testkit.RealTime
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
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
        // Супервизор пишет EXITED и только потом выходит; пока он жив, Windows не даст удалить его журнал.
        val runs = dir.resolve("runs").toFile().listFiles().orEmpty()
        for (run in runs) {
            val doc = DocumentStore(NioVfs(dir), "runs/${run.name}").read(AgentRuntime.RUN_KEY).valueOrNull()?.let { RunDoc.read(it.content) } ?: continue
            doc.supervisorPid?.let { pid -> ProcessHandle.of(pid).ifPresent { h -> runCatching { h.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS) } } }
        }
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

    /** Аудит 2026-10-07, A-4: каталог агента закрыт для других пользователей (на POSIX). */
    @Test
    fun agentHomeIsOwnerOnly() {
        val home = dir.resolve("home")
        AgentRuntime(home.toString(), Clock.SYSTEM, "test", TestChild.selfCommand())
        val posix = java.nio.file.Files.getFileAttributeView(home, java.nio.file.attribute.PosixFileAttributeView::class.java)
        org.junit.jupiter.api.Assumptions.assumeTrue(posix != null, "POSIX-прав нет (Windows): каталог в профиле пользователя")
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(posix!!.readAttributes().permissions()))
    }

    /**
     * Ревью 8 октября 2026, A-20: `doctor` — доступная память, а не `MemFree`.
     * Числа — как на Redmi Note 13 (8 октября 2026): свободно 159 МиБ из 7601,
     * доступно ~3,3 ГиБ.
     */
    @Test
    fun doctorMemoryIsAvailableNotMemFree() {
        val meminfo = """
            MemTotal:        7783712 kB
            MemFree:          163104 kB
            MemAvailable:    3460344 kB
            Active(anon):    1520332 kB
            HugePages_Total:       0
        """.trimIndent()
        val free = 163104L * 1024
        val total = 7783712L * 1024
        assertEquals(3460344L * 1024, AgentRuntime.availableMemory(meminfo, free, total))
        // Предел контейнера ниже памяти машины: не больше свободного под пределом.
        assertEquals(512L shl 20, AgentRuntime.availableMemory(meminfo, 512L shl 20, 2L shl 30))
        // Без MemAvailable (ядро до 3.14) и без /proc/meminfo (Windows) — значение JDK.
        assertEquals(free, AgentRuntime.availableMemory("MemTotal: 7783712 kB\nMemFree: 163104 kB", free, total))
        assertEquals(free, AgentRuntime.availableMemory(null, free, total))
        assertNull(AgentRuntime.availableMemory(null, null, null))
    }

    /**
     * Ревью 8 октября 2026: ответ агента — одна строка не длиннее
     * `LocalProcessHost.MAX_LINE`. Широкий вывод делится на страницы по объёму,
     * а слишком длинная строка вывода — обрезается с пометкой; канал
     * дочитывает всё, а не застревает на обрезанном ответе.
     */
    @Test
    fun widePagesStayUnderLineLimitAndChannelReadsAll() {
        val rt = runtime()
        /** Все страницы запуска; каждая — строка короче предела чтения. */
        fun pages(run: SessionId): List<List<EventEnvelope>> {
            val out = mutableListOf<List<EventEnvelope>>()
            var after = 0L
            while (true) {
                val p = rt.events(run, after, 500)
                assertTrue(CanonicalJson.write(p.toJson()).length < LocalProcessHost.MAX_LINE, "ответ длиннее строки")
                if (p.events.isEmpty()) return out
                out += p.events
                after = p.events.last().sequence
            }
        }
        val run = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "wide", "12", "200000"))).ok)
        RealTime.await(60.seconds, "процесс вышел") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        val wideRun = pages(run)
        assertTrue(wideRun.size > 1, "страницы — по объёму, а не только по числу")
        assertEquals(12, outputs(wideRun.flatten()).count { it.startsWith("") && it.length > 100_000 })
        val big = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(big, RunSpec(TestChild.command("child", "wide", "1", "600000"))).ok)
        RealTime.await(60.seconds, "процесс вышел") { rt.status(big).run!!.takeIf { it.state == RunState.EXITED } }
        val wide = pages(big).flatten().single { it.type == EventType.TARGET_PROCESS_OUTPUT && (it.payload["text"] as JsonString).value.startsWith("0:") }
        assertEquals(AgentRuntime.MAX_EVENT_TEXT, (wide.payload["text"] as JsonString).value.length)
        assertEquals(JsonBool(true), wide.payload["truncated"])
        val channel = RunChannel(source(rt), run)
        val all = mutableListOf<EventEnvelope>()
        repeat(20) { channel.poll().valueOrNull()?.let { all += it.events } }
        assertEquals(12, outputs(all).count { it.length > 100_000 }, "канал дочитал все строки")
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

    /** Вход хода (prompt) доходит до процесса и после запуска на target не хранится (аудит 2026-10-07, A-5). */
    @Test
    fun turnInputIsDeliveredThenDeleted() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        val prompt = "сделай задачу: секретный план"
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "stdin-length"), stdin = prompt)).ok)
        RealTime.await(60.seconds, "процесс вышел") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        val out = outputs(rt.events(run, 0, 1000).events)
        assertTrue(out.any { it == "stdin chars: ${prompt.length}" }, out.toString())
        assertNull(DocumentStore(NioVfs(dir), "runs/${run.value}").read(AgentRuntime.INPUT_KEY).valueOrNull())
        val onDisk = dir.toFile().walkTopDown().filter { it.isFile }.joinToString(" ") { it.readText(Charsets.UTF_8) }
        assertFalse("секретный план" in onDisk, "вход хода остался на диске")
    }

    /** Хранение (A-5): завершённый запуск старше срока удаляется целиком, идущий — нет. */
    @Test
    fun oldFinishedRunsArePrunedRunningAreKept() {
        val rt = runtime()
        val done = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(done, RunSpec(TestChild.command("child", "secret"))).ok)
        RealTime.await(60.seconds, "процесс вышел") { rt.status(done).run!!.takeIf { it.state == RunState.EXITED } }
        // Супервизор пишет EXITED и только потом выходит: на Windows живой держит свой журнал.
        val supervisor = RunDoc.read(DocumentStore(NioVfs(dir), "runs/${done.value}").read(AgentRuntime.RUN_KEY).valueOrNull()!!.content)!!.supervisorPid!!
        ProcessHandle.of(supervisor).ifPresent { it.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS) }
        val running = ids.next(IdKinds.SESSION)
        startTree(rt, running)
        started += rt.status(running).run!!.pid!!
        val later = AgentRuntime(dir.toString(), com.dpsmpx.dpai.core.clock.ManualClock(com.dpsmpx.dpai.core.clock.Timestamp(Clock.SYSTEM.now().epochMillis + 8 * 86_400_000L)), "test", TestChild.selfCommand())
        assertEquals(1, later.prune(AgentRuntime.RETENTION))
        val left = dir.resolve("runs/${done.value}").toFile().list().orEmpty().toSet()
        assertEquals(setOf(AgentRuntime.PRUNED_KEY), left, "от завершённого запуска осталось только надгробие (A-6)")
        assertTrue(dir.resolve("runs/${running.value}/events.jsonl").toFile().exists(), "идущий запуск на месте")
        assertEquals(0, later.prune(AgentRuntime.RETENTION), "надгробие не удаляется повторно")
        assertTrue(rt.stop(running, 2.seconds).ok)
    }

    /**
     * A-6: сессия, удалённая по сроку (приостановлена недельным пределом),
     * продолжается следующим ходом — ход и номера событий идут дальше, а не
     * с №1: контроллер принимает новый ход, а не видит конфликт номеров.
     */
    @Test
    fun prunedSessionContinuesTurnAndNumbering() {
        val rt = runtime()
        val run = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "secret"))).ok)
        val channel = RunChannel(source(rt), run)
        RealTime.await(60.seconds, "первый ход") { rt.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        val supervisor = RunDoc.read(DocumentStore(NioVfs(dir), "runs/${run.value}").read(AgentRuntime.RUN_KEY).valueOrNull()!!.content)!!.supervisorPid!!
        ProcessHandle.of(supervisor).ifPresent { it.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS) }
        val firstLast = RealTime.await(30.seconds, "первый ход принят") {
            channel.poll().valueOrNull()
            channel.lastAccepted.takeIf { it == rt.status(run).run!!.lastSequence }
        }
        val later = AgentRuntime(dir.toString(), com.dpsmpx.dpai.core.clock.ManualClock(com.dpsmpx.dpai.core.clock.Timestamp(Clock.SYSTEM.now().epochMillis + 8 * 86_400_000L)), "test", TestChild.selfCommand())
        assertEquals(1, later.prune(AgentRuntime.RETENTION))
        assertTrue(later.start(run, RunSpec(TestChild.command("child", "secret"))).ok)
        val second = RealTime.await(60.seconds, "второй ход") { later.status(run).run!!.takeIf { it.state == RunState.EXITED } }
        assertEquals(2, second.turn, "ход продолжается после удаления")
        val events = later.events(run, 0, 1000).events
        assertEquals(firstLast + 1, events.first().sequence, "номера продолжаются, а не с №1")
        val got = mutableListOf<EventEnvelope>()
        RealTime.await(30.seconds, "второй ход принят каналом") {
            got += channel.poll().valueOrNull()!!.events
            got.takeIf { it.any { e -> e.type == EventType.TARGET_PROCESS_EXITED } }
        }
        assertEquals(events.map { it.id }, got.map { it.id }, "контроллер принял новый ход целиком")
    }

    /**
     * A-4: остановка, пока супервизор ещё не взял ход. `stop` не пишет
     * журнал сам (писатель один), а опоздавший супервизор видит просьбу и
     * выходит, не запуская процесс.
     */
    @Test
    fun stopBeforeSupervisorKeepsOneWriterAndNeverStartsProcess() {
        // Супервизор не приходит: программа без режима `noop` сразу выходит.
        val rt = AgentRuntime(dir.toString(), Clock.SYSTEM, "test", TestChild.selfCommand() + "noop", supervisorWait = 500.milliseconds, pollEvery = 50.milliseconds)
        val run = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "hang"), stdin = "prompt")).ok)
        val stop = rt.stop(run, 1.seconds)
        assertTrue(stop.ok, stop.diagnostics.toString())
        assertTrue(stop.diagnostics.any { it.code == DiagCode.AGENT_STOP_PENDING }, stop.diagnostics.toString())
        assertEquals(RunState.STARTING, stop.run!!.state)
        assertEquals(emptyList(), rt.events(run, 0, 1000).events, "stop журнал не пишет")
        // Супервизор пришёл позже — видит просьбу, процесс не запускает.
        val late = rt.supervise(run)
        assertTrue(late.ok, late.diagnostics.toString())
        val events = rt.events(run, 0, 1000).events
        assertEquals(listOf(EventType.TARGET_PROCESS_EXITED), events.map { it.type })
        assertEquals(JsonString(ExitReason.STOPPED.name), events.single().payload["reason"])
        assertEquals(RunState.EXITED, rt.status(run).run!!.state)
        assertNull(DocumentStore(NioVfs(dir), "runs/${run.value}").read(AgentRuntime.INPUT_KEY).valueOrNull(), "вход хода удалён")
        // Повторная остановка — уже завершённый ход.
        assertTrue(rt.stop(run, 1.seconds).ok)
    }

    /** A-4: супервизор, опоздавший дольше срока, хода не берёт — контроллер мог уже счесть его потерянным. */
    @Test
    fun lateSupervisorDoesNotTakeTheTurn() {
        val rt = AgentRuntime(dir.toString(), Clock.SYSTEM, "test", TestChild.selfCommand() + "noop")
        val run = ids.next(IdKinds.SESSION)
        assertTrue(rt.start(run, RunSpec(TestChild.command("child", "hang"))).ok)
        val clock = com.dpsmpx.dpai.core.clock.ManualClock(com.dpsmpx.dpai.core.clock.Timestamp(Clock.SYSTEM.now().epochMillis + 31_000))
        val late = AgentRuntime(dir.toString(), clock, "test", TestChild.selfCommand()).supervise(run)
        assertFalse(late.ok)
        assertEquals(DiagCode.AGENT_RUN_LOST, late.diagnostics.single().code)
        assertEquals(emptyList(), rt.events(run, 0, 1000).events)
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
