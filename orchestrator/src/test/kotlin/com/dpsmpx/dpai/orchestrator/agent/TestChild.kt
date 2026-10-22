package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.TurnJob
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
 * - `child spawn-daemon` — потомок, потерявший родителя (A-9): на POSIX —
 *   фоном оболочки, на Windows — через промежуточный процесс, который сразу
 *   выходит; напечатать `daemon <pid>`, ждать без конца;
 * - `child job-helper S R M` — подделка помощника объекта задания
 *   (`TurnJob`): `R.pid`, строка готовности, ожидание выхода супервизора S;
 *   затем `R.marker` — `released`, если есть отметка R, иначе `closed`;
 *   режим M = `fail` — отказ, как без `Add-Type`;
 * - `agent supervise --home H --run R` — супервизор запуска, как у `dpai`;
 *   с файлом [FAKE_JOB] в H — с подделкой помощника.
 */
object TestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "child" -> child(args.drop(1))
            "agent" -> {
                val home = args[args.indexOf("--home") + 1]
                val run = IdKinds.SESSION.trusted(args[args.indexOf("--run") + 1])
                val fake = File(home, FAKE_JOB).takeIf { it.exists() }?.readText()?.trim()
                val helper = fake?.let { mode -> JobHelper { _, supervisor, release -> command("child", "job-helper", supervisor.toString(), release.path, mode.ifEmpty { "ok" }) } }
                val runtime = if (helper != null) {
                    AgentRuntime(home, Clock.SYSTEM, "test", selfCommand(), jobHelper = helper)
                } else {
                    AgentRuntime(home, Clock.SYSTEM, "test", selfCommand())
                }
                runtime.supervise(run)
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
                if (File.separatorChar == '\\') {
                    // Помощник объекта задания готовится секунды (A-9, окно в `TurnJob`): потомок — после.
                    CountDownLatch(1).await(15, java.util.concurrent.TimeUnit.SECONDS)
                    val pidFile = File.createTempFile("dpai-daemon", ".pid").apply { deleteOnExit() }
                    // Промежуточный процесс запускает внука и выходит: у внука нет живого родителя.
                    LocalProcessHost().run(ProcessSpec(command("child", "spawn-orphan", pidFile.path)), 60.seconds)
                    var pid: String? = null
                    while (pid.isNullOrBlank()) {
                        CountDownLatch(1).await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        pid = pidFile.readText().trim()
                    }
                    println("daemon $pid")
                    CountDownLatch(1).await()
                }
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
            // Вывод `claude auth status --json` с личными данными (D-55, ревью A-72).
            "auth-status" -> {
                println("""{"loggedIn":true,"authMethod":"claude.ai","apiProvider":"firstParty","email":"slot.owner@example.org","orgId":"org-777","subscriptionType":"max"}""")
                exitProcess(0)
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
            "spawn-orphan" -> {
                LocalProcessHost().start(ProcessSpec(command("child", "hang-pid", args[1])))
                exitProcess(0)
            }
            "hang-pid" -> {
                File(args[1]).writeText(ProcessHandle.current().pid().toString())
                CountDownLatch(1).await()
            }
            "job-helper" -> {
                val (supervisor, release, mode) = args.drop(1)
                File("$release.pid").writeText(ProcessHandle.current().pid().toString())
                if (mode == "fail") {
                    println(TurnJob.PREFIX + "Add-Type запрещён политикой")
                    exitProcess(3)
                }
                println(TurnJob.READY)
                ProcessHandle.of(supervisor.toLong()).ifPresent { it.onExit().get() }
                File("$release.marker").writeText(if (File(release).exists()) "released" else "closed")
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

    /** Файл в каталоге агента: супервизор теста — с подделкой помощника объекта задания; содержимое — режим. */
    const val FAKE_JOB = "test-job-helper"

    /** Похож на ключ API Anthropic; собран из частей, чтобы исходник не выглядел секретом. */
    val FAKE_KEY: String = "sk-" + "ant-" + "api03-" + "Zx9".repeat(12)

    fun command(vararg args: String): List<String> = selfCommand() + args

    fun selfCommand(): List<String> {
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), exe).path
        return listOf(java, "-cp", System.getProperty("java.class.path"), TestChild::class.java.name)
    }
}
