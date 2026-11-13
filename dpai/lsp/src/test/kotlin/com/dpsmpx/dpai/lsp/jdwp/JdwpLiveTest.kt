package com.dpsmpx.dpai.lsp.jdwp

import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.LocalStreamHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.lsp.dap.DebugStatus
import com.dpsmpx.dpai.lsp.dap.DebugView
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Настоящая ВМ (D-161): JVM тестов с `-agentlib:jdwp` (ждёт отладчика, как
 * приложение после `am set-debug-app -w`) и программа, собранная `javac -g`. Точка
 * в классе, ещё не загруженном при подключении (`CLASS_PREPARE`), стек, `this` и
 * переменные, выражение-имя, шаг, исключение, пойманное своим кодом, — мимо,
 * непойманное — остановка с текстом, выход. Без `javac` рядом с JVM — пропуск.
 */
class JdwpLiveTest {
    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    /** Ждать вида сеанса: до 60 с шагами по 50 мс — без системных часов (D-07). */
    private fun waitFor(session: JdwpSession, what: String, test: (DebugView) -> Boolean): DebugView {
        val tick = Semaphore(0)
        repeat(1200) {
            if (test(session.view)) return session.view
            tick.tryAcquire(50, TimeUnit.MILLISECONDS)
        }
        error("не дождались: $what; ${session.view}")
    }

    private class Sources(private val root: File) : JvmSources {
        override fun packageOf(path: String): String? =
            runCatching { File(path).readLines() }.getOrNull()?.firstNotNullOfOrNull { Regex("^\\s*package\\s+([\\w.]+)").find(it)?.groupValues?.get(1) } ?: ""

        override fun find(pkg: String, file: String): String? =
            File(root, pkg.replace('.', '/') + "/" + file).takeIf { it.isFile }?.path
    }

    @Test
    fun breakpointBeforeLoadStackVariablesStepExceptionAndExit(@TempDir dir: File) {
        val home = File(System.getProperty("java.home"))
        val javac = File(home, "bin/javac")
        val java = File(home, "bin/java")
        assumeTrue(javac.canExecute(), "javac нет рядом с JVM тестов")
        val source = File(dir, "app/Main.java").apply {
            parentFile.mkdirs()
            writeText(
                """
                package app;

                public class Main {
                    private final int base;
                    Main(int base) { this.base = base; }
                    int sq(int x) {
                        int y = x * x;
                        return y + base;
                    }
                    public static void main(String[] args) {
                        String label = "dp";
                        int a = 7;
                        try { Integer.parseInt("x"); } catch (NumberFormatException e) { }
                        try { throw new IllegalArgumentException("inner"); } catch (RuntimeException e) { }
                        Main m = new Main(1);
                        int r = m.sq(a);
                        System.out.println(label + r);
                        throw new IllegalStateException("boom " + r);
                    }
                }
                """.trimIndent() + "\n",
            )
        }
        val built = LocalProcessHost().run(ProcessSpec(listOf(javac.path, "-g", "-d", dir.path, source.path)), 120.seconds)
        assumeTrue((built as? Outcome.Success)?.value?.ok == true, "javac не собрал программу: $built")
        val vm = ok(LocalStreamHost().open(ProcessSpec(listOf(java.path, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0", "-cp", dir.path, "app.Main"), workDir = dir.path)))
        val reader = vm.stdout.bufferedReader()
        val port = generateSequence { reader.readLine() }.firstNotNullOfOrNull { Regex("address: (?:127\\.0\\.0\\.1:)?(\\d+)").find(it)?.groupValues?.get(1)?.toInt() }
            ?: error("ВМ не сказала порт JDWP")
        // Вывод программы — читать, чтобы ВМ не встала на полном канале.
        Thread({ runCatching { reader.forEachLine { } } }, "jdwp-live-out").apply { isDaemon = true; start() }
        val socket = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 10_000) }
        val session = JdwpSession(socket.getInputStream(), socket.getOutputStream(), Sources(dir), {})
        try {
            ok(session.attach(mapOf(source.path to listOf(7))))
            assertTrue(session.view.output.any { it.startsWith("ВМ: ") }, session.view.output.toString())

            val stopped = waitFor(session, "остановка на точке") { it.status is DebugStatus.Stopped && it.variables.isNotEmpty() }
            assertEquals("breakpoint", (stopped.status as DebugStatus.Stopped).reason)
            // Класс Main загружен после подключения: точка встала по CLASS_PREPARE.
            assertTrue(stopped.breakpoints[source.path].orEmpty().any { it.verified && it.line == 7 }, stopped.breakpoints.toString())
            val at = stopped.location!!
            assertEquals(source.path, at.path)
            assertEquals(7, at.line)
            assertEquals("Main.sq", at.name)
            assertEquals("Main.main", stopped.frames[1].name)
            assertEquals(16, stopped.frames[1].line)
            assertTrue(stopped.variables.any { it.name == "x" && it.value == "7" && it.type == "int" }, stopped.variables.toString())
            assertTrue(stopped.variables.any { it.name == "this" && it.value.startsWith("Main@") }, stopped.variables.toString())
            assertEquals("7", ok(session.evaluate("x")))
            assertIs<Outcome.Failure>(session.evaluate("x * x"))

            val callerVars = ok(session.selectFrame(stopped.frames[1].id))
            assertTrue(callerVars.any { it.name == "label" && it.value == "\"dp\"" }, callerVars.toString())
            assertTrue(callerVars.any { it.name == "m" && it.value.startsWith("Main@") }, callerVars.toString())

            ok(session.next())
            val stepped = waitFor(session, "остановка после шага") { it.status is DebugStatus.Stopped && it.frames.isNotEmpty() }
            assertEquals("step", (stepped.status as DebugStatus.Stopped).reason)
            assertEquals(8, stepped.location?.line)
            assertTrue(stepped.variables.any { it.name == "y" && it.value == "49" }, stepped.variables.toString())

            ok(session.setBreakpoints(source.path, emptyList()))
            ok(session.resume())
            val thrown = waitFor(session, "остановка на исключении") { it.status is DebugStatus.Stopped && it.frames.isNotEmpty() }
            val why = thrown.status as DebugStatus.Stopped
            assertEquals("exception", why.reason)
            assertTrue(why.description.orEmpty().startsWith("IllegalStateException не поймано") && "boom 50" in why.description.orEmpty(), why.toString())
            assertEquals(18, thrown.location?.line)

            ok(session.resume())
            val done = waitFor(session, "выход") { it.status is DebugStatus.Exited }
            assertEquals(DebugStatus.Exited(null), done.status)
        } finally {
            session.stop()
            runCatching { socket.close() }
            vm.stop(2.seconds)
        }
    }
}
