package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.diagnostics.DiagnosticCollector
import com.dpsmpx.dpai.core.diagnostics.Redactor
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.DiagnosticJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonFields
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.JsonValue
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.DoctorReport
import com.dpsmpx.dpai.execution.agent.ExitReason
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.execution.agent.RunState
import com.dpsmpx.dpai.execution.agent.RunStatus
import com.dpsmpx.dpai.execution.agent.StopResult
import com.dpsmpx.dpai.execution.agent.ToolProbe
import com.dpsmpx.dpai.execution.process.Detacher
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.ProcessTree
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.orchestrator.EventRecorder
import com.dpsmpx.dpai.storage.document.DocumentStore
import com.dpsmpx.dpai.storage.journal.EventJournal
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/**
 * Агент оркестратора на target — `dpai agent` (D-26, D-31; ТЗ, §56;
 * `docs/EXECUTION_MODEL.md`, §1.3).
 *
 * Каталог агента [home]: `runs/<SessionId>/` — документ запуска `run`
 * (`dpai.agent-run`), вход процесса `input`, журнал событий запуска
 * `events.jsonl` (`dpai.journal-record`), просьба остановиться
 * `stop-request`.
 *
 * - `start` записывает ход и запускает супервизор отдельным процессом
 *   ([Detacher]): обрыв SSH не убивает ни супервизор, ни Claude. `start`
 *   завершённого запуска — следующий ход в том же журнале: номера событий
 *   потока сессии продолжаются (`send` и `resume` раннера, этап 4).
 * - `supervise` — единственный писатель журнала во время хода: запускает
 *   процесс, пишет его вывод (через `Redactor`, D-14), heartbeat, выход;
 *   следит за тайм-аутом и просьбой остановиться.
 * - `events` отдаёт подтверждённые события с номера — после обрыва
 *   контроллер забирает пропущенное.
 * - `stop` просит супервизор остановить дерево процессов; если супервизор
 *   потерян — останавливает процесс сам по идентификатору и времени
 *   старта (чужой процесс с тем же идентификатором не трогается).
 *   Пока супервизор не взял ход (`STARTING`), `stop` журнал не пишет:
 *   писатель у журнала один (A-4), а супервизор увидит просьбу до запуска
 *   процесса.
 * - Удалённый по сроку запуск оставляет надгробие `pruned`
 *   (`dpai.agent-pruned`): номер хода и последнего события. Следующий ход
 *   той же сессии продолжает нумерацию, а не начинает с №1 (A-6).
 */
