package com.dpsmpx.dpai.execution.linux

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.LocalProcessHost
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Процессы через [NativeProcesses] — для ART, где нет `ProcessHandle`
 * (D-61): процесс — лидер своего сеанса, остановка — сигналами всем живым
 * процессам этого сеанса по `/proc` и его группе, сначала `TERM`, через
 * паузу — `KILL`; пережившие — в [StopReport.survivors] (ТЗ, §119). Так
 * останавливаются и ушедшие в фон потомки, сменившие родителя (A-9).
 * Вывод — строками, как у [LocalProcessHost].
 */
class NativeProcessHost(
    private val native: NativeProcesses,
    private val clock: Clock = Clock.SYSTEM,
    private val proc: File = File("/proc"),
) : ProcessHost {
    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        val child = when (val r = native.spawn(spec.command, spec.environment ?: System.getenv(), spec.workDir)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        try {
            child.stdin.use { out -> spec.stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        } catch (_: IOException) {
            // Процесс закрыл вход сам — это его поведение, оно видно по выводу и коду.
        }
        val readers = listOfNotNull(
            LocalProcessHost.reader(child.stdout, OutputStream.STDOUT, onOutput),
            child.stderr?.let { LocalProcessHost.reader(it, OutputStream.STDERR, onOutput) },
        )
        return Outcome.success(Native(child, readers))
    }

    private inner class Native(private val child: NativeChild, private val readers: List<Thread>) : RunningProcess {
        private val exited = CountDownLatch(1)
        private val pace = CountDownLatch(1)

        @Volatile
        private var code: Int? = null

        init {
            Thread({
                code = child.waitFor()
                exited.countDown()
            }, "dpai-native-wait-${child.pid}").apply { isDaemon = true }.start()
        }

        override val pid: Long = child.pid.toLong()

        override val isAlive: Boolean get() = exited.count > 0

        override fun awaitExit(timeout: Duration): Int? {
            if (!exited.await(timeout.inWholeMilliseconds.coerceAtLeast(0), TimeUnit.MILLISECONDS)) return null
            drain()
            return code
        }

        override fun stop(grace: Duration): StopReport {
            if (!isAlive && session().isEmpty()) {
                drain()
                return StopReport(code, forced = false, survivors = emptyList())
            }
            signal(Signals.TERM)
            var forced = false
            if (!awaitGone(grace)) {
                forced = true
                signal(Signals.KILL)
                awaitGone(FORCE_WAIT)
            }
            drain()
            return StopReport(code, forced, session())
        }

        /** Живые процессы сеанса процесса (он — лидер сеанса, номер сеанса — его pid). */
        private fun session(): List<Long> = ProcStat.livingInSession(pid, proc)

        /** Группе — на случай потомка, появившегося после снимка; каждому живому в сеансе — по номеру. */
        private fun signal(sig: Int) {
            native.kill(-child.pid, sig)
            for (p in session()) native.kill(p.toInt(), sig)
        }

        private fun awaitGone(limit: Duration): Boolean {
            val deadline = clock.monotonicNanos() + limit.inWholeNanoseconds
            while (true) {
                if (!isAlive && session().isEmpty()) return true
                if (clock.monotonicNanos() >= deadline) return false
                pace.await(POLL.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            }
        }

        private fun drain() = readers.forEach { it.join(LocalProcessHost.READER_DRAIN.inWholeMilliseconds) }
    }

    companion object {
        private val POLL = 50.milliseconds

        /** Как у `ProcessTree`: после `KILL` выход ждём до конца, но не дольше — нагруженный телефон медленен. */
        private val FORCE_WAIT = 10.seconds
    }
}
