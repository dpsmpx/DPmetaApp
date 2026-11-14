package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.linux.NativeChild
import com.dpsmpx.dpai.execution.linux.NativeProcessHost
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.ProcStat
import com.dpsmpx.dpai.execution.linux.Signals
import com.dpsmpx.dpai.execution.linux.WindowSize
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/**
 * Остановка процессов без `ProcessHandle` (D-75): сеанс по `/proc`,
 * `TERM`, через паузу — `KILL`, пережившие — в отчёте. `/proc` и процессы
 * — подделки: настоящие процессы через JNI проверяет `JniProcessesTest`
 * модуля `android`.
 */
class NativeProcessHostTest {
    @TempDir
    lateinit var dir: Path

    private val proc: File get() = dir.toFile()

    /** Процесс подделки `/proc`: состояние меняют сигналы. */
    private fun stat(pid: Int, session: Int, state: Char = 'S', comm: String = "sh", start: Long = 5000) =
        File(proc, "$pid").apply { mkdirs() }.resolve("stat")
            .writeText("$pid ($comm) $state 1 $session $session 0 -1 4194560 120 0 0 0 3 1 0 0 20 0 1 0 $start 1000 50\n")

    /**
     * Подделка: процесс [ROOT] — лидер сеанса, [members] — его фоновые
     * потомки. [ignoreTerm] — кто не выходит по `TERM`; [immortal] — кто
     * не выходит и по `KILL`.
     */
    private inner class Fake(
        val members: List<Int> = emptyList(),
        val ignoreTerm: Set<Int> = emptySet(),
        val immortal: Set<Int> = emptySet(),
        val stdout: String = "",
        val stderr: String = "",
    ) : NativeProcesses {
        val signals = CopyOnWriteArrayList<Pair<Int, Int>>()
        val spawned = CopyOnWriteArrayList<Triple<List<String>, Map<String, String>, String?>>()
        val stdin = ByteArrayOutputStream()
        private val exit = CountDownLatch(1)

        @Volatile
        var code = 0

        override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> {
            spawned += Triple(argv, environment, workDir)
            stat(ROOT, ROOT)
            members.forEach { stat(it, ROOT) }
            return Outcome.success(object : NativeChild {
                override val pid = ROOT
                override val stdin = this@Fake.stdin
                override val stdout: InputStream = ByteArrayInputStream(this@Fake.stdout.toByteArray())
                override val stderr: InputStream = ByteArrayInputStream(this@Fake.stderr.toByteArray())
                override fun waitFor(): Int {
                    exit.await()
                    return code
                }
                override fun resize(size: WindowSize) {}
            })
        }

        override fun kill(pid: Int, signal: Int): Boolean {
            signals += pid to signal
            if (pid < 0) return true
            val dies = when (signal) {
                Signals.TERM -> pid !in ignoreTerm && pid !in immortal
                Signals.KILL -> pid !in immortal
                else -> false
            }
            if (dies) {
                stat(pid, ROOT, 'Z')
                if (pid == ROOT) {
                    code = 128 + signal
                    exit.countDown()
                }
            }
            return true
        }

        fun exitNow(c: Int) {
            code = c
            stat(ROOT, ROOT, 'Z')
            exit.countDown()
        }
    }

    private fun start(fake: Fake, spec: ProcessSpec = ProcessSpec(listOf("/bin/sh", "-c", "x")), lines: MutableList<OutputLine> = CopyOnWriteArrayList()): RunningProcess =
        assertIs<Outcome.Success<RunningProcess>>(NativeProcessHost(fake, Clock.SYSTEM, proc, forceWait = 300.milliseconds).start(spec) { lines += it }).value

    @Test
    fun procStatReadsSessionAndStateEvenWithParensInTheName() {
        assertEquals(ProcStat.Entry(42, 'S', 40, 5000), ProcStat.of(42, proc.also { stat(42, 40, comm = "a) b") }))
        assertEquals(50_000L, ProcStat.startedSinceBootMillis(42, proc))
        stat(43, 40, 'Z')
        stat(44, 41)
        stat(45, 40, 'R')
        assertEquals(listOf(42L, 45L), ProcStat.livingInSession(40, proc))
        assertNull(ProcStat.of(99, proc))
    }

