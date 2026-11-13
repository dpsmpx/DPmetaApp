package com.dpsmpx.dpai.lsp.dap

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Настоящие адаптеры (D-159): gdb в режиме DAP (`-Ddpai.test.gdb=/usr/bin/gdb`, GDB 15+,
 * программа собирается `cc -g -O0`) и debugpy (`-Ddpai.test.debugpy=<python с debugpy>`).
 * Остановка в функции, стек, переменная, выражение, шаг, выход с кодом. Без свойств —
 * тесты пропускаются. Те же адаптеры под proot приложения — `docs/research/LINUX_IN_APP.md`, §12.
 */
class DapLiveTest {
    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    /** Ждать вида сеанса: до 60 с шагами по 50 мс — без системных часов (D-07). */
    private fun waitFor(session: DebugSession, what: String, test: (DebugView) -> Boolean): DebugView {
        val tick = java.util.concurrent.Semaphore(0)
        repeat(1200) {
            if (test(session.view)) return session.view
            tick.tryAcquire(50, TimeUnit.MILLISECONDS)
        }
        error("не дождались: $what; ${session.view}")
    }

    private fun walk(launch: DebugLaunch, file: String, line: Int, local: Pair<String, String>, expression: Pair<String, String>, function: String, exit: Int) {
        val process = ok(LocalStreamHost().open(ProcessSpec(launch.command, workDir = launch.workDir)))
        val session = DebugSession(process.stdout, process.stdin, {})
        try {
            ok(session.start(launch, mapOf(file to listOf(line)), 60.seconds))
            val stopped = waitFor(session, "остановка на точке") { it.status is DebugStatus.Stopped && it.variables.isNotEmpty() }
            // gdb ставит точку до загрузки программы отложенной и подтверждает событием `breakpoint`.
            assertTrue(stopped.breakpoints[file].orEmpty().any { it.verified && it.line == line }, stopped.toString())
            assertEquals("breakpoint", (stopped.status as DebugStatus.Stopped).reason)
            val at = stopped.location!!
            assertEquals(File(file).canonicalPath, File(at.path!!).canonicalPath)
            assertEquals(line, at.line)
            assertEquals(function, at.name.substringBefore('(').substringAfterLast('.').trim())
            assertTrue(stopped.frames.size >= 2, stopped.frames.toString())
            assertTrue(stopped.variables.any { it.name == local.first && it.value == local.second }, stopped.variables.toString())
            assertEquals(expression.second, ok(session.evaluate(expression.first)))
            ok(session.next())
            waitFor(session, "остановка после шага") { it.status is DebugStatus.Stopped && it.frames.isNotEmpty() }
            ok(session.resume())
            val done = waitFor(session, "выход") { it.status is DebugStatus.Exited }
            assertEquals(DebugStatus.Exited(exit), done.status)
            assertTrue(done.output.any { "49" in it }, done.output.toString())
        } finally {
            session.stop()
            process.stop(2.seconds)
        }
    }

    @Test
    fun gdbStopsStepsAndExits(@TempDir dir: File) {
        val gdb = System.getProperty("dpai.test.gdb")
        assumeTrue(gdb != null, "dpai.test.gdb не задан")
        val source = File(dir, "t.c").apply { writeText("#include <stdio.h>\nint sq(int x) {\n    return x * x;\n}\nint main(void) {\n    int a = 7;\n    printf(\"%d\\n\", sq(a));\n    return 3;\n}\n") }
        val program = File(dir, "t")
        val cc = com.dpsmpx.dpai.execution.process.LocalProcessHost().run(ProcessSpec(listOf("cc", "-g", "-O0", source.path, "-o", program.path)), 60.seconds)
        assumeTrue((cc as? Outcome.Success)?.value?.ok == true, "нет компилятора C")
        val launch = DebugLaunches.gdb(program.path, emptyList(), dir.path).let { it.copy(command = listOf(gdb!!) + it.command.drop(1)) }
        walk(launch, source.path, 3, "x" to "7", "x*x" to "49", "sq", 3)
    }

    @Test
    fun debugpyStopsStepsAndExits(@TempDir dir: File) {
        val python = System.getProperty("dpai.test.debugpy")
        assumeTrue(python != null, "dpai.test.debugpy не задан")
        val source = File(dir, "t.py").apply { writeText("import sys\n\n\ndef sq(x):\n    return x * x\n\n\na = 7\nprint(sq(a), flush=True)\nsys.exit(3)\n") }
        walk(DebugLaunches.debugpy(python!!, source.path, emptyList(), dir.path), source.path, 5, "x" to "7", "x*x" to "49", "sq", 3)
    }
}
