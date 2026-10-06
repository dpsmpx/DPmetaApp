package com.dpsmpx.dpai.execution.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.result.then
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.ssh.SshProcessHost
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Что запустить под агентом. Окружение — правка окружения агента:
 * [unsetEnv] удаляются, [setEnv] задаются (D-29: окружение слота).
 * Значения [setEnv] идут аргументами командной строки, поэтому секретов в
 * них нет (D-14): `CLAUDE_CONFIG_DIR` — путь, а не учётные данные (D-27).
 */
data class RunSpec(
    val command: List<String>,
    val workDir: String? = null,
    val setEnv: Map<String, String> = emptyMap(),
    val unsetEnv: List<String> = emptyList(),
    val timeout: Duration? = null,
) {
    init {
        require(command.isNotEmpty()) { "пустая команда" }
        require(setEnv.keys.all { ENV_NAME.matches(it) } && unsetEnv.all { ENV_NAME.matches(it) }) { "имя переменной окружения недопустимо" }
        require(timeout == null || timeout.isPositive()) { "тайм-аут — положительный" }
    }

    /** Аргументы `dpai agent start` после `--home` и `--run`. */
    fun toArgs(): List<String> = buildList {
        workDir?.let { add("--dir"); add(it) }
        timeout?.let { add("--timeout-seconds"); add(it.inWholeSeconds.coerceAtLeast(1).toString()) }
        for ((k, v) in setEnv.toSortedMap()) { add("--env"); add("$k=$v") }
        for (k in unsetEnv.sorted()) { add("--unset"); add(k) }
        add("--")
        addAll(command)
    }

    companion object {
        val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
    }
}

/** Откуда канал запуска берёт события: агент на target или подделка в тестах. */
fun interface RunEventSource {
    /** События запуска [run] с номерами больше [after], не больше [limit], и состояние запуска. */
    fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply>
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
) : RunEventSource {
    fun start(run: SessionId, spec: RunSpec): Outcome<RunStatus> =
        call("start", runArgs(run) + spec.toArgs()).then { r -> require(r, "run") { it.run } }

    fun status(run: SessionId): Outcome<RunStatus> = call("status", runArgs(run)).then { r -> require(r, "run") { it.run } }

    override fun events(run: SessionId, after: Long, limit: Int): Outcome<AgentReply> =
        call("events", runArgs(run) + listOf("--after", after.toString(), "--limit", limit.toString()))

    fun stop(run: SessionId, grace: Duration = 5.seconds): Outcome<AgentReply> =
        call("stop", runArgs(run) + listOf("--grace-seconds", grace.inWholeSeconds.coerceAtLeast(1).toString()))

    fun doctor(): Outcome<DoctorReport> = call("doctor", homeArgs()).then { r -> require(r, "doctor") { it.doctor } }

    private fun homeArgs(): List<String> = home?.let { listOf("--home", it) }.orEmpty()

    private fun runArgs(run: SessionId): List<String> = homeArgs() + listOf("--run", run.value)

    private fun call(command: String, args: List<String>): Outcome<AgentReply> {
        val argv = agentCommand + listOf("agent", command) + args
        return host.run(ProcessSpec(argv), callTimeout).then { done ->
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
