package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.ArgumentEncoding
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.run
import com.dpsmpx.dpai.testkit.RealTime
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Процессы на машине контроллера (критерий этапа 3): запуск, вывод, код
 * выхода; остановка и тайм-аут убирают всё дерево — внук не остаётся
 * сиротой (ТЗ, §119).
 */
class LocalProcessHostTest {
    private val host = LocalProcessHost()

    @Test
    fun runsAndCollectsOutput() {
        val r = host.run(ProcessSpec(TestChild.command("lines", "3")), 30.seconds)
        assertEquals(listOf("line 0", "line 1", "line 2"), r.valueOrNull()!!.stdout)
        assertEquals(0, r.valueOrNull()!!.exitCode)
        assertEquals(7, host.run(ProcessSpec(TestChild.command("exit", "7")), 30.seconds).valueOrNull()!!.exitCode)
        // Предел вывода: строки сверх него отброшены, итог помечен (ревью 17 октября 2026).
        val cut = host.run(ProcessSpec(TestChild.command("lines", "100")), 30.seconds, maxStdoutChars = 21).valueOrNull()!!
        assertEquals(listOf("line 0", "line 1", "line 2"), cut.stdout)
        assertTrue(cut.truncated)
        assertTrue(!r.valueOrNull()!!.truncated)
    }

    @Test
    fun largeInputAndOutputDoNotDeadlock() {
        val text = "message\n".repeat(24_000)
        val result = host.run(ProcessSpec(TestChild.command("echo"), stdin = text), 30.seconds, maxStderrChars = 2048).valueOrNull()!!
        assertEquals(0, result.exitCode)
        assertEquals(text.trimEnd().lines(), result.stdout)
        assertTrue(result.stderr.sumOf { it.length + 1 } <= 2048)
        assertTrue(result.truncated, "поток ошибок ограничен, но полностью дочитан")
    }

    @Test
    fun outputBudgetsIncludeEmptyLinesAndReaderTruncation() {
        val host = object : com.dpsmpx.dpai.execution.process.ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<com.dpsmpx.dpai.execution.process.RunningProcess> {
                repeat(100_000) {
                    onOutput(OutputLine(OutputStream.STDOUT, ""))
                    onOutput(OutputLine(OutputStream.STDERR, ""))
                }
                return LocalProcessHost().start(ProcessSpec(TestChild.command("exit", "0")))
            }
        }
        val result = host.run(ProcessSpec(listOf("fake")), 30.seconds, maxStdoutChars = 100, maxStderrChars = 200).valueOrNull()!!
        assertEquals(100, result.stdout.size)
        assertEquals(200, result.stderr.size)
        assertTrue(result.truncated)
        val lines = mutableListOf<OutputLine>()
        val input = ("x".repeat(LocalProcessHost.MAX_LINE * 3) + "\r\nlast\r\n").byteInputStream()
        com.dpsmpx.dpai.execution.process.OutputLines.read(input, OutputStream.STDOUT, lines::add)
        assertEquals(LocalProcessHost.MAX_LINE, lines.first().text.length)
        assertTrue(lines.first().truncated)
        assertEquals("last", lines.last().text)
    }

    @Test
    fun missingProgramIsStartFailure() {
        val r = host.run(ProcessSpec(listOf("dpai-no-such-program-${'$'}")), 5.seconds)
        assertIs<Outcome.Failure>(r)
        assertEquals(DiagCode.PROCESS_START_FAILED, r.errors.single().code)
    }

    @Test
    fun timeoutStopsWholeTreeWithoutOrphans() {
        val lines = Collections.synchronizedList(mutableListOf<OutputLine>())
        // Запуск через start, чтобы узнать внука до тайм-аута.
        val p = (host.start(ProcessSpec(TestChild.command("spawn-hang"))) { lines += it } as Outcome.Success).value
        val grandchild = RealTime.await(30.seconds, "внук запущен") {
            lines.firstOrNull { it.text.startsWith("grandchild ") }?.text?.removePrefix("grandchild ")?.toLong()
        }
        assertTrue(ProcessHandle.of(grandchild).isPresent)
        val report = p.stop(2.seconds)
        assertTrue(report.clean, "сироты: ${report.survivors}")
        assertFalse(p.isAlive)
        assertFalse(ProcessHandle.of(grandchild).map { it.isAlive }.orElse(false), "внук жив после остановки")
    }

    @Test
    fun runTimeoutReportsAndCleansUp() {
        val r = host.run(ProcessSpec(TestChild.command("hang")), 2.seconds, grace = 1.seconds)
        assertIs<Outcome.Failure>(r)
        val d = r.errors.single()
        assertEquals(DiagCode.PROCESS_TIMEOUT, d.code)
        assertTrue(d.causes.isEmpty(), "сирот нет: ${d.causes}")
    }

    /** D-05: аргумент вне кодировки локали — отказ запуска, а не `?` вместо кириллицы в команде. */
    @Test
    fun unencodableArgumentIsRefusedNotMangled() {
        val commit = listOf("git", "commit", "-m", "правка")
        val refused = ArgumentEncoding.check(commit, Charsets.US_ASCII)
        assertIs<Outcome.Failure>(refused)
        assertEquals(DiagCode.PROCESS_ARGUMENT_UNENCODABLE, refused.errors.single().code)
        assertTrue(ArgumentEncoding.check(commit, Charsets.UTF_8).isSuccess)
        assertTrue(ArgumentEncoding.check(listOf("git", "status"), Charsets.US_ASCII).isSuccess)
        assertTrue(ArgumentEncoding.check(commit, null).isSuccess, "Windows: аргументы — UTF-16, проверка не нужна")
    }

    /**
     * Ревью A-8: медленный обработчик вывода (правка и запись журнала на
     * телефоне) — хвост вывода всё равно дочитан до возврата `awaitExit`.
     * Сверяется стандартный вывод: в stderr JVM-потомка окружение может
     * дописать своё («Picked up JAVA_TOOL_OPTIONS» облачной сессии).
     */
    @Test
    fun slowOutputHandlerStillGetsTheTail() {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val slow = java.util.concurrent.CountDownLatch(1)
        val p = (
            host.start(ProcessSpec(TestChild.command("lines", "3"))) { l ->
                if (l.text == "line 2") slow.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                // Только stdout: JVM-потомок пишет в stderr «Picked up JAVA_TOOL_OPTIONS», когда
                // переменная задана окружением (облачная среда), — это не вывод программы.
                if (l.stream == OutputStream.STDOUT) lines += l.text
            } as Outcome.Success
            ).value
        assertEquals(0, p.awaitExit(60.seconds))
        assertEquals(listOf("line 0", "line 1", "line 2"), lines.toList())
    }
}
