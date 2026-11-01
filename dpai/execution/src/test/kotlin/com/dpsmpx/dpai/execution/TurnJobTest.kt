package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import com.dpsmpx.dpai.execution.process.TurnJob
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Помощник объекта задания Windows (A-9): команда и сценарий PowerShell,
 * готовность по строке помощника и предупреждение, если объект не готов к
 * остановке. Сам объект задания — только на Windows
 * (`AgentRuntimeTest.stopKillsBackgroundedDescendant`).
 */
class TurnJobTest {
    @Test
    fun commandCarriesTheScriptEncoded() {
        val release = File("C:/Users/O'Brien/.dpai-agent/runs/s/job-release-1")
        val argv = TurnJob.powershell(4242, 77, release)
        assertEquals("powershell.exe", argv.first())
        assertTrue("-NonInteractive" in argv && "-NoProfile" in argv)
        val script = String(Base64.getDecoder().decode(argv[argv.indexOf("-EncodedCommand") + 1]), Charsets.UTF_16LE)
        assertEquals(TurnJob.script(4242, 77, release.path), script)
        assertTrue("\$child = 4242" in script && "\$supervisor = 77" in script)
        // Апостроф пути удвоен: строка PowerShell в одинарных кавычках не рвётся.
        assertTrue("'${release.path.replace("'", "''")}'" in script, script)
        // KILL_ON_JOB_CLOSE, расширенные пределы, права для AssignProcessToJobObject.
        assertTrue("Limit(0x2000)" in script && "SetInformationJobObject(job, 9," in script && "0x0100 | 0x0001" in script)
        // Here-string C#: закрывающий '@ — с начала строки, иначе PowerShell его не видит.
        assertTrue(script.lines().any { it == "'@" })
        assertTrue(TurnJob.READY in script)
    }

    /** Помощник-подделка: строки — сразу при запуске, процесс жив, пока его не остановят. */
    private class Helper(private val lines: List<String>, private val exitsAt: Int? = null) : ProcessHost {
        var stopped = false

        override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
            lines.forEach { onOutput(OutputLine(OutputStream.STDOUT, it)) }
            return Outcome.success(object : RunningProcess {
                override val pid: Long? = 1
                override val isAlive: Boolean get() = !stopped && exitsAt == null

                override fun awaitExit(timeout: Duration): Int? = if (stopped) 1 else exitsAt

                override fun stop(grace: Duration): StopReport {
                    stopped = true
                    return StopReport(1, false, emptyList())
                }
            })
        }
    }

    @Test
    fun readyHelperIsStoppedSilently() {
        val host = Helper(listOf(TurnJob.READY))
        val job = TurnJob.start(host, listOf("helper")).valueOrNull()!!
        assertNull(job.kill(1.seconds, "run r"))
        assertTrue(host.stopped)
    }

    @Test
    fun helperThatFailedIsAWarningWithItsReason() {
        val job = TurnJob.start(Helper(listOf(TurnJob.PREFIX + "Add-Type запрещён"), exitsAt = 3), listOf("helper")).valueOrNull()!!
        val d = job.kill(1.seconds, "run r")!!
        assertEquals(DiagCode.PROCESS_JOB_UNAVAILABLE, d.code)
        assertTrue("Add-Type запрещён" in d.message, d.message)
        assertEquals("run r", d.subject)
        val silent = TurnJob.start(Helper(emptyList()), listOf("helper")).valueOrNull()!!
        assertTrue("не успел" in silent.kill(1.seconds, "run r")!!.message)
    }
}
