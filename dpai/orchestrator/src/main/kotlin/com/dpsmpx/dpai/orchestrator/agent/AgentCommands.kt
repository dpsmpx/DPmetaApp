package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.diagnostics.Diagnostic
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.AgentReply
import com.dpsmpx.dpai.execution.agent.RunSpec
import kotlin.time.Duration.Companion.seconds

/**
 * Параметры команды агента `dpai agent …`: `--имя значение`, флаги
 * `--имя`; всё после `--` — команда запуска ([tail]). Один разбор у
 * `dpai agent` на target и у агента в процессе приложения (D-75):
 * канал без SSH понимает те же вызовы `AgentClient`.
 */
class AgentArgs private constructor(private val values: Map<String, List<String>>, private val flags: Set<String>, val tail: List<String>) {
    fun flag(name: String): Boolean = name in flags

    fun single(name: String): String? = values[name]?.lastOrNull()

    fun all(name: String): List<String> = values[name].orEmpty()

    companion object {
        private val KNOWN = setOf("home", "run", "dir", "env", "unset", "timeout-seconds", "after", "limit", "grace-seconds", "root", "shell")
        private val FLAGS = setOf("spec-stdin")

        /** Неизвестный параметр, параметр без значения или слово без `--` — `null`. */
        fun parse(args: List<String>): AgentArgs? {
            val values = LinkedHashMap<String, MutableList<String>>()
            val flags = mutableSetOf<String>()
            var i = 0
            while (i < args.size) {
                val a = args[i]
                if (a == "--") return AgentArgs(values, flags, args.drop(i + 1))
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
            return AgentArgs(values, flags, emptyList())
        }
    }
}

/** Ответ на команду агента, кроме долгих `follow` и `mcp`: одна строка `dpai.agent-reply`. */
object AgentCommands {
    /** Команды с ответом одной строкой. */
    val SINGLE_REPLY: Set<String> = setOf("profiles", "fence-stop", "doctor", "start", "supervise", "status", "events", "stop")

    /**
     * Ответ [runtime] на [command] с параметрами [args]; [stdin] — вход
     * вызова (спецификация `--spec-stdin`). Неверный вызов (нет `--run`,
     * незнакомая команда, нет команды запуска) — `null`: вызывающий
     * отвечает подсказкой.
     */
    fun reply(runtime: AgentRuntime, command: String, args: AgentArgs, stdin: () -> String): AgentReply? {
        if (command == "profiles") return AgentReply("profiles", ok = true)
        if (command == "doctor") return runtime.doctor()
        if (command !in SINGLE_REPLY) return null
        val run = args.single("run")?.let { IdKinds.SESSION.parse(it).valueOrNull() } ?: return null
        return when (command) {
            "start" -> when {
                args.flag("spec-stdin") -> when (val spec = RunSpec.parse(stdin())) {
                    is Outcome.Success -> runtime.start(run, spec.value)
                    is Outcome.Failure -> AgentReply.failure("start", spec.diagnostics)
                }
                args.tail.isEmpty() -> null
                else -> {
                    val env = args.all("env").map { it.substringBefore('=') to it.substringAfter('=', "") }
                    val spec = runCatching {
                        RunSpec(
                            command = args.tail,
                            workDir = args.single("dir"),
                            setEnv = env.toMap(),
                            unsetEnv = args.all("unset"),
                            timeout = args.single("timeout-seconds")?.toLongOrNull()?.seconds,
                        )
                    }.getOrNull()
                    spec?.let { runtime.start(run, it) }
                        ?: AgentReply.failure("start", listOf(Diagnostic.error(DiagCode.VALUE_INVALID, "параметры запуска недопустимы")))
                }
            }
            "supervise" -> runtime.supervise(run)
            "status" -> runtime.status(run)
            "events" -> runtime.events(run, args.single("after")?.toLongOrNull() ?: 0, args.single("limit")?.toIntOrNull() ?: 500)
            "fence-stop" -> runtime.fenceStop(run)
            "stop" -> runtime.stop(run, (args.single("grace-seconds")?.toLongOrNull() ?: 5).seconds)
            else -> null
        }
    }
}