    @Test
    fun startPassesCommandEnvironmentInputAndOutput() {
        val fake = Fake(stdout = "раз\nдва\n", stderr = "ошибка\n")
        val lines = CopyOnWriteArrayList<OutputLine>()
        val p = start(fake, ProcessSpec(listOf("/bin/sh", "-c", "x"), workDir = "/w", environment = mapOf("A" to "1"), stdin = "ввод"), lines)
        assertEquals(Triple(listOf("/bin/sh", "-c", "x"), mapOf("A" to "1"), "/w"), fake.spawned.single())
        assertEquals(ROOT.toLong(), p.pid)
        fake.exitNow(3)
        assertEquals(3, p.awaitExit(5.seconds))
        // Запись stdin асинхронна: awaitExit дожидается также её потока (PR #188).
        assertEquals("ввод", fake.stdin.toString(Charsets.UTF_8))
        assertFalse(p.isAlive)
        assertEquals(listOf("раз", "два"), lines.filter { it.stream == OutputStream.STDOUT }.map { it.text })
        assertEquals(listOf("ошибка"), lines.filter { it.stream == OutputStream.STDERR }.map { it.text })
    }

    @Test
    fun stopTerminatesTheWholeSessionIncludingBackgroundedChildren() {
        val fake = Fake(members = listOf(201, 202))
        val p = start(fake)
        assertNull(p.awaitExit(10.milliseconds))
        val r = p.stop(2.seconds)
        assertFalse(r.forced)
        assertEquals(emptyList(), r.survivors)
        assertEquals(128 + Signals.TERM, r.exitCode)
        // Группе и каждому живому процессу сеанса — TERM; KILL не понадобился.
        assertTrue((-ROOT to Signals.TERM) in fake.signals)
        assertTrue(listOf(ROOT, 201, 202).all { (it to Signals.TERM) in fake.signals }, fake.signals.toString())
        assertTrue(fake.signals.none { it.second == Signals.KILL })
    }

    @Test
    fun childIgnoringTermIsKilledAfterGrace() {
        val fake = Fake(members = listOf(201), ignoreTerm = setOf(201))
        val r = start(fake).stop(200.milliseconds)
        assertTrue(r.forced)
        assertEquals(emptyList(), r.survivors)
        assertTrue((201 to Signals.KILL) in fake.signals)
    }

    @Test
    fun survivorsAreReportedNotHidden() {
        val fake = Fake(members = listOf(201), immortal = setOf(201))
        val r = start(fake).stop(100.milliseconds)
        assertTrue(r.forced)
        assertEquals(listOf(201L), r.survivors)
    }

    @Test
    fun stopAfterExitStillTakesLeftBackgroundProcesses() {
        val fake = Fake(members = listOf(201))
        val p = start(fake)
        fake.exitNow(0)
        assertEquals(0, p.awaitExit(5.seconds))
        val r = p.stop(2.seconds)
        assertEquals(0, r.exitCode)
        assertEquals(emptyList(), r.survivors)
        assertTrue((201 to Signals.TERM) in fake.signals)
    }

    @Test
    fun stopOfAFinishedSessionSendsNothing() {
        val fake = Fake()
        val p = start(fake)
        fake.exitNow(0)
        assertEquals(0, p.awaitExit(5.seconds))
        assertEquals(0, p.stop(1.seconds).exitCode)
        assertEquals(emptyList(), fake.signals)
    }

    @Test
    fun spawnFailureIsReturnedAsIs() {
        val failing = object : NativeProcesses {
            override fun spawn(argv: List<String>, environment: Map<String, String>, workDir: String?, pty: WindowSize?): Outcome<NativeChild> =
                Outcome.failure(DiagCode.PROCESS_START_FAILED, "нет файла")
            override fun kill(pid: Int, signal: Int) = false
        }
        val r = NativeProcessHost(failing, Clock.SYSTEM, proc).start(ProcessSpec(listOf("/nope"))) {}
        assertEquals(DiagCode.PROCESS_START_FAILED, assertIs<Outcome.Failure>(r).errors.single().code)
    }

    private companion object {
        const val ROOT = 200
    }
}
