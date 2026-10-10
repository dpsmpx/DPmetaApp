package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * Программы тестов агента: потомок с известным поведением и супервизор,
 * запускаемый агентом отдельным процессом.
 *
 * - `child hang` — строка `ready` и ожидание без конца;
 * - `child secret` — напечатать строку с поддельным ключом API и выйти;
 * - `child wide N W` — напечатать N строк по W знаков и выйти;
 * - `child spawn-hang` — запустить внука `child hang`, напечатать
 *   `grandchild <pid>`, ждать без конца;
 * - `agent supervise --home H --run R` — супервизор запуска, как у `dpai`.
 */
object TestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "child" -> child(args.drop(1))
            "agent" -> {
                val home = args[args.indexOf("--home") + 1]
                val run = IdKinds.SESSION.trusted(args[args.indexOf("--run") + 1])
                AgentRuntime(home, Clock.SYSTEM, "test", selfCommand()).supervise(run)
                exitProcess(0)
            }
            else -> exitProcess(64)
        }
    }

    private fun child(args: List<String>) {
        when (args.firstOrNull()) {
            "hang" -> {
                println("ready")
                CountDownLatch(1).await()
            }
            "spawn-daemon" -> {
                // Внук уходит в фон: оболочка выходит, и его родитель — init (A-9).
                val cmd = command("child", "hang").joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
                val sh = LocalProcessHost().run(ProcessSpec(listOf("sh", "-c", "$cmd >/dev/null 2>&1 & echo \$!")), 30.seconds)
                println("daemon ${(sh as Outcome.Success).value.stdout.first()}")
                CountDownLatch(1).await()
            }
            "tick" -> {
                // Строка каждые 200 мс, без конца (A-15: запись журнала ломается посреди хода).
                var i = 0
                while (true) {
                    println("tick ${i++}")
                    CountDownLatch(1).await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
            "ignore-term" -> {
                // Мягкая остановка (SIGTERM) не завершает: хук выхода ждёт без конца — нужен SIGKILL.
                Runtime.getRuntime().addShutdownHook(Thread { CountDownLatch(1).await() })
                println("ready")
                CountDownLatch(1).await()
            }
            "secret" -> {
                println("ключ: $FAKE_KEY")
                exitProcess(0)
            }
            "wide" -> {
                // [count] строк по [width] знаков: страницы ответа агента и предел текста события.
                val (count, width) = args.drop(1).map { it.toInt() }
                repeat(count) { i -> println("$i:" + "x".repeat(width)) }
                exitProcess(0)
            }
            "stdin-length" -> {
                // Только длина входа: сам вход в вывод (и в журнал) не попадает; латиницей —
                // вывод Java на Windows идёт в кодировке консоли (долг в `TODO.md`).
                println("stdin chars: ${System.`in`.readBytes().toString(Charsets.UTF_8).length}")
                exitProcess(0)
            }
            "spawn-hang" -> {
                val started = LocalProcessHost().start(ProcessSpec(command("child", "hang")))
                println("grandchild ${(started as Outcome.Success).value.pid}")
                CountDownLatch(1).await()
            }
            else -> exitProcess(64)
        }
    }

    /** Похож на ключ API Anthropic; собран из частей, чтобы исходник не выглядел секретом. */
    val FAKE_KEY: String = "sk-" + "ant-" + "api03-" + "Zx9".repeat(12)

    fun command(vararg args: String): List<String> = selfCommand() + args

    fun selfCommand(): List<String> {
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), exe).path
        return listOf(java, "-cp", System.getProperty("java.class.path"), TestChild::class.java.name)
    }
}
