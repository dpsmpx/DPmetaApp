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
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.DiagnosticJson
import com.dpsmpx.dpai.core.serialization.FormatHeader
import com.dpsmpx.dpai.core.serialization.JsonArray
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
import com.dpsmpx.dpai.execution.process.OutputStream
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Агент оркестратора на target — `dpai agent` (D-26; ТЗ, §56;
 * `docs/EXECUTION_MODEL.md`, §1).
 *
 * Каталог агента [home]: `runs/<SessionId>/` — документ запуска `run`
 * (`dpai.agent-run`), журнал событий запуска `events.jsonl`
 * (`dpai.journal-record`), просьба остановиться `stop-request`.
 *
 * - `start` записывает запуск и запускает супервизор отдельным процессом
 *   ([Detacher]): обрыв SSH не убивает ни супервизор, ни Claude.
 * - `supervise` — единственный писатель журнала запуска: запускает
 *   процесс, пишет его вывод (через `Redactor`, D-14), heartbeat, выход;
 *   следит за тайм-аутом и просьбой остановиться.
 * - `events` отдаёт подтверждённые события с номера — после обрыва
 *   контроллер забирает пропущенное.
 * - `stop` просит супервизор остановить дерево процессов; если супервизор
 *   потерян — останавливает процесс сам по идентификатору и времени
 *   старта (чужой процесс с тем же идентификатором не трогается).
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
) {
    private val vfs = NioVfs(Path.of(home))
    private val ids = IdGenerator(clock, RandomSource.secure())

    private fun runDir(id: SessionId) = "runs/${id.value}"

    private fun docs(id: SessionId) = DocumentStore(vfs, runDir(id))

    private fun journalPath(id: SessionId) = "${runDir(id)}/events.jsonl"

    fun start(id: SessionId, spec: RunSpec): AgentReply {
        val store = docs(id)
        when (val existing = store.read(RUN_KEY)) {
            is Outcome.Failure -> return AgentReply.failure("start", existing.diagnostics)
            is Outcome.Success -> if (existing.value != null) {
                return AgentReply.failure("start", listOf(Diagnostic.error(DiagCode.VALUE_INVALID, "запуск уже есть", "run $id")))
            }
        }
        val doc = RunDoc(id, spec, clock.now(), RunState.STARTING)
        store.write(RUN_KEY, doc.toJson()).let { if (it is Outcome.Failure) return AgentReply.failure("start", it.diagnostics) }
        val argv = selfCommand + listOf("agent", "supervise", "--home", home, "--run", id.value)
        val log = File(home, "${runDir(id)}/supervisor.log").path
        return when (val spawned = detacher.spawn(argv, home, log)) {
            is Outcome.Failure -> {
                val failed = doc.copy(state = RunState.EXITED, reason = ExitReason.START_FAILED)
                store.write(RUN_KEY, failed.toJson())
                AgentReply("start", ok = false, diagnostics = spawned.diagnostics, run = failed.status(0))
            }
            is Outcome.Success -> AgentReply("start", ok = true, run = doc.status(0))
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
            return AgentReply.failure("supervise", listOf(Diagnostic.error(DiagCode.INVALID_TRANSITION, "запуск уже под супервизором", "run $id")))
        }
        val journal = when (val j = EventJournal.open(vfs, journalPath(id))) {
            is Outcome.Failure -> return AgentReply.failure("supervise", j.diagnostics)
            is Outcome.Success -> j.value
        }
        val recorder = EventRecorder(ids, clock).apply { resumeFrom(journal.records()) }
        val stream = StreamRef.of(id)
        val lock = Any()
        fun emit(type: EventType, payload: JsonObject) = synchronized(lock) {
            journal.append(recorder.envelope(type, stream, clock.now(), payload))
        }
        val self = ProcessHandle.current()
        var current = doc.copy(supervisorPid = self.pid(), supervisorStartedAt = ProcessTree.startedAtMillis(self))
        val env = HashMap(System.getenv()).apply {
            doc.spec.unsetEnv.forEach { remove(it) }
            putAll(doc.spec.setEnv)
        }
        val started = host.start(ProcessSpec(doc.spec.command, doc.spec.workDir, env)) { line ->
            emit(
                EventType.TARGET_PROCESS_OUTPUT,
                JsonObject.of(
                    "stream" to JsonString(line.stream.name.lowercase()),
                    "text" to JsonString(Redactor.STANDARD.redact(line.text)),
                    "truncated" to JsonBool(line.truncated),
                ),
            )
        }
        val process: RunningProcess = when (started) {
            is Outcome.Failure -> {
                emit(EventType.TARGET_PROCESS_EXITED, exitPayload(null, ExitReason.START_FAILED, started.diagnostics))
                current = current.copy(state = RunState.EXITED, reason = ExitReason.START_FAILED)
                store.write(RUN_KEY, current.toJson())
                return AgentReply("supervise", ok = false, diagnostics = started.diagnostics, run = current.status(journal.position))
            }
            is Outcome.Success -> started.value
        }
        val handle = process.pid?.let { ProcessHandle.of(it).orElse(null) }
        current = current.copy(state = RunState.RUNNING, pid = process.pid, pidStartedAt = handle?.let(ProcessTree::startedAtMillis))
        emit(EventType.TARGET_PROCESS_STARTED, JsonObject.of("program" to JsonString(doc.spec.command.first())).let { base ->
            process.pid?.let { JsonObject(base.fields + ("pid" to JsonNumber.of(it))) } ?: base
        })
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
            val stopRequested = (store.read(STOP_KEY) as? Outcome.Success)?.value != null
            val timedOut = doc.spec.timeout?.let { clock.monotonicNanos() - begin >= it.inWholeNanoseconds } ?: false
            if (stopRequested || timedOut) {
                val grace = (store.read(STOP_KEY) as? Outcome.Success)?.value?.content?.get("graceSeconds")
                    ?.let { (it as? JsonNumber)?.toLongExact()?.seconds } ?: RunningProcess.DEFAULT_GRACE
                val report = process.stop(grace)
                exitCode = report.exitCode
                reason = if (stopRequested) ExitReason.STOPPED else ExitReason.TIMEOUT
                stopDiags = buildList {
                    if (reason == ExitReason.TIMEOUT) add(Diagnostic.warning(DiagCode.PROCESS_TIMEOUT, "тайм-аут ${doc.spec.timeout}: дерево процессов остановлено", "run $id"))
                    if (!report.clean) add(Diagnostic.error(DiagCode.PROCESS_ORPHANS_LEFT, "после остановки живы ${report.survivors.joinToString()}", "run $id"))
                }
                break
            }
            if (clock.monotonicNanos() - lastBeat >= heartbeatEvery.inWholeNanoseconds) {
                lastBeat = clock.monotonicNanos()
                emit(EventType.TARGET_PROCESS_HEARTBEAT, JsonObject(emptyMap()))
            }
        }
        emit(EventType.TARGET_PROCESS_EXITED, exitPayload(exitCode, reason, stopDiags))
        current = current.copy(state = RunState.EXITED, exitCode = exitCode, reason = reason)
        store.write(RUN_KEY, current.toJson())
        return AgentReply("supervise", ok = true, diagnostics = stopDiags.filterNot { it.isError }, run = current.status(journal.position))
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
        val page = records.asSequence().map { it.event }.filter { it.sequence > after }.take(limit).toList()
        return AgentReply("events", ok = true, run = status.first.status(status.second), events = page)
    }

    fun stop(id: SessionId, grace: Duration): AgentReply {
        val store = docs(id)
        val (doc, last) = when (val s = observe(id)) {
            is Outcome.Failure -> return AgentReply.failure("stop", s.diagnostics)
            is Outcome.Success -> s.value
        }
        if (doc.state == RunState.EXITED) return AgentReply("stop", ok = true, run = doc.status(last), stop = StopResult(false, emptyList()))
        val supervisor = doc.supervisorPid?.let { ProcessTree.find(it, doc.supervisorStartedAt) }
        if (supervisor != null) {
            store.write(STOP_KEY, JsonObject.of("graceSeconds" to JsonNumber.of(grace.inWholeSeconds.coerceAtLeast(1))))
            val waited = runCatching {
                supervisor.onExit().get((grace + SUPERVISOR_EXTRA).inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
            }.isSuccess
            if (waited) {
                val after = observe(id)
                if (after is Outcome.Success) return AgentReply("stop", ok = true, run = after.value.first.status(after.value.second), stop = StopResult(false, emptyList()))
            }
            // Супервизор не уложился — останавливается он сам вместе со своим деревом.
            val r = ProcessTree.stop(supervisor, grace, clock)
            return stopLost(id, doc, last, r, store)
        }
        // Супервизор потерян: процесс останавливается по идентификатору и времени старта.
        val child = doc.pid?.let { ProcessTree.find(it, doc.pidStartedAt) }
        val r = child?.let { ProcessTree.stop(it, grace, clock) } ?: ProcessTree.Result(false, emptyList())
        return stopLost(id, doc, last, r, store)
    }

    private fun stopLost(id: SessionId, doc: RunDoc, last: Long, r: ProcessTree.Result, store: DocumentStore): AgentReply {
        val exited = doc.copy(state = RunState.EXITED, reason = ExitReason.STOPPED)
        // Супервизор мёртв — журнал без писателя; выход записывает stop.
        EventJournal.open(vfs, journalPath(id)).valueOrNull()?.let { journal ->
            val recorder = EventRecorder(ids, clock).apply { resumeFrom(journal.records()) }
            journal.append(recorder.envelope(EventType.TARGET_PROCESS_EXITED, StreamRef.of(id), clock.now(), exitPayload(null, ExitReason.STOPPED, emptyList())))
        }
        store.write(RUN_KEY, exited.toJson())
        val diags = if (r.survivors.isEmpty()) {
            emptyList()
        } else {
            listOf(Diagnostic.error(DiagCode.PROCESS_ORPHANS_LEFT, "после остановки живы ${r.survivors.joinToString()}", "run $id"))
        }
        val lastNow = EventJournal.readCommitted(vfs, journalPath(id)).valueOrNull()?.lastOrNull()?.event?.sequence ?: last
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
            memoryFreeBytes = os?.freeMemorySize,
            claude = probeTool(listOf("claude", "--version")),
            git = probeTool(listOf("git", "--version")),
        )
        return AgentReply("doctor", ok = true, doctor = report)
    }

    private fun probeTool(argv: List<String>): ToolProbe {
        val windows = Detacher.isWindows()
        var r = host.run(ProcessSpec(argv), TOOL_TIMEOUT)
        // npm ставит на Windows `claude.cmd`: без расширения его находит только cmd.exe.
        if (windows && r is Outcome.Failure && r.errors.any { it.code == DiagCode.PROCESS_START_FAILED }) {
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

    /**
     * Документ запуска и номер последнего события. Запуск, супервизор
     * которого умер, не записав выхода, — `LOST`: что с процессом, решает
     * сверка (D-13).
     */
    private fun observe(id: SessionId, knownLast: Long? = null): Outcome<Pair<RunDoc, Long>> {
        val doc = when (val r = docs(id).read(RUN_KEY)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value?.let { RunDoc.read(it.content) }
                ?: return Outcome.failure(DiagCode.ENTITY_NOT_FOUND, "нет такого запуска", "run $id")
        }
        val last = knownLast ?: EventJournal.readCommitted(vfs, journalPath(id)).valueOrNull()?.lastOrNull()?.event?.sequence ?: 0
        if (doc.state == RunState.EXITED) return Outcome.success(doc to last)
        val supervisorAlive = doc.supervisorPid?.let { ProcessTree.find(it, doc.supervisorStartedAt) != null }
        val lost = when (supervisorAlive) {
            true -> false
            false -> true
            null -> clock.now() - doc.createdAt > START_GRACE
        }
        return Outcome.success((if (lost) doc.copy(state = RunState.LOST) else doc) to last)
    }

    private fun exitPayload(code: Int?, reason: ExitReason, diags: List<Diagnostic>): JsonObject {
        val f = linkedMapOf<String, JsonValue>("reason" to JsonString(reason.name))
        code?.let { f["exitCode"] = JsonNumber.of(it) }
        if (diags.isNotEmpty()) f["diagnostics"] = DiagnosticJson.writeAll(diags)
        return JsonObject(f)
    }

    companion object {
        const val RUN_KEY = "run"
        const val STOP_KEY = "stop-request"
        private val TOOL_TIMEOUT = 20.seconds
        private val SUPERVISOR_EXTRA = 10.seconds

        /** Сколько ждать, пока супервизор запишет себя, прежде чем считать запуск потерянным. */
        val START_GRACE: Duration = 60.seconds
    }
}

/** Документ запуска `dpai.agent-run` v1 (`docs/DATA_MODEL.md`, §4). */
internal data class RunDoc(
    val id: SessionId,
    val spec: RunSpec,
    val createdAt: Timestamp,
    val state: RunState,
    val supervisorPid: Long? = null,
    val supervisorStartedAt: Long? = null,
    val pid: Long? = null,
    val pidStartedAt: Long? = null,
    val exitCode: Int? = null,
    val reason: ExitReason? = null,
) {
    fun status(lastSequence: Long): RunStatus = RunStatus(id, state, pid, exitCode, reason, lastSequence)

    fun toJson(): JsonObject {
        val f = linkedMapOf<String, JsonValue>(*FormatHeader.write(FORMAT, VERSION))
        f["id"] = JsonString(id.value)
        f["command"] = JsonArray(spec.command.map(::JsonString))
        spec.workDir?.let { f["workDir"] = JsonString(it) }
        f["setEnv"] = JsonObject(spec.setEnv.toSortedMap().mapValues { JsonString(it.value) })
        f["unsetEnv"] = JsonArray(spec.unsetEnv.map(::JsonString))
        spec.timeout?.let { f["timeoutSeconds"] = JsonNumber.of(it.inWholeSeconds) }
        f["createdAt"] = JsonNumber.of(createdAt.epochMillis)
        f["state"] = JsonString(state.name)
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
        const val VERSION = 1

        fun read(value: JsonObject): RunDoc? {
            val d = DiagnosticCollector()
            val f = JsonFields(value, "$", d)
            FormatHeader.read(f, FORMAT, VERSION..VERSION, d) ?: return null
            val id = f.string("id")?.let { com.dpsmpx.dpai.core.ids.IdKinds.SESSION.parse(it).valueOrNull() }
            val command = f.array("command")?.items?.map { (it as? JsonString)?.value }
            val workDir = f.optString("workDir")
            val setEnv = (f.raw("setEnv") as? JsonObject)?.fields?.mapValues { (it.value as? JsonString)?.value }
            val unset = f.array("unsetEnv")?.items?.map { (it as? JsonString)?.value }
            val timeout = f.optLong("timeoutSeconds")?.seconds
            val created = f.long("createdAt")?.let(::Timestamp)
            val state = f.string("state")?.let { s -> RunState.entries.firstOrNull { it.name == s } }
            val supervisorPid = f.optLong("supervisorPid")
            val supervisorStartedAt = f.optLong("supervisorStartedAt")
            val pid = f.optLong("pid")
            val pidStartedAt = f.optLong("pidStartedAt")
            val exitCode = f.optLong("exitCode")?.toInt()
            val reason = f.optString("reason")?.let { s -> ExitReason.entries.firstOrNull { it.name == s } }
            f.finish()
            if (id == null || command == null || command.any { it == null } || command.isEmpty() || setEnv == null ||
                setEnv.values.any { it == null } || unset == null || unset.any { it == null } || created == null || state == null
            ) {
                return null
            }
            val spec = runCatching {
                RunSpec(command.filterNotNull(), workDir, setEnv.mapValues { it.value!! }, unset.filterNotNull(), timeout)
            }.getOrNull() ?: return null
            return RunDoc(id, spec, created, state, supervisorPid, supervisorStartedAt, pid, pidStartedAt, exitCode, reason)
        }
    }
}
