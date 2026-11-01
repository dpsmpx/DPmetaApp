package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.serialization.CanonicalJson
import com.dpsmpx.dpai.orchestrator.agent.AgentArgs
import com.dpsmpx.dpai.orchestrator.agent.AgentCommands
import com.dpsmpx.dpai.orchestrator.agent.AgentRuntime
import com.dpsmpx.dpai.orchestrator.agent.WorkspaceMcp
import java.io.File

/**
 * `dpai agent …` — агент оркестратора на target (D-26;
 * `docs/EXECUTION_MODEL.md`, §1). Каждая команда отвечает одной строкой
 * `dpai.agent-reply` на стандартном выводе; код выхода: 0 — успех, 1 —
 * отказ (подробности в ответе), 2 — неверный вызов. Исключения — `follow`:
 * строки ответа потоком, пока идёт ход (D-76, D-79), и `mcp`: сервер MCP,
 * отвечающий строками JSON-RPC, пока открыт вход (D-50).
 */
class AgentCommand(
    private val out: (String) -> Unit,
    private val err: (String) -> Unit,
    private val version: String,
    private val clock: Clock = Clock.SYSTEM,
    private val selfCommand: List<String> = selfCommand(),
    private val stdin: () -> String = { System.`in`.readBytes().toString(Charsets.UTF_8) },
    private val input: () -> java.io.BufferedReader = { System.`in`.bufferedReader(Charsets.UTF_8) },
    /** Вывод закрыт — читатель потока `follow` ушёл (обрыв канала SSH). */
    private val outClosed: () -> Boolean = { false },
) {
    fun run(args: List<String>): Int {
        val command = args.firstOrNull() ?: return usage("нет команды агента")
        val opts = AgentArgs.parse(args.drop(1)) ?: return usage("неверные параметры")
        if (command == "mcp") return mcp(opts)
        // Абсолютный (ревью A-18): супервизор запускается с рабочим каталогом home, и относительный
        // путь он искал бы в home/home — каждый ход становился бы LOST.
        val home = File(opts.single("home") ?: defaultHome()).absolutePath
        val runtime = AgentRuntime(home, clock, version, selfCommand)
        if (command == "follow") {
            val run = opts.single("run")?.let { IdKinds.SESSION.parse(it).valueOrNull() } ?: return usage("--run <SessionId> обязателен")
            val after = opts.single("after")?.let { it.toLongOrNull() ?: return usage("--after — номер события") } ?: 0
            val ok = runtime.follow(run, after) { reply ->
                out(CanonicalJson.write(reply.toJson()))
                !outClosed()
            }
            return if (ok) Cli.OK else FAILED
        }
        if (command !in AgentCommands.SINGLE_REPLY) return usage("неизвестная команда агента: $command")
        if (command != "doctor" && opts.single("run")?.let { IdKinds.SESSION.parse(it).valueOrNull() } == null) return usage("--run <SessionId> обязателен")
        val reply = AgentCommands.reply(runtime, command, opts, stdin) ?: return usage("неверные параметры команды $command")
        out(CanonicalJson.write(reply.toJson()))
        return if (reply.ok) Cli.OK else FAILED
    }

    /**
     * Рабочее дерево по MCP для хода Claude на другом target (D-50): строки
     * JSON-RPC на входе и выходе, пока вход не закрыт.
     */
    private fun mcp(opts: AgentArgs): Int {
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
            |  dpai agent follow   --run ID [--home DIR] [--after N]
            |                      (строки ответа потоком, пока идёт ход, D-76)
            |  dpai agent stop     --run ID [--home DIR] [--grace-seconds N]
            |  dpai agent mcp      --root DIR [--shell bash|sh|powershell|cmd]
            |                      (MCP-сервер рабочего дерева по stdio, D-50)
            |
            |Ответ — одна строка JSON dpai.agent-reply (follow — строка на каждую новость).
            |Коды выхода: 0 — успех, 1 — отказ, 2 — неверный вызов.
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
