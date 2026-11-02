package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Канал к агенту в процессе приложения (D-75, шаг 4): target «в
 * приложении» без SSH. `AgentClient` шлёт те же вызовы `dpai agent …`,
 * что и по SSH, — здесь они исполняются агентом [runtime] этого же
 * процесса: ответ — строками stdout, код выхода — как у `dpai agent`
 * (0 — успех, 1 — отказ, 2 — неверный вызов). Вызов идёт в своём потоке:
 * `run` ждёт его со своим сроком, а поток `follow` кончается, когда
 * читатель остановил «процесс».
 *
 * Супервизор хода — поток агента ([ThreadSupervision]), поэтому команды
 * `supervise` снаружи нет; `mcp` — сервер рабочего дерева на target
 * (D-50), в приложении его нет.
 */
class InAppAgentHost(private val runtime: AgentRuntime, home: String) : ProcessHost {
    private val home: String = File(home).absolutePath

    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        val at = spec.command.indexOf("agent")
        val command = spec.command.getOrNull(at + 1)?.takeIf { at >= 0 }
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "не вызов агента: ${spec.command.take(3)}", SUBJECT)
        if (command !in AgentCommands.SINGLE_REPLY && command != "follow" || command == "supervise") {
            return Outcome.failure(DiagCode.UNSUPPORTED, "агент в приложении не исполняет «$command»", SUBJECT)
        }
        val args = AgentArgs.parse(spec.command.drop(at + 2))
            ?: return Outcome.failure(DiagCode.VALUE_INVALID, "неверные параметры агента", SUBJECT)
        args.single("home")?.let { h ->
            if (File(h).absolutePath != home) return Outcome.failure(DiagCode.VALUE_INVALID, "каталог агента в приложении — $home, а не $h", SUBJECT)
        }
        val call = Call(onOutput)
        Thread({ call.finish(execute(command, args, spec.stdin.orEmpty(), call)) }, "dpai-agent-$command").apply { isDaemon = true }.start()
        return Outcome.success(call)
    }

    /** Код выхода, как у `dpai agent`; строки ответа — в [call]. */
    private fun execute(command: String, args: AgentArgs, stdin: String, call: Call): Int {
        if (command == "follow") {
            val run = args.single("run")?.let { IdKinds.SESSION.parse(it).valueOrNull() } ?: return call.usage("--run <SessionId> обязателен")
            val after = args.single("after")?.let { it.toLongOrNull() ?: return call.usage("--after — номер события") } ?: 0
            val ok = runtime.follow(run, after) { reply -> call.reply(CanonicalJson.write(reply.toJson())) }
            return if (ok) OK else FAILED
        }
        val reply = AgentCommands.reply(runtime, command, args, { stdin }) ?: return call.usage("неверные параметры команды $command")
        call.reply(CanonicalJson.write(reply.toJson()))
        return if (reply.ok) OK else FAILED
    }

    /** Вызов агента как «процесс»: остановка закрывает вывод — поток `follow` видит, что читатель ушёл. */
    private class Call(private val onOutput: (OutputLine) -> Unit) : RunningProcess {
        private val done = CountDownLatch(1)

        @Volatile
        private var closed = false

        @Volatile
        private var code: Int? = null

        /** Строка ответа; `false` — читатель ушёл (как запись в закрытый канал SSH). */
        fun reply(line: String): Boolean {
            if (closed) return false
            onOutput(OutputLine(OutputStream.STDOUT, line))
            return !closed
        }

        fun usage(why: String): Int {
            onOutput(OutputLine(OutputStream.STDERR, why))
            return USAGE_ERROR
        }

        fun finish(exit: Int) {
            code = exit
            done.countDown()
        }

        override val pid: Long? = null

        override val isAlive: Boolean get() = done.count > 0

        override fun awaitExit(timeout: Duration): Int? =
            if (done.await(timeout.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)) code else null

        /**
         * Вызов в потоке не убить: вывод закрывается, `follow` кончается на
         * следующей строке (признак жизни — не реже 15 с), короткий вызов —
         * сам. Не уложился в [grace] — `forced`, кода нет.
         */
        override fun stop(grace: Duration): StopReport {
            closed = true
            val exited = done.await(grace.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            return StopReport(code, forced = !exited, survivors = emptyList())
        }
    }

    companion object {
        private const val OK = 0
        private const val FAILED = 1
        private const val USAGE_ERROR = 2
        private const val SUBJECT = "агент в приложении"

        /**
         * Агент target «в приложении» (D-75): процессы хода — [guest]
         * (Alpine под proot поверх `NativeProcessHost`), супервизор хода —
         * поток, опознание и остановка процессов — по `/proc` через
         * [native]; память для `doctor` — из `/proc/meminfo`. Окружение
         * процессов хода строится от [guestEnvironment] (гостя, а не
         * Android); `null` — от окружения процесса (тесты на JVM).
         */
        fun create(home: String, clock: Clock, version: String, native: NativeProcesses, guest: ProcessHost, guestEnvironment: Map<String, String>? = null): InAppAgentHost =
            InAppAgentHost(
                AgentRuntime(
                    home, clock, version, selfCommand = emptyList(), host = guest,
                    jobHelper = null, supervision = ThreadSupervision(native, clock), osFacts = { OsFacts.NONE },
                    baseEnvironment = guestEnvironment?.let { e -> { e } } ?: System::getenv,
                ),
                home,
            )
    }
}
