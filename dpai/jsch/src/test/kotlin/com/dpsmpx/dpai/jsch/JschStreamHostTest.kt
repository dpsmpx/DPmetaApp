package com.dpsmpx.dpai.jsch

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.execution.target.SshEndpoint
import com.dpsmpx.dpai.execution.target.TargetPlatform
import com.dpsmpx.dpai.lsp.dap.DebugLaunch
import com.dpsmpx.dpai.lsp.dap.DebugLaunches
import com.dpsmpx.dpai.lsp.dap.DebugSession
import com.dpsmpx.dpai.lsp.dap.DebugStatus
import com.dpsmpx.dpai.lsp.dap.DebugView
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Процесс-собеседник по SSH без PTY (D-164): отказы — до сети; на настоящем
 * сервере (`-Ddpai.test.ssh=user@host:port:posix`, `.identity`, `.knownHosts`) —
 * байты туда и обратно как есть, остановка закрытием stdin, а с
 * `-Ddpai.test.gdb` и `-Ddpai.test.debugpy` (пути на том же сервере) — сеанс
 * отладки DAP через SSH до остановки на точке и выхода.
 */
class JschStreamHostTest {
    private fun <T> ok(r: Outcome<T>): T = assertIs<Outcome.Success<T>>(r, r.toString()).value

    /** Адрес, на котором никто не слушает: отказ до сети не дойдёт до соединения. */
    private val nowhere = SshEndpoint.of("127.0.0.1", "nobody", 1).valueOrNull()!!

    @Test
    fun refusedBeforeTheNetwork() {
        val posix = JschStreamHost(nowhere, TargetPlatform.POSIX)
        val withDir = posix.open(ProcessSpec(listOf("gdb", "-i=dap"), workDir = "/tmp"))
        assertTrue(withDir is Outcome.Failure && withDir.diagnostics.single().code == DiagCode.UNSUPPORTED, withDir.toString())
        val withEnv = posix.open(ProcessSpec(listOf("gdb"), environment = mapOf("A" to "1")))
        assertTrue(withEnv is Outcome.Failure && withEnv.diagnostics.single().code == DiagCode.UNSUPPORTED, withEnv.toString())
        // Windows: знак раскрытия cmd/PowerShell не передаётся (A-06) — отказ, а не попытка входа.
        val windows = JschStreamHost(nowhere, TargetPlatform.WINDOWS).open(ProcessSpec(listOf("py", "-c", "print(\$x)")))
        assertTrue(windows is Outcome.Failure && windows.diagnostics.single().code == DiagCode.UNSUPPORTED, windows.toString())
        // Тот же вход без отказа — до соединения и его ошибки.
        val refused = posix.open(ProcessSpec(listOf("true")))
        assertTrue(refused is Outcome.Failure && refused.diagnostics.single().code != DiagCode.UNSUPPORTED, refused.toString())
    }

    private fun live(): Pair<SshEndpoint, TargetPlatform>? {
        val spec = System.getProperty("dpai.test.ssh") ?: return null
        val (userHost, port, platform) = spec.split(":", limit = 4)
        val endpoint = SshEndpoint.of(
            userHost.substringAfter('@'), userHost.substringBefore('@'), port.toInt(),
            identityFile = System.getProperty("dpai.test.ssh.identity"), knownHostsFile = System.getProperty("dpai.test.ssh.knownHosts"),
        ).valueOrNull()!!
        return endpoint to if (platform == "windows") TargetPlatform.WINDOWS else TargetPlatform.POSIX
    }

    @Test
    fun bytesPassUnchangedAndClosingInputEndsTheProgram() {
        val (endpoint, platform) = live() ?: return assumeTrue(false, "dpai.test.ssh не задан")
        assumeTrue(platform == TargetPlatform.POSIX, "проверка байтов — на POSIX-сервере")
        val process = ok(JschStreamHost(endpoint, platform).open(ProcessSpec(listOf("cat"))))
        // Кадр протокола с CRLF и все значения байта: без PTY нет эха и замены переводов строк.
        val sent = "Content-Length: 5\r\n\r\nhello".toByteArray() + ByteArray(256) { it.toByte() }
        process.stdin.write(sent)
        process.stdin.flush()
        val got = ByteArray(sent.size)
        var n = 0
        while (n < sent.size) {
            val r = process.stdout.read(got, n, sent.size - n)
            if (r < 0) break
            n += r
        }
        assertContentEquals(sent, got)
        val report = process.stop(10.seconds)
        assertEquals(0, report.exitCode, report.toString())
        assertTrue(!report.forced)
    }

    private fun waitFor(session: DebugSession, what: String, test: (DebugView) -> Boolean): DebugView {
        val tick = Semaphore(0)
        repeat(1200) {
            if (test(session.view)) return session.view
            tick.tryAcquire(50, TimeUnit.MILLISECONDS)
        }
        error("не дождались: $what; ${session.view}")
    }

    /** Сеанс DAP через SSH: точка, стек, переменная, выход с кодом. */
    private fun debugOverSsh(launch: DebugLaunch, file: String, line: Int, function: String) {
        val (endpoint, platform) = live()!!
        val process = ok(JschStreamHost(endpoint, platform).open(ProcessSpec(launch.command)))
        val session = DebugSession(process.stdout, process.stdin, {})
        try {
            ok(session.start(launch, mapOf(file to listOf(line)), 60.seconds))
            val stopped = waitFor(session, "остановка на точке") { it.status is DebugStatus.Stopped && it.variables.isNotEmpty() }
            assertEquals(line, stopped.location!!.line)
            assertEquals(function, stopped.location!!.name.substringBefore('(').substringAfterLast('.').trim())
            assertTrue(stopped.variables.any { it.name == "x" && it.value == "7" }, stopped.variables.toString())
            ok(session.resume())
            assertEquals(DebugStatus.Exited(3), waitFor(session, "выход") { it.status is DebugStatus.Exited }.status)
        } finally {
            session.stop()
            process.stop(5.seconds)
        }
    }

    @Test
    fun gdbOverSsh(@TempDir dir: File) {
        val gdb = System.getProperty("dpai.test.gdb")
        assumeTrue(live() != null && gdb != null, "dpai.test.ssh и dpai.test.gdb не заданы")
        val source = File(dir, "t.c").apply { writeText("#include <stdio.h>\nint sq(int x) {\n    return x * x;\n}\nint main(void) {\n    printf(\"%d\\n\", sq(7));\n    return 3;\n}\n") }
        val program = File(dir, "t")
        val cc = LocalProcessHost().run(ProcessSpec(listOf("cc", "-g", "-O0", source.path, "-o", program.path)), 60.seconds)
        assumeTrue((cc as? Outcome.Success)?.value?.ok == true, "нет компилятора C")
        val launch = DebugLaunches.gdb(program.path, emptyList(), dir.path)
        debugOverSsh(launch.copy(command = listOf(gdb!!) + launch.command.drop(1)), source.path, 3, "sq")
    }

    @Test
    fun debugpyOverSsh(@TempDir dir: File) {
        val python = System.getProperty("dpai.test.debugpy")
        assumeTrue(live() != null && python != null, "dpai.test.ssh и dpai.test.debugpy не заданы")
        val source = File(dir, "t.py").apply { writeText("import sys\n\n\ndef sq(x):\n    return x * x\n\n\nprint(sq(7), flush=True)\nsys.exit(3)\n") }
        debugOverSsh(DebugLaunches.debugpy(python!!, source.path, emptyList(), dir.path), source.path, 5, "sq")
    }
}
