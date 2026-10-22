package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.ssh.SshProcessHost
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Откуда канал запуска берёт события: агент на target или подделка в тестах. */
fun interface RunEventSource {
    /** События запуска [run] с номерами больше [after], не больше [limit], и состояние запуска. */
    fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply>

    /**
     * Раннер сменил ход запуска [run] (начал новый, остановил): то, что
     * источник знает о запуске, устарело — следующий ответ берётся у агента.
     */
    fun reset(run: SessionId) {}
}

/** Поток событий запуска (D-62): долгий вызов `follow` агента или подделка в тестах. */
fun interface RunFollower {
    /**
     * Строки потока запуска [run] после номера [after] — в [onReply] по мере
     * прихода, из потока чтения; конец потока — выход процесса.
     */
    fun follow(run: SessionId, after: Long, onReply: (Outcome<AgentReply>) -> Unit): Outcome<RunningProcess>
}

/**
 * Вызовы агента `dpai agent …` на target (D-26): через [host] — локальный
 * или SSH. Каждый вызов короткий и отвечает одной строкой
 * `dpai.agent-reply`; долгий процесс живёт под супервизором агента, а не
 * под этим вызовом, поэтому обрыв SSH его не убивает (ТЗ, §56).
 */
class AgentClient(
    private val host: ProcessHost,
    private val agentCommand: List<String>,
    private val home: String?,
    private val subject: String,
    private val callTimeout: Duration = 60.seconds,
) : RunEventSource, RunFollower {
    /**
     * Запуск или следующий ход того же запуска (у завершённого запуска —
     * новый ход в том же журнале, номера событий продолжаются).
     * Спецификация — на стандартный вход агента (`--spec-stdin`).
     */
    fun start(run: SessionId, spec: RunSpec): Outcome<RunStatus> =
        call("start", runArgs(run) + "--spec-stdin", stdin = CanonicalJson.write(spec.toJson())).then { r -> require(r, "run") { it.run } }

    fun status(run: SessionId): Outcome<RunStatus> = call("status", runArgs(run)).then { r -> require(r, "run") { it.run } }

    override fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply> =
        call("events", runArgs(run) + listOf("--after", after.toString(), "--limit", limit.toString()))

    /**
     * Поток `follow` (D-62, D-65): долгий канал `exec`, строка ответа — на
     * каждую новость журнала запуска. Строка, которая не читается как
     * ответ, — отказом в [onReply]; вывод ошибок не читается — исход потока
     * виден по коду выхода.
     */
    override fun follow(run: SessionId, after: Long, onReply: (Outcome<AgentReply>) -> Unit): Outcome<RunningProcess> =
        host.start(ProcessSpec(agentCommand + listOf("agent", "follow") + runArgs(run) + listOf("--after", after.toString()))) { line ->
            if (line.stream == OutputStream.STDOUT && line.text.isNotBlank()) {
                onReply(
                    if (line.truncated) {
                        Outcome.failure(DiagCode.AGENT_REPLY_INVALID, "строка потока длиннее предела — обрезана", subject)
                    } else {
                        AgentReply.parseOutput(listOf(line.text), subject)
                    },
                )
            }
        }

    fun stop(run: SessionId, grace: Duration = 5.seconds): Outcome<AgentReply> =
        call("stop", runArgs(run) + listOf("--grace-seconds", grace.inWholeSeconds.coerceAtLeast(1).toString()))

    fun doctor(): Outcome<DoctorReport> = call("doctor", homeArgs()).then { r -> require(r, "doctor") { it.doctor } }

    private fun homeArgs(): List<String> = home?.let { listOf("--home", it) }.orEmpty()

    private fun runArgs(run: SessionId): List<String> = homeArgs() + listOf("--run", run.value)

    private fun call(command: String, args: List<String>, stdin: String? = null): Outcome<AgentReply> {
        val argv = agentCommand + listOf("agent", command) + args
        return host.run(ProcessSpec(argv, stdin = stdin), callTimeout).then { done ->
            SshProcessHost.classify(done, subject)?.let { return@then Outcome.failure(it) }
            when (val reply = AgentReply.parseOutput(done.stdout, subject)) {
                is Outcome.Success -> reply.value.toOutcome()
                is Outcome.Failure ->
                    if (done.ok) {
                        reply
                    } else {
                        val tail = done.stderr.lastOrNull { it.isNotBlank() }?.take(300) ?: "без вывода ошибок"
                        Outcome.Failure(
                            listOf(Diagnostic.of(DiagCode.PROCESS_EXIT_NONZERO, "агент: код ${done.exitCode}: $tail", subject, causes = reply.errors)),
                        )
                    }
            }
        }
    }

    private inline fun <T> require(reply: AgentReply, field: String, get: (AgentReply) -> T?): Outcome<T> =
        get(reply)?.let { Outcome.success(it, reply.diagnostics.filterNot { d -> d.isError }) }
            ?: Outcome.failure(DiagCode.AGENT_REPLY_INVALID, "в ответе агента нет поля $field", subject)
}
