package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.events.EventEnvelope
import com.dpsmpx.dpai.core.events.EventType
import com.dpsmpx.dpai.core.events.StreamRef
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpai.core.serialization.json
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import kotlin.time.Duration

/**
 * Target вида `MOCK` (`docs/EXECUTION_MODEL.md`, §1; ТЗ, §89): отвечает на
 * вызовы `dpai agent …` так же, как настоящий агент, но без процессов —
 * запуски живут в памяти, а их события задают тесты ([emit], [exit]).
 * Нужен, чтобы ядро проверялось целиком без target и Claude.
 *
 * [reachable] = false — target недоступен: вызовы падают, как упавший SSH.
 * [onStart] вызывается после каждого хода `start` — так сценарий
 * `MockRunner` пишет вывод «процесса».
 */
class MockAgentHost(private val clock: Clock, private val ids: IdGenerator) : ProcessHost {
    private class Run(val id: SessionId) {
        val events = mutableListOf<EventEnvelope>()
        var spec: RunSpec? = null
        var turn = 0
        var state = RunState.RUNNING
        var exitCode: Int? = null
        var reason: ExitReason? = null
    }

    /** Ход начат: запуск, его спецификация и номер хода. */
    var onStart: (SessionId, RunSpec, Int) -> Unit = { _, _, _ -> }

    private val runs = LinkedHashMap<SessionId, Run>()

    @Volatile
    var reachable: Boolean = true

    /** Сколько вызовов агента пришло (для проверок «проба не пошла»). */
    var calls: Int = 0
        private set

    @Synchronized
    fun emit(run: SessionId, type: EventType, payload: JsonObject = JsonObject(emptyMap())): EventEnvelope {
        val r = runs.getValue(run)
        val e = EventEnvelope(ids.next(IdKinds.EVENT), type, StreamRef.of(run), r.events.size + 1L, clock.now(), clock.now(), payload = payload)
        r.events += e
        return e
    }

    /** Процесс хода вышел сам с кодом [code] или по [reason] (тайм-аут агента). */
    @Synchronized
    fun exit(run: SessionId, code: Int?, reason: ExitReason = ExitReason.EXITED) = finish(runs.getValue(run), code, reason)

    /** Строка вывода процесса хода. */
    fun output(run: SessionId, text: String, stream: OutputStream = OutputStream.STDOUT): EventEnvelope =
        emit(run, EventType.TARGET_PROCESS_OUTPUT, JsonObject.of("stream" to JsonString(stream.name.lowercase()), "text" to JsonString(text), "truncated" to com.dpsmpx.dpai.core.serialization.JsonBool(false)))

    @Synchronized
    fun state(run: SessionId): RunState? = runs[run]?.state

    @Synchronized
    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        calls++
        if (!reachable) {
            return Outcome.success(Done(255, "", "ssh: connect to host mock: Connection timed out", onOutput))
        }
        val i = spec.command.indexOf("agent")
        val command = spec.command.getOrNull(i + 1)
        val args = if (i >= 0) spec.command.drop(i + 2) else emptyList()
        fun opt(name: String): String? = args.indexOf("--$name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
        val run = opt("run")?.let { IdKinds.SESSION.parse(it).valueOrNull() }
        val reply: AgentReply = when (command) {
            "doctor" -> AgentReply(
                "doctor",
                true,
                doctor = DoctorReport(
                    "mock", "Mock", "none", "17", clock.now().epochMillis, false, 10L shl 30, 8L shl 30, 4L shl 30,
                    ToolProbe(true, "mock (Claude Code)", null), ToolProbe(true, "git version mock", null),
                ),
            )
            "start" -> {
                val runSpec = if ("--spec-stdin" in args) {
                    spec.stdin?.let { RunSpec.parse(it).valueOrNull() }
                } else {
                    args.dropWhile { it != "--" }.drop(1).takeIf { it.isNotEmpty() }?.let { RunSpec(it) }
                }
                val existing = run?.let { runs[it] }
                when {
                    run == null || runSpec == null -> failure("start", DiagCode.VALUE_INVALID, "нет --run или спецификации")
                    existing != null && existing.state != RunState.EXITED -> failure("start", DiagCode.INVALID_TRANSITION, "ход ещё идёт")
                    else -> {
                        val r = existing ?: Run(run).also { runs[run] = it }
                        r.spec = runSpec
                        r.turn++
                        r.state = RunState.RUNNING
                        r.exitCode = null
                        r.reason = null
                        emit(run, EventType.TARGET_PROCESS_STARTED, JsonObject.of("program" to JsonString(runSpec.command.first()), "turn" to json(r.turn)))
                        onStart(run, runSpec, r.turn)
                        AgentReply("start", true, run = status(r))
                    }
                }
            }
            "status" -> runs[run]?.let { AgentReply("status", true, run = status(it)) } ?: failure("status", DiagCode.ENTITY_NOT_FOUND, "нет такого запуска")
            "events" -> runs[run]?.let { r ->
                val after = opt("after")?.toLongOrNull() ?: 0
                val limit = opt("limit")?.toIntOrNull() ?: 500
                AgentReply("events", true, run = status(r), events = r.events.filter { it.sequence > after }.take(limit))
            } ?: failure("events", DiagCode.ENTITY_NOT_FOUND, "нет такого запуска")
            "stop" -> runs[run]?.let { r ->
                if (r.state == RunState.RUNNING) finish(r, null, ExitReason.STOPPED)
                AgentReply("stop", true, run = status(r), stop = StopResult(false, emptyList()))
            } ?: failure("stop", DiagCode.ENTITY_NOT_FOUND, "нет такого запуска")
            else -> failure(command ?: "", DiagCode.UNSUPPORTED, "неизвестная команда агента")
        }
        return Outcome.success(Done(if (reply.ok) 0 else 1, CanonicalJson.write(reply.toJson()), "", onOutput))
    }

    private fun finish(r: Run, code: Int?, reason: ExitReason) {
        val payload = JsonObject(buildMap {
            put("reason", JsonString(reason.name))
            put("turn", json(r.turn))
            code?.let { put("exitCode", json(it)) }
        })
        emit(r.id, EventType.TARGET_PROCESS_EXITED, payload)
        r.state = RunState.EXITED
        r.exitCode = code
        r.reason = reason
    }

    private fun status(r: Run) = RunStatus(r.id, r.state, null, r.exitCode, r.reason, r.events.size.toLong(), r.turn)

    private fun failure(command: String, code: DiagCode, message: String) = AgentReply.failure(command, listOf(Diagnostic.error(code, message)))

    /** Вызов, который уже завершился: вывод отдан сразу. */
    private class Done(private val code: Int, stdout: String, stderr: String, onOutput: (OutputLine) -> Unit) : RunningProcess {
        init {
            if (stdout.isNotEmpty()) onOutput(OutputLine(OutputStream.STDOUT, stdout))
            if (stderr.isNotEmpty()) onOutput(OutputLine(OutputStream.STDERR, stderr))
        }

        override val pid: Long? = null
        override val isAlive: Boolean = false

        override fun awaitExit(timeout: Duration): Int = code

        override fun stop(grace: Duration): StopReport = StopReport(code, false, emptyList())
    }
}
