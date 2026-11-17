package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.ids.IdGenerator
import com.dpsmpx.dpai.core.ids.IdKinds
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.Detacher
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.ProcessTree
import com.dpsmpx.dpai.execution.process.RunningProcess
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.io.TempDir

/** Прерванное ожидание — отказ подтвердить выход, а не исключение из API супервизора. */
class ProcessSupervisionTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun interruptedWaitDoesNotReportExitOrThrow() {
        val child = assertIs<Outcome.Success<RunningProcess>>(
            LocalProcessHost().start(ProcessSpec(TestChild.command("child", "hang"))),
        ).value
        try {
            val handle = ProcessHandle.of(assertNotNull(child.pid)).orElseThrow()
            val mark = ProcessMark(handle.pid(), assertNotNull(ProcessTree.startedAtMillis(handle)))
            val supervision = ProcessSupervision(dir.toString(), TestChild.selfCommand(), Detacher(), Clock.SYSTEM)
            val id = IdGenerator(Clock.SYSTEM, RandomSource.seeded(431)).next(IdKinds.SESSION)
            val ended = try {
                Thread.currentThread().interrupt()
                supervision.awaitEnd(id, mark, 1.seconds)
            } finally {
                // Флаг теста не должен попасть в следующий тест или очистку процесса.
                Thread.interrupted()
            }
            assertFalse(ended, "прерывание не подтверждает завершение живого процесса")
            assertTrue(ProcessTree.alive(handle))
        } finally {
            child.stop(1.seconds)
        }
    }
}
