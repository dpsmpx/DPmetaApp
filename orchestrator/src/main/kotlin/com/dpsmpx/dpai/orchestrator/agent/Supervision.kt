package com.dpsmpx.dpai.orchestrator.agent

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.core.ids.SessionId
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.execution.agent.StopResult
import com.dpsmpx.dpai.execution.linux.NativeProcesses
import com.dpsmpx.dpai.execution.linux.NativeSessions
import com.dpsmpx.dpai.execution.linux.ProcStat
import com.dpsmpx.dpai.execution.process.Detacher
import com.dpsmpx.dpai.execution.process.ProcessTree
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Опознание процесса ОС в документе запуска: номер, время старта и время
 * загрузки на момент записи (A-11). Единицы [startedAt] выбирает
 * [Supervision], которая их и сверяет.
 */
data class ProcessMark(val pid: Long, val startedAt: Long?, val bootAt: Long?)

/**
 * Где живёт супервизор хода и как опознать процессы хода после его потери
 * (`docs/EXECUTION_MODEL.md`, §1.3, §8): на target по SSH — отдельный
 * процесс ОС ([ProcessSupervision]), в приложении — поток его процесса
 * ([ThreadSupervision], D-61: агент — в процессе приложения, а в ART нет
 * `ProcessHandle`). Журнал хода, его документ и протокол — одни.
 */
interface Supervision {
    /** Запустить супервизор хода [id]; [supervise] — его работа (`AgentRuntime.supervise`). */
    fun launch(id: SessionId, supervise: () -> Unit): Outcome<Unit>

    /** Кто я как супервизор — записывается в документ запуска до запуска процесса хода (A-4). */
    fun self(): ProcessMark

    /** Время старта процесса хода [pid] — для опознания после потери супервизора. */
    fun startedAt(pid: Long): Long?

    /** Жив ли супервизор хода [id], записанный как [supervisor], — тот самый, а не процесс с тем же номером. */
    fun alive(id: SessionId, supervisor: ProcessMark): Boolean

    /** Ждать конца супервизора не дольше [timeout]; `true` — его больше нет. */
    fun awaitEnd(id: SessionId, supervisor: ProcessMark, timeout: Duration): Boolean

    /**
     * Супервизор не остановил ход в срок: остановить его вместе с процессами
     * хода [child], чтобы журнал больше не писал никто, кроме `stop`.
     */
    fun stopSupervisor(id: SessionId, supervisor: ProcessMark, child: ProcessMark?, grace: Duration): StopResult

    /** Процесс хода [child] остался без супервизора: остановить, если это всё ещё он. */
    fun stopOrphan(child: ProcessMark, supervisor: ProcessMark?, grace: Duration): StopResult
}

/**
 * Супервизор — отдельный процесс `dpai agent supervise` ([Detacher]):
 * обрыв SSH не убивает ни его, ни Claude. Опознание — `ProcessHandle`
 * (JVM на target; в приложении не исполняется).
 */
class ProcessSupervision(
    private val home: String,
    private val selfCommand: List<String>,
    private val detacher: Detacher,
    private val clock: Clock,
) : Supervision {
    override fun launch(id: SessionId, supervise: () -> Unit): Outcome<Unit> {
        val argv = selfCommand + listOf("agent", "supervise", "--home", home, "--run", id.value)
        val log = File(home, "runs/${id.value}/supervisor.log").path
        return when (val spawned = detacher.spawn(argv, home, log)) {
            is Outcome.Failure -> spawned
            is Outcome.Success -> Outcome.success(Unit)
        }
    }

    override fun self(): ProcessMark {
        val me = ProcessHandle.current()
        return ProcessMark(me.pid(), ProcessTree.startedAtMillis(me), ProcessTree.bootMillis())
    }

    override fun startedAt(pid: Long): Long? = ProcessHandle.of(pid).orElse(null)?.let(ProcessTree::startedAtMillis)

    private fun find(mark: ProcessMark): ProcessHandle? = ProcessTree.find(mark.pid, mark.startedAt, mark.bootAt)

    override fun alive(id: SessionId, supervisor: ProcessMark): Boolean = find(supervisor) != null

    override fun awaitEnd(id: SessionId, supervisor: ProcessMark, timeout: Duration): Boolean {
        val h = find(supervisor) ?: return true
        return runCatching { h.onExit().get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }.isSuccess
    }

    override fun stopSupervisor(id: SessionId, supervisor: ProcessMark, child: ProcessMark?, grace: Duration): StopResult {
        val h = find(supervisor) ?: return child?.let { stopOrphan(it, supervisor, grace) } ?: StopResult(false, emptyList())
        // Супервизор — лидер своего сеанса: с ним уходят и фоновые потомки хода (A-9).
        val r = ProcessTree.stop(h, grace, clock, h.pid().takeIf { ProcessTree.sessionOf(it) == it })
        return StopResult(r.forced, r.survivors)
    }

    override fun stopOrphan(child: ProcessMark, supervisor: ProcessMark?, grace: Duration): StopResult {
        val h = find(child) ?: return StopResult(false, emptyList())
        val r = ProcessTree.stop(h, grace, clock, supervisor?.pid?.takeIf { sid -> ProcessTree.sessionOf(h.pid()) == sid })
        return StopResult(r.forced, r.survivors)
    }
}

