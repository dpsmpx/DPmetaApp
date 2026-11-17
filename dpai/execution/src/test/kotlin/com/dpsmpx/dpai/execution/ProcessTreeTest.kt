package com.dpsmpx.dpai.execution

import com.dpsmpx.dpai.execution.process.ProcessTree
import com.dpsmpx.dpai.execution.linux.ProcStat
import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.testkit.RealTime
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.OutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs

/** Опознание процесса по идентификатору и времени старта (ТЗ, §119; ревью A-11). */
class ProcessTreeTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun unreapedChildHasExitedAndDoesNotSurviveStop() {
        assumeTrue(File("/proc/self/stat").isFile, "состояние зомби — Linux")
        // Родитель удерживает зомби для проверки, затем сам вызывает waitpid:
        // тест не оставляет процесс на init, который в контейнере может не убирать детей.
        val release = dir.resolve("reap")
        val childId = AtomicReference<Long>()
        val parent = assertIs<Outcome.Success<com.dpsmpx.dpai.execution.process.RunningProcess>>(LocalProcessHost().start(ProcessSpec(listOf("python3", "-c", """
            import os, sys, time
            pid = os.fork()
            if pid == 0:
                os._exit(0)
            print(pid, flush=True)
            while not os.path.exists(sys.argv[1]):
                time.sleep(0.01)
            os.waitpid(pid, 0)
        """.trimIndent(), release.toString()))) { line ->
            if (line.stream == OutputStream.STDOUT) childId.set(line.text.toLongOrNull())
        }).value
        try {
            val pid = RealTime.await(5.seconds, "родитель сообщил pid ребёнка") { childId.get() }
            val child = ProcessHandle.of(pid).orElseThrow()
            RealTime.await(5.seconds, "дочерний процесс завершился, родитель ещё не убрал его") {
                ProcStat.of(pid)?.takeIf { it.state == 'Z' }
            }
            assertFalse(ProcessTree.alive(child))
            assertEquals(child, ProcessTree.find(pid, ProcessTree.startedAtMillis(child)), "личность сохранена для уборки живых потомков сеанса")
            assertTrue(ProcessTree.awaitExit(child, 1.seconds, Clock.SYSTEM))
            val stopped = ProcessTree.stop(child, 1.seconds, Clock.SYSTEM)
            assertFalse(stopped.forced)
            assertTrue(stopped.survivors.isEmpty(), "зомби уже не исполняется")
        } finally {
            Files.writeString(release, "")
            if (parent.awaitExit(5.seconds) == null) parent.stop(1.seconds)
        }
    }

    /**
     * Ревью A-11: на Linux время старта процесса JDK считает от времени
     * загрузки, а оно сдвигается вместе со скачком часов. Записанное до
     * скачка узнаётся по старту относительно загрузки.
     */
    @Test
    fun liveProcessIsRecognisedAfterClockStep() {
        val self = ProcessHandle.current()
        val start = ProcessTree.startedAtMillis(self)!!
        val step = 60_000L
        val boot = 1_000_000L
        // Записано до скачка часов на минуту вперёд: старт — на минуту раньше нынешнего счёта.
        assertEquals(self, ProcessTree.find(self.pid(), start - step, boot, boot + step))
        assertNull(ProcessTree.find(self.pid(), start - step, null, null), "без записанной загрузки — по стенным часам, как раньше")
        assertNull(ProcessTree.find(self.pid(), start - step, boot, boot), "загрузка не сдвинулась — старт другой: чужой процесс")
        assertEquals(self, ProcessTree.find(self.pid(), start, boot, boot))
    }
}
