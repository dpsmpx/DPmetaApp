package com.dpsmpx.dpai.android.linux

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcessHost
import com.dpsmpx.dpai.execution.linux.ProcStat
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import java.io.File
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

/**
 * Нативный код процессов (D-61, шаг 3) — на JVM: `dpai_native.c`, собранный
 * для машины сборки (задача `hostNative`), с теми же вызовами, что на
 * телефоне. Нет компилятора C — проверка пропущена; на телефоне —
 * `docs/DEVICE_CHECK.md`.
 */
class JniProcessesTest {
    private val native = JniProcesses()
    private val env = mapOf("PATH" to "/usr/bin:/bin", "LANG" to "C.UTF-8", "HOME" to "/tmp")

    @BeforeEach
    fun library() {
        val path = System.getProperty(NativeCalls.LIBRARY_PROPERTY)
        assumeTrue(path != null && File(path).isFile, "нет библиотеки для машины сборки (нужен компилятор C)")
    }

    private fun spawn(script: String, pty: WindowSize? = null, workDir: String? = null, environment: Map<String, String> = env): NativeChild =
        assertIs<Outcome.Success<NativeChild>>(native.spawn(listOf("sh", "-c", script), environment, workDir, pty)).value

    private fun InputStream.text(): String = readBytes().toString(Charsets.UTF_8)

    @Test
    fun pipesCarryInputOutputErrorsAndExitCode() {
        val c = spawn("read x; echo \"вывод:\$x\"; echo ошибка >&2; exit 3")
        c.stdin.use { it.write("ввод 😀\n".toByteArray(Charsets.UTF_8)) }
        assertEquals("вывод:ввод 😀\n", c.stdout.use { it.text() })
        assertEquals("ошибка\n", requireNotNull(c.stderr).use { it.text() })
        assertEquals(3, c.waitFor())
    }

    @Test
    fun argumentsEnvironmentAndDirectoryArePassedExactly() {
        val dir = createTempDir()
        val c = assertIs<Outcome.Success<NativeChild>>(
            native.spawn(listOf("/bin/sh", "-c", "printf '%s|%s|%s' \"\$1\" \"\$ONLY\" \"\$(pwd)\"", "sh", "знак 😀"), mapOf("ONLY" to "да"), dir.path, null),
        ).value
        c.stdin.close()
        assertEquals("знак 😀|да|${dir.canonicalPath}", c.stdout.use { it.text() })
        assertEquals(0, c.waitFor())
        // Окружение — ровно заданное: без оболочки, которая добавила бы PWD.
        val e = assertIs<Outcome.Success<NativeChild>>(native.spawn(listOf("/usr/bin/env"), mapOf("ONLY" to "да"), null, null)).value
        e.stdin.close()
        assertEquals("ONLY=да\n", e.stdout.use { it.text() })
        assertEquals(0, e.waitFor())
        dir.deleteRecursively()
    }

    @Test
    fun childLeadsItsOwnSession() {
        val c = spawn("cat /proc/self/stat")
        c.stdin.close()
        val stat = c.stdout.use { it.text() }
        val self = requireNotNull(ProcStat.of(c.pid.toLong()))
        assertEquals(c.pid.toLong(), self.session, stat)
        assertEquals(0, c.waitFor())
    }

    @Test
    fun ptyHasSizeResizeAndMergedOutput() {
        val c = spawn("tty; stty size; read x; stty size; echo ошибка >&2", pty = WindowSize(100, 30))
        assertNull(c.stderr)
        val out = c.stdout
        val first = readUntil(out, "30 100")
        assertTrue("/dev/pts/" in first, first)
        c.resize(WindowSize(120, 40))
        c.stdin.write("\n".toByteArray())
        val rest = readUntil(out, "ошибка")
        assertTrue("40 120" in rest, rest)
        assertEquals(0, c.waitFor())
        out.close()
        c.stdin.close()
    }

    @Test
    fun startFailuresAreDiagnostics() {
        val missing = native.spawn(listOf("/нет/такой/программы"), env, null, null)
        val e = assertIs<Outcome.Failure>(missing).errors.single()
        assertEquals(DiagCode.PROCESS_START_FAILED, e.code)
        assertTrue("No such file" in e.message, e.message)
        assertIs<Outcome.Failure>(native.spawn(listOf("sh"), env, "/нет/каталога", null))
        assertIs<Outcome.Failure>(native.spawn(listOf("нет-такой-программы"), env, null, null))
    }

    @Test
    fun hostStopsBackgroundedDescendants() {
        val lines = CopyOnWriteArrayList<OutputLine>()
        val host = NativeProcessHost(native, Clock.SYSTEM)
        val p = assertIs<Outcome.Success<RunningProcess>>(
            host.start(ProcessSpec(listOf("sh", "-c", "sleep 300 & (trap '' TERM; sleep 300) & echo готов; wait"), environment = env)) { lines += it },
        ).value
        val pid = requireNotNull(p.pid)
        val deadline = Clock.SYSTEM.monotonicNanos() + 10.seconds.inWholeNanoseconds
        while (lines.none { it.text == "готов" } && Clock.SYSTEM.monotonicNanos() < deadline) Thread.onSpinWait()
        assertTrue(ProcStat.livingInSession(pid).size >= 3, ProcStat.livingInSession(pid).toString())
        val r = p.stop(1.seconds)
        assertTrue(r.forced, "подпроцесс игнорирует TERM")
        assertEquals(emptyList(), r.survivors)
        assertEquals(emptyList(), ProcStat.livingInSession(pid))
        assertTrue(lines.any { it.stream == OutputStream.STDOUT && it.text == "готов" })
    }

    private fun readUntil(input: InputStream, marker: String): String {
        val got = StringBuilder()
        val buf = ByteArray(256)
        while (marker !in got) {
            val n = input.read(buf)
            if (n < 0) break
            got.append(String(buf, 0, n, Charsets.UTF_8))
        }
        return got.toString()
    }

    private fun createTempDir(): File = kotlin.io.path.createTempDirectory("dpai-native").toFile()
}
