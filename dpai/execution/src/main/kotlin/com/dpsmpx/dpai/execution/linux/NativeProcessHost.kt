package com.dpsmpx.dpai.execution.linux

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.process.OutputLine
import com.dpsmpx.dpai.execution.process.OutputLines
import com.dpsmpx.dpai.execution.process.OutputStream
import com.dpsmpx.dpai.execution.process.ProcessHost
import com.dpsmpx.dpai.execution.process.ProcessSpec
import com.dpsmpx.dpai.execution.process.RunningProcess
import com.dpsmpx.dpai.execution.process.StopReport
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Процессы через [NativeProcesses] — для ART, где нет `ProcessHandle`
 * (D-75): процесс — лидер своего сеанса, остановка — [NativeSessions.stop].
 * Вывод — строками, как у `LocalProcessHost`.
 */
class NativeProcessHost(
    private val native: NativeProcesses,
    private val clock: Clock = Clock.SYSTEM,
    proc: File = File("/proc"),
    forceWait: Duration = NativeSessions.FORCE_WAIT,
) : ProcessHost {
    private val sessions = NativeSessions(native, clock, proc, forceWait)

    override fun start(spec: ProcessSpec, onOutput: (OutputLine) -> Unit): Outcome<RunningProcess> {
        val child = when (val r = native.spawn(spec.command, spec.environment ?: System.getenv(), spec.workDir)) {
            is Outcome.Failure -> return r
            is Outcome.Success -> r.value
        }
        val readers = listOfNotNull(
            OutputLines.reader(child.stdout, OutputStream.STDOUT, onOutput),
            child.stderr?.let { OutputLines.reader(it, OutputStream.STDERR, onOutput) },
            OutputLines.writer(child.stdin, spec.stdin),
        )
        return Outcome.success(Native(child, readers))
    }

    private inner class Native(private val child: NativeChild, private val readers: List<Thread>) : RunningProcess {
        private val exited = CountDownLatch(1)

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
            val r = if (!isAlive && sessions.living(pid).isEmpty()) NativeSessions.Result(false, emptyList()) else sessions.stop(pid, grace)
            // Сеанс пуст — корень уже вышел; его код забирает поток ожидания, дать ему записать.
            exited.await(EXIT_WAIT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            drain()
            return StopReport(code, r.forced, r.survivors)
        }

        private fun drain() = readers.forEach { it.join(OutputLines.READER_DRAIN.inWholeMilliseconds) }
    }

    private companion object {
        val EXIT_WAIT = 1.seconds
    }
}

/**
 * Остановка сеанса процессов без `ProcessHandle` (D-75): `TERM` группе
 * лидера и каждому живому процессу сеанса по `/proc`, через паузу —
 * `KILL`; пережившие — в итоге (ТЗ, §119). Так останавливаются и ушедшие
 * в фон потомки, сменившие родителя (A-9). Сеанс — номер его лидера.
 */
class NativeSessions(
    private val native: NativeProcesses,
    private val clock: Clock = Clock.SYSTEM,
    private val proc: File = File("/proc"),
    /** Сколько ждать выхода после `KILL`: выход ждём до конца, но не дольше — нагруженный телефон медленен. */
    private val forceWait: Duration = FORCE_WAIT,
) {
    /** [forced] — понадобился `KILL`; [survivors] — кто жив после него. */
    data class Result(val forced: Boolean, val survivors: List<Long>)

    private val pace = CountDownLatch(1)

    /** Живые процессы сеанса. */
    fun living(session: Long): List<Long> = ProcStat.livingInSession(session, proc)

    fun stop(session: Long, grace: Duration): Result {
        if (living(session).isEmpty()) return Result(false, emptyList())
        signal(session, Signals.TERM)
        if (awaitGone(session, grace)) return Result(false, emptyList())
        signal(session, Signals.KILL)
        awaitGone(session, forceWait)
        return Result(true, living(session))
    }

    /** Группе — на случай потомка, появившегося после снимка; каждому живому в сеансе — по номеру. */
    private fun signal(session: Long, sig: Int) {
        native.kill(-session.toInt(), sig)
        for (p in living(session)) native.kill(p.toInt(), sig)
    }

    private fun awaitGone(session: Long, limit: Duration): Boolean {
        val deadline = clock.monotonicNanos() + limit.inWholeNanoseconds
        while (true) {
            if (living(session).isEmpty()) return true
            if (clock.monotonicNanos() >= deadline) return false
            pace.await(POLL.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private val POLL = 50.milliseconds

        /** Как у `ProcessTree`. */
        val FORCE_WAIT: Duration = 10.seconds
    }
}