/**
 * Супервизор — поток в процессе приложения (D-61): агент живёт там же, где
 * контроллер, и процесс хода — его прямой потомок под proot. Процессы
 * хода запускает `NativeProcessHost` лидерами своих сеансов, поэтому
 * остановка без супервизора — остановка сеанса ([NativeSessions]).
 *
 * Опознание — по `/proc` ([ProcStat]): номер процесса приложения и время
 * его старта в мс от загрузки ([ProcessMark.startedAt]; `bootAt` не нужен —
 * к тому же `/proc/stat` приложению недоступен). Супервизор жив, пока жив
 * этот процесс приложения и его поток хода: после перезапуска приложения
 * поток не вернётся, и ход — `LOST`, как у умершего супервизора на target.
 */
class ThreadSupervision(
    native: NativeProcesses,
    clock: Clock = Clock.SYSTEM,
    private val proc: File = File("/proc"),
    forceWait: Duration = NativeSessions.FORCE_WAIT,
) : Supervision {
    private val sessions = NativeSessions(native, clock, proc, forceWait)
    private val threads = ConcurrentHashMap<SessionId, Thread>()
    private val me: ProcessMark by lazy {
        val pid = ProcStat.self(proc) ?: error("нет /proc/self: номер процесса приложения неизвестен")
        ProcessMark(pid, ProcStat.startedSinceBootMillis(pid, proc), null)
    }

    override fun launch(id: SessionId, supervise: () -> Unit): Outcome<Unit> {
        val t = Thread({
            try {
                supervise()
            } finally {
                threads.remove(id, Thread.currentThread())
            }
        }, "dpai-supervise-${id.value}")
        t.isDaemon = true
        threads[id] = t
        t.start()
        return Outcome.success(Unit)
    }

    override fun self(): ProcessMark = me

    override fun startedAt(pid: Long): Long? = ProcStat.startedSinceBootMillis(pid, proc)

    private fun isMe(mark: ProcessMark): Boolean =
        mark.pid == me.pid && mark.startedAt != null && me.startedAt != null && kotlin.math.abs(mark.startedAt - me.startedAt) <= TOLERANCE_MILLIS

    override fun alive(id: SessionId, supervisor: ProcessMark): Boolean = isMe(supervisor) && threads[id]?.isAlive == true

    override fun awaitEnd(id: SessionId, supervisor: ProcessMark, timeout: Duration): Boolean {
        if (!isMe(supervisor)) return true
        val t = threads[id] ?: return true
        t.join(timeout.inWholeMilliseconds.coerceAtLeast(1))
        return !t.isAlive
    }

    /**
     * Поток не убить, как процесс: процессы хода останавливаются, и поток,
     * увидев выход, кончается сам; ждём его ещё [STUCK_WAIT]. Не кончился —
     * завис на записи журнала, и выход пишет `stop` (последнее средство).
     */
    override fun stopSupervisor(id: SessionId, supervisor: ProcessMark, child: ProcessMark?, grace: Duration): StopResult {
        val r = child?.let { stopOrphan(it, supervisor, grace) } ?: StopResult(false, emptyList())
        awaitEnd(id, supervisor, STUCK_WAIT)
        return r
    }

    override fun stopOrphan(child: ProcessMark, supervisor: ProcessMark?, grace: Duration): StopResult {
        val started = startedAt(child.pid) ?: return StopResult(false, emptyList())
        if (child.startedAt == null || kotlin.math.abs(started - child.startedAt) > TOLERANCE_MILLIS) return StopResult(false, emptyList())
        // Процесс хода — лидер своего сеанса (`NativeProcessHost`): номер сеанса — его номер.
        val r = sessions.stop(child.pid, grace)
        return StopResult(r.forced, r.survivors)
    }

    private companion object {
        /** Время старта — в тиках по 10 мс; номер, доставшийся другому процессу, отличается на секунды. */
        const val TOLERANCE_MILLIS = 1000L
        val STUCK_WAIT = 10.seconds
    }
}
