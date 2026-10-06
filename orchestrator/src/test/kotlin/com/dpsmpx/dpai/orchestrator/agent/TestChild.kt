package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Программы тестов агента: потомок с известным поведением и супервизор,
 * запускаемый агентом отдельным процессом.
 *
 * - `child hang` — строка `ready` и ожидание без конца;
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
            "spawn-hang" -> {
                val started = LocalProcessHost().start(ProcessSpec(command("child", "hang")))
                println("grandchild ${(started as Outcome.Success).value.pid}")
                CountDownLatch(1).await()
            }
            else -> exitProcess(64)
        }
    }

    fun command(vararg args: String): List<String> = selfCommand() + args

    fun selfCommand(): List<String> {
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), exe).path
        return listOf(java, "-cp", System.getProperty("java.class.path"), TestChild::class.java.name)
    }
}
