package com.dpsmpx.dpai.cli

import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * Точка входа `dpai` — оболочка JVM над ядром (D-02).
 *
 * Версия, справка (этап 0), агент target `dpai agent` (этап 3), пульт
 * приложения (D-47). Глобальный прогон экосистемы убран (D-200).
 */
fun main(args: Array<String>) {
    // Ответ агента — строки JSON в UTF-8 при любой локали target: System.out
    // кодирует в кодировку консоли или локали (POSIX — `?` вместо кириллицы,
    // русская Windows — cp1251), а контроллер читает UTF-8 (ревью 8 октября
    // 2026). Команды для человека — в кодировке его консоли, как прежде.
    val (out, err) = if (args.firstOrNull() == "agent") {
        // Внешний PrintStream кодирует в UTF-8, внутренний пропускает байты как есть.
        PrintStream(System.out, true, Charsets.UTF_8) to PrintStream(System.err, true, Charsets.UTF_8)
    } else {
        System.out to System.err
    }
    val code = Cli(out = { out.println(it) }, err = { err.println(it) }, outClosed = out::checkError).run(args.toList())
    out.flush()
    err.flush()
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
    /** Стандартный вывод закрыт: поток `dpai agent follow` кончается (D-76). */
    private val outClosed: () -> Boolean = { false },
    private val control: () -> ControlCommand = { ControlCommand(out, err) },
) {
    fun run(args: List<String>): Int = when (args.firstOrNull()) {
        "version", "--version" -> {
            out("dpai $version")
            OK
        }
        "agent" -> AgentCommand(out, err, version, outClosed = outClosed).run(args.drop(1))
        "tools" -> ToolsCommand(out, err).run(args.drop(1))
        // Пульт приложения (D-47): те же команды, что на экране, через канал на loopback.
        "control", "status", "task", "session", "requests", "answer", "limit", "target", "slot", "sessions-limit", "search" ->
            control().run(args.first(), args.drop(1))
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
            |  dpai agent     агент на execution target (dpai agent без параметров — справка)
            |  dpai tools     общая папка инструментов target (configure|status|disable)
            |${ControlCommand.USAGE}
            |  dpai help      эта справка
            |
            |Коды выхода: 0 — успех, 1 — отказ, 2 — неверный вызов.
        """.trimMargin()
    }
}
