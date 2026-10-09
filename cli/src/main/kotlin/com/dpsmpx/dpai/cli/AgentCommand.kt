package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.RunSpec
import com.dpsmpx.dpai.orchestrator.agent.AgentRuntime
import com.dpsmpx.dpai.orchestrator.agent.WorkspaceMcp
import java.io.File
import kotlin.time.Duration.Companion.seconds

/**
 * `dpai agent …` — агент оркестратора на target (D-26;
 * `docs/EXECUTION_MODEL.md`, §1). Каждая команда отвечает одной строкой
 * `dpai.agent-reply` на стандартном выводе; код выхода: 0 — успех, 1 —
 * отказ (подробности в ответе), 2 — неверный вызов. Исключение — `mcp`:
 * сервер MCP, отвечающий строками JSON-RPC, пока открыт вход (D-50).
 */
class AgentCommand(
    private val out: (String) -> Unit,
    private val err: (String) -> Unit,
    private val version: String,
    private val clock: Clock = Clock.SYSTEM,
    private val selfCommand: List<String> = selfCommand(),
    private val stdin: () -> String = { System.`in`.readBytes().toString(Charsets.UTF_8) },
    private val input: () -> java.io.BufferedReader = { System.`in`.bufferedReader(Charsets.UTF_8) },
) {
    fun run(args: List<String>): Int {
        val command = args.firstOrNull() ?: return usage("нет команды агента")
        val opts = Options.parse(args.drop(1)) ?: return usage("неверные параметры")
        if (command == "mcp") return mcp(opts)
        val home = opts.single("home") ?: defaultHome()
        val runtime = AgentRuntime(home, clock, version, selfCommand)
        val reply = when (command) {
            "doctor" -> runtime.doctor()
            "start", "supervise", "status", "events", "stop" -> {
                val run = opts.single("run")?.let { IdKinds.SESSION.parse(it).valueOrNull() } ?: return usage("--run <SessionId> обязателен")
                runFor(command, run, opts, runtime) ?: return usage("неверные параметры команды $command")
            }
            else -> return usage("неизвестная команда агента: $command")
        }
        out(CanonicalJson.write(reply.toJson()))
        return if (reply.ok) Cli.OK else FAILED
    }

    private fun runFor(command: String, run: SessionId, opts: Options, runtime: AgentRuntime): AgentReply? = when (command) {
        "start" -> {
            if (opts.flag("spec-stdin")) {
                when (val spec = RunSpec.parse(stdin())) {
                    is Outcome.Success -> runtime.start(run, spec.value)
                    is Outcome.Failure -> AgentReply.failure("start", spec.diagnostics)
                }
            } else if (opts.tail.isEmpty()) {
                null
            } else {
                val env = opts.all("env").map { it.substringBefore('=') to it.substringAfter('=', "") }
                val spec = runCatching {
                    RunSpec(
                        command = opts.tail,
                        workDir = opts.single("dir"),
                        setEnv = env.toMap(),
                        unsetEnv = opts.all("unset"),
                        timeout = opts.single("timeout-seconds")?.toLongOrNull()?.seconds,
                    )
                }.getOrNull()
                spec?.let { runtime.start(run, it) }
                    ?: AgentReply.failure("start", listOf(Diagnostic.error(DiagCode.VALUE_INVALID, "параметры запуска недопустимы")))
            }
        }
        "supervise" -> runtime.supervise(run)
        "status" -> runtime.status(run)
        "events" -> {
            val after = opts.single("after")?.toLongOrNull() ?: 0
            val limit = opts.single("limit")?.toIntOrNull() ?: 500
            runtime.events(run, after, limit)
        }
        "stop" -> runtime.stop(run, (opts.single("grace-seconds")?.toLongOrNull() ?: 5).seconds)
        else -> null
    }

    /**
     * Рабочее дерево по MCP для хода Claude на другом target (D-50): строки
     * JSON-RPC на входе и выходе, пока вход не закрыт.
     */
    private fun mcp(opts: Options): Int {
        val root = opts.single("root") ?: return usage("--root <каталог> обязателен")
        val dir = File(root)
        if (!dir.isDirectory) {
            err("нет каталога $root")
            return FAILED
        }
        val shell = WorkspaceMcp.shellNamed(opts.single("shell"), File.separatorChar == '\\') ?: return usage("--shell: bash, sh, powershell или cmd")
        WorkspaceMcp(dir.toPath(), shell, version).serve(input(), out)
        return Cli.OK
    }

    private fun usage(why: String): Int {
        err(why)
        err(USAGE)
        return Cli.USAGE_ERROR
    }

    /** Параметры `--имя значение` и флаги `--имя`; всё после `--` — команда запуска. */
    private class Options(private val values: Map<String, List<String>>, private val flags: Set<String>, val tail: List<String>) {
        fun flag(name: String): Boolean = name in flags

        fun single(name: String): String? = values[name]?.lastOrNull()

        fun all(name: String): List<String> = values[name].orEmpty()

        companion object {
            private val KNOWN = setOf("home", "run", "dir", "env", "unset", "timeout-seconds", "after", "limit", "grace-seconds", "root", "shell")
            private val FLAGS = setOf("spec-stdin")

            fun parse(args: List<String>): Options? {
                val values = LinkedHashMap<String, MutableList<String>>()
                val flags = mutableSetOf<String>()
                var i = 0
                while (i < args.size) {
                    val a = args[i]
                    if (a == "--") return Options(values, flags, args.drop(i + 1))
                    if (!a.startsWith("--")) return null
                    val name = a.removePrefix("--")
                    if (name in FLAGS) {
                        flags += name
                        i += 1
                        continue
                    }
                    if (name !in KNOWN || i + 1 >= args.size) return null
                    values.getOrPut(name) { mutableListOf() } += args[i + 1]
                    i += 2
                }
                return Options(values, flags, emptyList())
            }
        }
    }

    companion object {
        const val FAILED = 1

        val USAGE = """
            |dpai agent — агент оркестратора на target (D-26)
            |
            |  dpai agent doctor   [--home DIR]
            |  dpai agent start    --run ID [--home DIR] [--dir DIR] [--timeout-seconds N]
            |                      [--env NAME=VALUE]... [--unset NAME]... -- КОМАНДА…
            |  dpai agent start    --run ID [--home DIR] --spec-stdin   (dpai.run-spec на входе)
            |  dpai agent status   --run ID [--home DIR]
            |  dpai agent events   --run ID [--home DIR] [--after N] [--limit N]
            |  dpai agent stop     --run ID [--home DIR] [--grace-seconds N]
            |  dpai agent mcp      --root DIR [--shell bash|sh|powershell|cmd]
            |                      (MCP-сервер рабочего дерева по stdio, D-50)
            |
            |Ответ — одна строка JSON dpai.agent-reply. Коды выхода: 0 — успех, 1 — отказ, 2 — неверный вызов.
        """.trimMargin()

        fun defaultHome(): String = File(System.getProperty("user.home"), ".dpai-agent").path

        /** Как запустить эту же программу: супервизор — тот же `dpai` отдельным процессом. */
        fun selfCommand(): List<String> {
            val exe = if (File.separatorChar == '\\') "java.exe" else "java"
            val java = File(File(System.getProperty("java.home"), "bin"), exe).path
            return listOf(java, "-cp", System.getProperty("java.class.path"), "com.dpsmpx.dpai.cli.MainKt")
        }
    }
}
