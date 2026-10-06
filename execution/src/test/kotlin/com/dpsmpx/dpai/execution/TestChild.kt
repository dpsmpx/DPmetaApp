package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * Программа-потомок тестов процессов: настоящий процесс ОС с известным
 * поведением на любой платформе, где есть JVM.
 *
 * - `lines N` — N строк и выход 0;
 * - `exit C` — выход с кодом C;
 * - `hang` — строка `ready` и ожидание без конца;
 * - `spawn-hang` — запустить внука `hang`, напечатать `grandchild <pid>`,
 *   ждать без конца: проверка, что остановка убирает всё дерево.
 */
object TestChild {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.getOrNull(0)) {
            "lines" -> {
                repeat(args[1].toInt()) { println("line $it") }
                exitProcess(0)
            }
            "exit" -> exitProcess(args[1].toInt())
            "hang" -> {
                println("ready")
                CountDownLatch(1).await()
            }
            "spawn-hang" -> {
                val started = LocalProcessHost().start(ProcessSpec(command("hang")))
                val child = (started as Outcome.Success).value
                println("grandchild ${child.pid}")
                CountDownLatch(1).await()
            }
            else -> exitProcess(64)
        }
    }

    fun command(vararg args: String): List<String> {
        val exe = if (File.separatorChar == '\\') "java.exe" else "java"
        val java = File(File(System.getProperty("java.home"), "bin"), exe).path
        return listOf(java, "-cp", System.getProperty("java.class.path"), TestChild::class.java.name) + args
    }
}
