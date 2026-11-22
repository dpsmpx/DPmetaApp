package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.run
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RunCancellationTest {
    @Test fun cancelStopsOnlyItsOwnRealProcess() {
        val base = LocalProcessHost()
        val ready = CountDownLatch(1)
        val command = listOf("python3", "-I", "-c", "import threading; print('ready',flush=True); threading.Event().wait(30)")
        val neighbour = base.start(ProcessSpec(command)).valueOrNull()!!
        var own: RunningProcess? = null
        val decorated = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> = base.start(spec) {
                onOutput(it)
                if (it.text == "ready") ready.countDown()
            }.also { own = it.valueOrNull() }
        }
        try {
            val result = decorated.run(ProcessSpec(command), 10.seconds, cancelled = { ready.count == 0L })
            assertEquals(DiagCode.CANCELLED_BY_USER, result.diagnostics.single().code)
            assertFalse(own!!.isAlive)
            assertTrue(neighbour.isAlive, "отмена не останавливает соседний процесс")
        } finally { neighbour.stop(1.seconds); own?.takeIf { it.isAlive }?.stop(1.seconds) }
    }

    @Test fun cancellationBeforeStartHasNoEffect() {
        var started = false
        val host = object : ProcessHost {
            override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> { started = true; error("не должен вызываться") }
        }
        assertEquals(DiagCode.CANCELLED_BY_USER, host.run(ProcessSpec(listOf("unused")), 1.seconds, cancelled = { true }).diagnostics.single().code)
        assertFalse(started)
    }
}
