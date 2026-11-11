package com.dpsmpx.dpai.cli

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.tools.SharedTools
import com.dpsmpx.dpai.orchestrator.agent.SharedToolsStore
import com.dpsmpx.dpai.storage.vfs.NioVfs
import java.io.File

/** Локальная настройка target (D-122). Не устанавливает зависимости и не читает ключи. */
class ToolsCommand(private val out: (String) -> Unit, private val err: (String) -> Unit) {
    fun run(args: List<String>): Int {
        val action = args.firstOrNull()
        if (action !in setOf("configure", "status", "disable")) return usage()
        val tail = args.drop(1)
        if (tail.size % 2 != 0) return usage()
        val values = linkedMapOf<String, String>()
        for ((name, value) in tail.chunked(2)) {
            if (name !in setOf("--home", "--repo", "--controller", "--python") || name in values) return usage()
            values[name] = value
        }
        if (action != "configure" && values.keys.any { it != "--home" }) return usage()
        val home = File(values["--home"] ?: AgentCommand.defaultHome()).absoluteFile.toPath()
        val store = SharedToolsStore(NioVfs(home))
        val result = when (action) {
            "configure" -> {
                val repository = values["--repo"] ?: return usage()
                val controller = values["--controller"] ?: return usage()
                val value = runCatching { SharedTools(repository, controller, values["--python"] ?: "python3") }
                    .getOrElse { err(it.message ?: "неверная настройка"); return Cli.USAGE_ERROR }
                if (File.separatorChar == '\\') {
                    err("Общие инструменты пока поддерживают Linux и Termux; Windows — отдельный этап")
                    return 1
                }
                store.write(value)
            }
            "disable" -> store.disable()
            else -> when (val read = store.read()) {
                is Outcome.Failure -> read
                is Outcome.Success -> {
                    read.value?.let { out("Папка: ${it.repository}"); out("Контроллер: ${it.controller}"); out("Python: ${it.python}") }
                        ?: out("Общие инструменты: выключены")
                    return Cli.OK
                }
            }
        }
        if (result is Outcome.Failure) {
            result.diagnostics.forEach { err("${it.code}: ${it.message}") }
            return 1
        }
        out(if (action == "disable") "Общие инструменты выключены для новых ходов" else "Общие инструменты настроены для новых ходов")
        return Cli.OK
    }

    private fun usage(): Int {
        err(USAGE)
        return Cli.USAGE_ERROR
    }

    companion object {
        const val USAGE = "dpai tools configure --repo ABS --controller ABS [--python PROGRAM] [--home DIR]\n" +
            "dpai tools status|disable [--home DIR]"
    }
}
