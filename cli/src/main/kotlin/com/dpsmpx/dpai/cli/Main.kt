package com.dpsmpx.dpai.cli

import kotlin.system.exitProcess

/**
 * Точка входа `dpai` — оболочка JVM над ядром (D-02).
 *
 * Сейчас — версия и справка (этап 0). `doctor`, сценарии на MockRunner и
 * режим без интерфейса появляются вместе с подсистемами (этапы 3–14).
 */
fun main(args: Array<String>) {
    val code = Cli(out = { println(it) }, err = { System.err.println(it) }).run(args.toList())
    exitProcess(code)
}

/**
 * Разбор команды и коды выхода: 0 — успех, 2 — неверный вызов. Вывод — через
 * переданные функции, чтобы команду проверяли тесты без процесса.
 */
class Cli(
    private val out: (String) -> Unit,
    private val err: (String) -> Unit,
    private val version: String = Cli::class.java.`package`?.implementationVersion ?: "dev",
) {
    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "version", "--version" -> {
            out("dpai $version")
            OK
        }
        "help", "--help", "-h" -> {
            out(USAGE)
            OK
        }
        null -> {
            err(USAGE)
            USAGE_ERROR
        }
        else -> {
            err("неизвестная команда: ${args.first()}")
            err(USAGE)
            USAGE_ERROR
        }
    }

    companion object {
        const val OK = 0
        const val USAGE_ERROR = 2

        val USAGE = """
            |dpai — DP_AI_Orcestrator (docs/direction/ROADMAP/README.md)
            |
            |  dpai version   версия
            |  dpai help      эта справка
            |
            |Коды выхода: 0 — успех, 2 — неверный вызов.
        """.trimMargin()
    }
}