class AgentRuntime(
    private val home: String,
    private val clock: Clock,
    private val version: String,
    private val selfCommand: List<String>,
    private val host: ProcessHost = LocalProcessHost(clock),
    private val detacher: Detacher = Detacher(),
    private val heartbeatEvery: Duration = 30.seconds,
    private val pollEvery: Duration = 250.milliseconds,
    private val supervisorWait: Duration = 10.seconds,
) {
    private val vfs = NioVfs(Path.of(home))
    private val ids = IdGenerator(clock, RandomSource.secure())
    private val pace = java.util.concurrent.CountDownLatch(1)

    init {
        restrictHome(Path.of(home))
    }

    private fun runDir(id: SessionId) = "runs/${id.value}"

    private fun docs(id: SessionId) = DocumentStore(vfs, runDir(id))

    private fun journalPath(id: SessionId) = "${runDir(id)}/events.jsonl"

    fun start(id: SessionId, spec: RunSpec): AgentReply {
        prune(RETENTION, keep = id)
        val store = docs(id)
        val previous = when (val r = store.read(RUN_KEY)) {
            is Outcome.Failure -> return AgentReply.failure("start", r.diagnostics)
            is Outcome.Success -> r.value?.let { RunDoc.read(it.content) ?: return invalidDoc("start", id) }
        }
        if (previous != null) {
            val (seen, _) = (observe(id) as? Outcome.Success)?.value ?: return invalidDoc("start", id)
            if (seen.state != RunState.EXITED) {
                val why = if (seen.state == RunState.LOST) "прежний ход потерян: сначала stop" else "ход ${seen.turn} ещё идёт"
                return AgentReply.failure("start", listOf(Diagnostic.error(DiagCode.INVALID_TRANSITION, why, "run $id")))
            }
        }
        val turn = (previous?.turn ?: pruned(id)?.turn ?: 0) + 1
        val input = JsonObject(buildMap {
            put("turn", JsonNumber.of(turn))
            spec.stdin?.let { put("text", JsonString(it)) }
        })
        store.write(INPUT_KEY, input).let { if (it is Outcome.Failure) return AgentReply.failure("start", it.diagnostics) }
        val doc = RunDoc(id, spec.copy(stdin = null), clock.now(), RunState.STARTING, turn = turn)
        store.write(RUN_KEY, doc.toJson()).let {
            if (it is Outcome.Failure) {
                store.delete(INPUT_KEY)
                return AgentReply.failure("start", it.diagnostics)
            }
        }
        val last = lastSequence(id)
        val argv = selfCommand + listOf("agent", "supervise", "--home", home, "--run", id.value)
        val log = File(home, "${runDir(id)}/supervisor.log").path
        return when (val spawned = detacher.spawn(argv, home, log)) {
            is Outcome.Failure -> {
                val failed = doc.copy(state = RunState.EXITED, reason = ExitReason.START_FAILED)
                store.write(RUN_KEY, failed.toJson())
                AgentReply("start", ok = false, diagnostics = spawned.diagnostics, run = failed.status(last))
            }
            is Outcome.Success -> AgentReply("start", ok = true, run = doc.status(last))
        }
    }

    fun supervise(id: SessionId): AgentReply {
        val store = docs(id)
        val doc = when (val r = store.read(RUN_KEY)) {
            is Outcome.Failure -> return AgentReply.failure("supervise", r.diagnostics)
            is Outcome.Success -> r.value?.let { RunDoc.read(it.content) }
                ?: return AgentReply.failure("supervise", listOf(Diagnostic.error(DiagCode.ENTITY_NOT_FOUND, "нет такого запуска", "run $id")))
        }
        if (doc.state != RunState.STARTING) {
            return AgentReply.failure("supervise", listOf(Diagnostic.error(DiagCode.INVALID_TRANSITION, "ход уже под супервизором", "run $id")))
        }
        // Опоздавший супервизор хода не берёт: контроллер мог уже счесть ход потерянным и записать
        // выход сам (A-4). Запас до START_GRACE — чтобы проверка и решение контроллера не совпали.
        if (clock.now() - doc.createdAt > SUPERVISOR_DEADLINE) {
            // Ход не будет взят никем: вход хода (prompt) на target не нужен (ревью A-16).
            store.delete(INPUT_KEY)
            return AgentReply.failure("supervise", listOf(Diagnostic.error(DiagCode.AGENT_RUN_LOST, "супервизор запущен позже ${SUPERVISOR_DEADLINE}: ход не взят", "run $id")))
        }
        val stdin = (store.read(INPUT_KEY) as? Outcome.Success)?.value?.content
            ?.takeIf { (it["turn"] as? JsonNumber)?.toLongExact() == doc.turn.toLong() }
            ?.let { (it["text"] as? JsonString)?.value }
        val journal = when (val j = EventJournal.open(vfs, journalPath(id))) {
            is Outcome.Failure -> return AgentReply.failure("supervise", j.diagnostics)
            is Outcome.Success -> j.value
        }
        val stream = StreamRef.of(id)
        val recorder = EventRecorder(ids, clock).apply {
            resumeFrom(journal.records())
            pruned(id)?.let { resumeFromLast(mapOf(stream to it.lastSequence)) }
        }
        val lock = Any()
        fun emit(type: EventType, payload: JsonObject) = synchronized(lock) {
            journal.append(recorder.envelope(type, stream, clock.now(), payload))
        }
        val self = ProcessHandle.current()
        var current = doc.copy(supervisorPid = self.pid(), supervisorStartedAt = ProcessTree.startedAtMillis(self))
        // Супервизор записывает себя до запуска процесса: так stop находит его и просит, а не пишет сам (A-4).
        store.write(RUN_KEY, current.toJson()).let { if (it is Outcome.Failure) return AgentReply.failure("supervise", it.diagnostics) }
        if (stopRequest(store, doc.turn) != null) {
            // Остановку попросили раньше, чем ход начался: процесс не запускается.
            if (stdin != null) store.delete(INPUT_KEY)
            emit(EventType.TARGET_PROCESS_EXITED, exitPayload(doc.turn, null, ExitReason.STOPPED, emptyList()))
            current = current.copy(state = RunState.EXITED, reason = ExitReason.STOPPED)
            store.write(RUN_KEY, current.toJson())
            return AgentReply("supervise", ok = true, run = current.status(journal.position))
        }
        val env = HashMap(System.getenv()).apply {
            doc.spec.unsetEnv.forEach { remove(it) }
            putAll(doc.spec.setEnv)
        }
        // Вывод ждёт блокировки, пока не записано TARGET_PROCESS_STARTED: в журнале ход начинается с него.
        val process: RunningProcess = synchronized(lock) {
            val started = host.start(ProcessSpec(doc.spec.command, doc.spec.workDir, env, stdin)) { line ->
                // Текст события — не длиннее MAX_EVENT_TEXT: с экранированием JSON одно событие
                // помещается в строку ответа агента (см. events).
                val text = Redactor.STANDARD.redactLine(line.text)
                val cut = text.length > MAX_EVENT_TEXT
                emit(
                    EventType.TARGET_PROCESS_OUTPUT,
                    JsonObject.of(
                        "stream" to JsonString(line.stream.name.lowercase()),
                        "text" to JsonString(if (cut) text.take(MAX_EVENT_TEXT) else text),
                        "truncated" to JsonBool(line.truncated || cut),
                    ),
                )
            }
            when (started) {
                is Outcome.Failure -> {
                    // Процесс не запустился — вход хода (prompt) на target тоже не хранится (ревью A-73).
                    if (stdin != null) store.delete(INPUT_KEY)
                    emit(EventType.TARGET_PROCESS_EXITED, exitPayload(doc.turn, null, ExitReason.START_FAILED, started.diagnostics))
                    current = current.copy(state = RunState.EXITED, reason = ExitReason.START_FAILED)
                    store.write(RUN_KEY, current.toJson())
                    return AgentReply("supervise", ok = false, diagnostics = started.diagnostics, run = current.status(journal.position))
                }
                is Outcome.Success -> started.value.also { p ->
                    emit(
                        EventType.TARGET_PROCESS_STARTED,
                        JsonObject(buildMap {
                            put("program", JsonString(doc.spec.command.first()))
                            put("turn", JsonNumber.of(doc.turn))
                            p.pid?.let { put("pid", JsonNumber.of(it)) }
                        }),
                    )
                }
            }
        }
        // Вход хода (prompt) отдан процессу — на target он больше не хранится (аудит 2026-10-07, A-5).
        if (stdin != null) store.delete(INPUT_KEY)
        val handle = process.pid?.let { ProcessHandle.of(it).orElse(null) }
        current = current.copy(state = RunState.RUNNING, pid = process.pid, pidStartedAt = handle?.let(ProcessTree::startedAtMillis))
        store.write(RUN_KEY, current.toJson())

        val begin = clock.monotonicNanos()
        var lastBeat = begin
        var exitCode: Int?
        var reason: ExitReason
        var stopDiags = emptyList<Diagnostic>()
        while (true) {
            exitCode = process.awaitExit(pollEvery)
            if (exitCode != null) {
                reason = ExitReason.EXITED
                break
            }
            val stopRequest = stopRequest(store, doc.turn)
            val timedOut = doc.spec.timeout?.let { clock.monotonicNanos() - begin >= it.inWholeNanoseconds } ?: false
            if (stopRequest != null || timedOut) {
                val report = process.stop(stopRequest ?: RunningProcess.DEFAULT_GRACE)
                exitCode = report.exitCode
                reason = if (stopRequest != null) ExitReason.STOPPED else ExitReason.TIMEOUT
                stopDiags = buildList {
                    if (reason == ExitReason.TIMEOUT) add(Diagnostic.warning(DiagCode.PROCESS_TIMEOUT, "тайм-аут ${doc.spec.timeout}: дерево процессов остановлено", "run $id"))
                    if (!report.clean) add(Diagnostic.error(DiagCode.PROCESS_ORPHANS_LEFT, "после остановки живы ${report.survivors.joinToString()}", "run $id"))
                }
                break
            }
            if (clock.monotonicNanos() - lastBeat >= heartbeatEvery.inWholeNanoseconds) {
                lastBeat = clock.monotonicNanos()
                emit(EventType.TARGET_PROCESS_HEARTBEAT, JsonObject.of("turn" to JsonNumber.of(doc.turn)))
            }
        }
        emit(EventType.TARGET_PROCESS_EXITED, exitPayload(doc.turn, exitCode, reason, stopDiags))
        current = current.copy(state = RunState.EXITED, exitCode = exitCode, reason = reason)
        store.write(RUN_KEY, current.toJson())
        return AgentReply("supervise", ok = true, diagnostics = stopDiags.filterNot { it.isError }, run = current.status(journal.position))
    }

    /** Пауза остановки, если для этого хода её попросили. */
    private fun stopRequest(store: DocumentStore, turn: Int): Duration? {
        val req = (store.read(STOP_KEY) as? Outcome.Success)?.value?.content ?: return null
        if ((req["turn"] as? JsonNumber)?.toLongExact() != turn.toLong()) return null
        return (req["graceSeconds"] as? JsonNumber)?.toLongExact()?.seconds ?: RunningProcess.DEFAULT_GRACE
    }

    fun status(id: SessionId): AgentReply = when (val s = observe(id)) {
        is Outcome.Failure -> AgentReply.failure("status", s.diagnostics)
        is Outcome.Success -> AgentReply("status", ok = true, run = s.value.first.status(s.value.second))
    }

    fun events(id: SessionId, after: Long, limit: Int): AgentReply {
        if (after < 0 || limit !in 1..10_000) {
            return AgentReply.failure("events", listOf(Diagnostic.error(DiagCode.VALUE_INVALID, "after ≥ 0, limit — 1…10000")))
        }
        val records = when (val r = EventJournal.readCommitted(vfs, journalPath(id))) {
            is Outcome.Failure -> return AgentReply.failure("events", r.diagnostics)
            is Outcome.Success -> r.value
        }
        val status = when (val s = observe(id, records.lastOrNull()?.event?.sequence ?: 0)) {
            is Outcome.Failure -> return AgentReply.failure("events", s.diagnostics)
            is Outcome.Success -> s.value
        }
        // Страница — не больше PAGE_CHARS знаков (и хотя бы одно событие): ответ — одна строка,
        // а контроллер читает строку до LocalProcessHost.MAX_LINE; обрезанный ответ не разбирался,
        // и канал запрашивал ту же страницу без конца (ревью 8 октября 2026).
        val candidates = records.asSequence().map { it.event }.filter { it.sequence > after }.take(limit).toList()
        var size = 0
        val page = candidates.takeWhile { e ->
            val first = size == 0
            size += CanonicalJson.write(e.toJson()).length
            first || size <= PAGE_CHARS
        }
        return AgentReply("events", ok = true, run = status.first.status(status.second), events = page, more = page.size < candidates.size)
    }

    fun stop(id: SessionId, grace: Duration): AgentReply {
        val store = docs(id)
        val (doc, last) = when (val s = observe(id)) {
            is Outcome.Failure -> return AgentReply.failure("stop", s.diagnostics)
            is Outcome.Success -> s.value
        }
        if (doc.state == RunState.EXITED) return AgentReply("stop", ok = true, run = doc.status(last), stop = StopResult(false, emptyList()))
        if (doc.state == RunState.STARTING && doc.supervisorPid == null) return stopBeforeSupervisor(id, doc, last, grace, store)
        val supervisor = doc.supervisorPid?.let { ProcessTree.find(it, doc.supervisorStartedAt) }
        if (supervisor != null) return stopWithSupervisor(id, doc, last, supervisor, grace, store)
        // Супервизор потерян: процесс останавливается по идентификатору и времени старта.
        val child = doc.pid?.let { ProcessTree.find(it, doc.pidStartedAt) }
        val r = child?.let { ProcessTree.stop(it, grace, clock) } ?: ProcessTree.Result(false, emptyList())
        return stopLost(id, doc, last, r, store)
    }

    private fun requestStop(store: DocumentStore, turn: Int, grace: Duration) {
        store.write(STOP_KEY, JsonObject.of("turn" to JsonNumber.of(turn), "graceSeconds" to JsonNumber.of(grace.inWholeSeconds.coerceAtLeast(1))))
    }

    private fun stopWithSupervisor(id: SessionId, doc: RunDoc, last: Long, supervisor: ProcessHandle, grace: Duration, store: DocumentStore): AgentReply {
        requestStop(store, doc.turn, grace)
        val waited = runCatching { supervisor.onExit().get((grace + SUPERVISOR_EXTRA).inWholeMilliseconds, TimeUnit.MILLISECONDS) }.isSuccess
        if (waited) {
            val after = observe(id)
            if (after is Outcome.Success) {
                return AgentReply("stop", ok = true, run = after.value.first.status(after.value.second), stop = StopResult(false, emptyList()))
            }
        }
        // Супервизор не уложился — останавливается он сам вместе со своим деревом.
        return stopLost(id, doc, last, ProcessTree.stop(supervisor, grace, clock), store)
    }

    /**
     * Ход записан, а супервизор ещё не взял его (A-4): он запущен отдельно и
     * может прийти в любой момент. Писать выход самому — значит завести
     * второго писателя журнала, а супервизор потом запустил бы Claude без
     * контроля. Просьба остановиться записывается, и `stop` ждёт, пока
     * супервизор запишет себя или выход; не дождался — отвечает
     * `AGENT_STOP_PENDING`: процесс не запущен и не запустится. Супервизор,
     * опоздавший дольше [SUPERVISOR_DEADLINE], хода не берёт, а ход без
     * супервизора дольше [START_GRACE] — `LOST`, его выход пишет `stop`.
     */
    private fun stopBeforeSupervisor(id: SessionId, doc: RunDoc, last: Long, grace: Duration, store: DocumentStore): AgentReply {
        requestStop(store, doc.turn, grace)
        val polls = (supervisorWait.inWholeMilliseconds / pollEvery.inWholeMilliseconds.coerceAtLeast(1)).coerceAtLeast(1)
        repeat(polls.toInt()) {
            pace.await(pollEvery.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            val (now, lastNow) = (observe(id) as? Outcome.Success)?.value ?: return@repeat
            when {
                now.state == RunState.EXITED -> return AgentReply("stop", ok = true, run = now.status(lastNow), stop = StopResult(false, emptyList()))
                now.supervisorPid != null -> {
                    val supervisor = ProcessTree.find(now.supervisorPid, now.supervisorStartedAt)
                    return if (supervisor != null) stopWithSupervisor(id, now, lastNow, supervisor, grace, store) else stop(id, grace)
                }
                now.state == RunState.LOST -> return stop(id, grace)
            }
        }
        val pending = Diagnostic.warning(DiagCode.AGENT_STOP_PENDING, "супервизор ещё не взял ход ${doc.turn}: остановка запрошена, процесс не запустится", "run $id")
        return AgentReply("stop", ok = true, diagnostics = listOf(pending), run = doc.status(last), stop = StopResult(false, emptyList()))
    }

    private fun stopLost(id: SessionId, doc: RunDoc, last: Long, r: ProcessTree.Result, store: DocumentStore): AgentReply {
        val exited = doc.copy(state = RunState.EXITED, reason = ExitReason.STOPPED)
        // Ход потерян и закрыт: вход хода (prompt), если процесс так и не начался, на target не остаётся (ревью A-16).
        store.delete(INPUT_KEY)
        // Супервизор мёртв — у журнала нет писателя; выход записывает stop.
        EventJournal.open(vfs, journalPath(id)).valueOrNull()?.let { journal ->
            val recorder = EventRecorder(ids, clock).apply { resumeFrom(journal.records()) }
            journal.append(recorder.envelope(EventType.TARGET_PROCESS_EXITED, StreamRef.of(id), clock.now(), exitPayload(doc.turn, null, ExitReason.STOPPED, emptyList())))
        }
        store.write(RUN_KEY, exited.toJson())
        val diags = if (r.survivors.isEmpty()) {
            emptyList()
        } else {
            listOf(Diagnostic.error(DiagCode.PROCESS_ORPHANS_LEFT, "после остановки живы ${r.survivors.joinToString()}", "run $id"))
        }
        val lastNow = lastSequence(id).takeIf { it > 0 } ?: last
        return AgentReply("stop", ok = diags.isEmpty(), diagnostics = diags, run = exited.status(lastNow), stop = StopResult(r.forced, r.survivors))
    }

    fun doctor(): AgentReply {
        File(home).mkdirs()
        val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        val report = DoctorReport(
            agentVersion = version,
            os = System.getProperty("os.name").orEmpty(),
            arch = System.getProperty("os.arch").orEmpty(),
            javaVersion = System.getProperty("java.version").orEmpty(),
            timeMillis = clock.now().epochMillis,
            termux = System.getenv("PREFIX")?.contains("com.termux") == true,
            diskFreeBytes = File(home).usableSpace.takeIf { it > 0 },
            memoryTotalBytes = os?.totalMemorySize,
            memoryFreeBytes = availableMemory(meminfo(), os?.freeMemorySize, os?.totalMemorySize),
            claude = probeTool(listOf("claude", "--version")),
            git = probeTool(listOf("git", "--version")),
        )
        return AgentReply("doctor", ok = true, doctor = report)
    }

    private fun probeTool(argv: List<String>): ToolProbe {
        var r = host.run(ProcessSpec(argv), TOOL_TIMEOUT)
        // npm ставит на Windows `claude.cmd`: без расширения его находит только cmd.exe.
        if (Detacher.isWindows() && r is Outcome.Failure && r.errors.any { it.code == DiagCode.PROCESS_START_FAILED }) {
            r = host.run(ProcessSpec(listOf("cmd.exe", "/c") + argv), TOOL_TIMEOUT)
        }
        return when (r) {
            is Outcome.Failure -> ToolProbe(false, null, r.errors.first().message.take(200))
            is Outcome.Success ->
                if (r.value.ok) {
                    ToolProbe(true, r.value.stdout.firstOrNull { it.isNotBlank() }?.trim()?.take(200), null)
                } else {
                    ToolProbe(false, null, "код ${r.value.exitCode}: " + (r.value.stderr + r.value.stdout).lastOrNull { it.isNotBlank() }.orEmpty().take(200))
                }
        }
    }

    private fun lastSequence(id: SessionId): Long =
        maxOf(EventJournal.readCommitted(vfs, journalPath(id)).valueOrNull()?.lastOrNull()?.event?.sequence ?: 0, pruned(id)?.lastSequence ?: 0)

    /** Надгробие удалённого по сроку запуска (A-6): с какого хода и номера продолжать. */
    private fun pruned(id: SessionId): PrunedDoc? =
        (docs(id).read(PRUNED_KEY) as? Outcome.Success)?.value?.let { PrunedDoc.read(it.content) }

    private fun invalidDoc(command: String, id: SessionId) =
        AgentReply.failure(command, listOf(Diagnostic.error(DiagCode.STORAGE_CORRUPTED, "документ запуска не прочитан", "run $id")))

    /**
     * Документ запуска и номер последнего события. Ход, супервизор которого
     * умер, не записав выхода, — `LOST`: что с процессом, решает сверка
     * (D-13).
     */
    private fun observe(id: SessionId, knownLast: Long? = null): Outcome<Pair<RunDoc, Long>> {
        val doc = when (val r = docs(id).read(RUN_KEY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value?.let { RunDoc.read(it.content) }
                ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого запуска", "run $id")
        }
        val last = knownLast ?: lastSequence(id)
        if (doc.state == RunState.EXITED) return Outcome.success(doc to last)
        val supervisorAlive = doc.supervisorPid?.let { ProcessTree.find(it, doc.supervisorStartedAt) != null }
        val lost = when (supervisorAlive) {
            true -> false
            false -> true
            null -> clock.now() - doc.createdAt > START_GRACE
        }
        return Outcome.success((if (lost) doc.copy(state = RunState.LOST) else doc) to last)
    }

    private fun exitPayload(turn: Int, code: Int?, reason: ExitReason, diags: List<Diagnostic>): JsonObject {
        val f = linkedMapOf<String, JsonValue>("reason" to JsonString(reason.name), "turn" to JsonNumber.of(turn))
        code?.let { f["exitCode"] = JsonNumber.of(it) }
        if (diags.isNotEmpty()) f["diagnostics"] = DiagnosticJson.writeAll(diags)
        return JsonObject(f)
    }

    /**
     * Хранение журналов запусков (аудит 2026-10-07, A-5): завершённый
     * запуск, последний ход которого начат раньше [olderThan], удаляется —
     * документ запуска, вход, журнал событий, журнал супервизора. Идущие и
     * потерянные (`LOST`: процесс может быть жив) не трогаются. Вызывается
     * при каждом `start`; возвращает, сколько удалено.
     *
     * Остаётся надгробие `pruned` — номер хода и последнего события, без
     * текста хода (A-6): сессия, приостановленная недельным пределом,
     * продолжается позже срока, и её следующий ход должен продолжить
     * нумерацию — иначе контроллер видит под принятыми номерами другие
     * события и теряет весь ход. Надгробие пишется до удаления: прерванное
     * удаление нумерацию не сбрасывает.
     */
    fun prune(olderThan: Duration, keep: SessionId? = null): Int {
        val dirs = File(home, "runs").listFiles { f -> f.isDirectory }.orEmpty()
        var removed = 0
        for (d in dirs.sortedBy { it.name }) {
            val id = IdKinds.SESSION.parse(d.name).valueOrNull() ?: continue
            if (id == keep) continue
            val doc = (docs(id).read(RUN_KEY) as? Outcome.Success)?.value?.let { RunDoc.read(it.content) } ?: continue
            if (doc.state != RunState.EXITED || clock.now() - doc.createdAt < olderThan) continue
            val tomb = PrunedDoc(doc.turn, lastSequence(id), clock.now())
            if (docs(id).write(PRUNED_KEY, tomb.toJson()) is Outcome.Failure) continue
            try {
                for (f in vfs.list(runDir(id))) {
                    if (f == PRUNED_KEY || f.startsWith("$PRUNED_KEY.")) continue
                    vfs.delete("${runDir(id)}/$f")
                }
                removed++
            } catch (_: java.io.IOException) {
                // Не удалилось (файл занят) — попробуем при следующем start.
            }
        }
        if (removed > 0) runCatching { vfs.fsyncDir("runs") }
        return removed
    }

    companion object {
        /** Сколько хранить завершённые запуски на target (A-5). */
        val RETENTION: Duration = 7.days

        /** Предел знаков страницы событий: ответ агента — одна строка не длиннее `LocalProcessHost.MAX_LINE`. */
        const val PAGE_CHARS: Int = 256 * 1024

        /** Предел текста одного события вывода: даже с экранированием JSON оно меньше `LocalProcessHost.MAX_LINE`. */
        const val MAX_EVENT_TEXT: Int = 256 * 1024

        /**
         * Каталог агента — только владельцу (T8; аудит 2026-10-07, A-4): в нём
         * prompt и вывод Claude, а на общем Linux-хосте права по умолчанию
         * (umask 022) открыли бы их другим пользователям. Где POSIX-прав нет
         * (Windows), каталог наследует права профиля пользователя.
         */
        fun restrictHome(home: Path) {
            java.nio.file.Files.createDirectories(home)
            val posix = java.nio.file.Files.getFileAttributeView(home, java.nio.file.attribute.PosixFileAttributeView::class.java) ?: return
            posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
        }

        const val RUN_KEY = "run"
        const val INPUT_KEY = "input"
        const val STOP_KEY = "stop-request"
        const val PRUNED_KEY = "pruned"
        private val TOOL_TIMEOUT = 20.seconds
        private val SUPERVISOR_EXTRA = 10.seconds

        /** Сколько ждать, пока супервизор запишет себя, прежде чем считать ход потерянным. */
        val START_GRACE: Duration = 60.seconds

        /** Позже этого супервизор хода не берёт (A-4): запас до [START_GRACE], после которого ход — `LOST`. */
        val SUPERVISOR_DEADLINE: Duration = 30.seconds

        /**
         * Доступная память для `doctor` (ревью 8 октября 2026, A-20). На Linux и
         * Android `freeMemorySize` JDK — это `MemFree`, без кеша страниц, который
         * ядро отдаёт по первому требованию: на телефоне это сотни МиБ из 7,5 ГиБ,
         * и `doctor` всегда предупреждал о памяти. Доступная — `MemAvailable` из
         * `/proc/meminfo`. Если JDK видит предел контейнера ниже памяти машины,
         * `MemAvailable` всей машины ограничивается свободным под пределом. Без
         * `MemAvailable` (Windows, macOS, ядро до 3.14) — значение JDK: на
         * Windows это уже доступная физическая память.
         */
        internal fun availableMemory(meminfo: String?, jvmFree: Long?, jvmTotal: Long?): Long? {
            val kb = HashMap<String, Long>()
            meminfo?.lineSequence()?.forEach { line ->
                val m = MEMINFO_LINE.matchEntire(line.trim()) ?: return@forEach
                m.groupValues[2].toLongOrNull()?.let { kb[m.groupValues[1]] = it }
            }
            val available = kb["MemAvailable"]?.let { it * 1024 } ?: return jvmFree
            val machine = kb["MemTotal"]?.let { it * 1024 }
            val limited = jvmTotal != null && machine != null && jvmTotal < machine
            return if (limited && jvmFree != null) minOf(available, jvmFree) else available
        }

        /** Текст `/proc/meminfo`; нет файла (Windows, macOS) или он не читается — `null`. */
        private fun meminfo(): String? = try {
            File("/proc/meminfo").takeIf { it.isFile }?.readText()
        } catch (_: java.io.IOException) {
            null
        }

        private val MEMINFO_LINE = Regex("""(\w+):\s+(\d+) kB""")
    }
}

/**
 * Документ запуска `dpai.agent-run` v2 (`docs/DATA_MODEL.md`, §4): текущий
 * ход. v1 (этап 3: спецификация полями документа, без хода) заменён до
 * выпуска и не читается: запуск агента — недолговечное состояние, а
 * незнакомая версия — отказ, а не догадка (D-09).
 */
internal data class RunDoc(
    val id: SessionId,
    val spec: RunSpec,
    val createdAt: Timestamp,
    val state: RunState,
    val turn: Int,
    val supervisorPid: Long? = null,
    val supervisorStartedAt: Long? = null,
    val pid: Long? = null,
    val pidStartedAt: Long? = null,
    val exitCode: Int? = null,
    val reason: ExitReason? = null,
) {
    fun status(lastSequence: Long): RunStatus = RunStatus(id, state, pid, exitCode, reason, lastSequence, turn)

    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, VERSION))
        f["id"] = JsonString(id.value)
        f["spec"] = spec.toJson()
        f["createdAt"] = JsonNumber.of(createdAt.epochMillis)
        f["state"] = JsonString(state.name)
        f["turn"] = JsonNumber.of(turn)
        supervisorPid?.let { f["supervisorPid"] = JsonNumber.of(it) }
        supervisorStartedAt?.let { f["supervisorStartedAt"] = JsonNumber.of(it) }
        pid?.let { f["pid"] = JsonNumber.of(it) }
        pidStartedAt?.let { f["pidStartedAt"] = JsonNumber.of(it) }
        exitCode?.let { f["exitCode"] = JsonNumber.of(it) }
        reason?.let { f["reason"] = JsonString(it.name) }
        return JsonObject(f)
    }

    companion object {
        const val FORMAT = "dpai.agent-run"
        const val VERSION = 2

        fun read(value: JsonObject): RunDoc? {
            val d = DiagnosticCollector()
            val f = JsonFields(value, "$", d)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return null
            val id = f.string("id")?.let { IdKinds.SESSION.parse(it).valueOrNull() }
            val spec = f.raw("spec")?.let { RunSpec.fromJson(it).valueOrNull() }
            val created = f.long("createdAt")?.let(::Timestamp)
            val state = f.string("state")?.let { s -> RunState.entries.firstOrNull { it.name == s } }
            val turn = f.int("turn")
            val supervisorPid = f.optLong("supervisorPid")
            val supervisorStartedAt = f.optLong("supervisorStartedAt")
            val pid = f.optLong("pid")
            val pidStartedAt = f.optLong("pidStartedAt")
            val exitCode = f.optLong("exitCode")?.toInt()
            val reason = f.optString("reason")?.let { s -> ExitReason.entries.firstOrNull { it.name == s } }
            f.finish()
            if (d.hasErrors || id == null || spec == null || created == null || state == null || turn == null) return null
            return RunDoc(id, spec, created, state, turn, supervisorPid, supervisorStartedAt, pid, pidStartedAt, exitCode, reason)
        }
    }
}

/**
 * Надгробие удалённого по сроку запуска `dpai.agent-pruned` v1
 * (`docs/DATA_MODEL.md`, §4; A-6): номер последнего хода и последнего
 * события потока сессии. Текста хода в нём нет — срок хранения A-5
 * соблюдается.
 */
internal data class PrunedDoc(val turn: Int, val lastSequence: Long, val prunedAt: Timestamp) {
    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, VERSION))
        f["turn"] = JsonNumber.of(turn)
        f["lastSequence"] = JsonNumber.of(lastSequence)
        f["prunedAt"] = JsonNumber.of(prunedAt.epochMillis)
        return JsonObject(f)
    }

    companion object {
        const val FORMAT = "dpai.agent-pruned"
        const val VERSION = 1

        fun read(value: JsonObject): PrunedDoc? {
            val d = DiagnosticCollector()
            val f = JsonFields(value, "$", d)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return null
            val turn = f.int("turn")
            val last = f.long("lastSequence")
            val at = f.long("prunedAt")?.let(::Timestamp)
            f.finish()
            if (d.hasErrors || turn == null || last == null || at == null) return null
            return PrunedDoc(turn, last, at)
        }
    }
}
