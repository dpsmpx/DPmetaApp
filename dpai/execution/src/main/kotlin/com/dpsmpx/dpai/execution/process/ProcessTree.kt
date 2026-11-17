package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.clock.Clock
import com.dpsmpx.dpai.execution.linux.ProcStat
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Остановка дерева процессов (ТЗ, §119): список потомков снимается до
 * сигнала корню — после смерти корня связь «родитель — потомок» у ОС
 * теряется, и внуки остались бы сиротами. Снимок повторяется, пока
 * появляются новые потомки.
 */
object ProcessTree {
    /**
     * Сколько ждать выхода после принудительного завершения. Ожидание
     * кончается, как только все вышли; верхняя граница — для нагруженной
     * машины: на Windows завершение JVM под нагрузкой полного прогона
     * занимало больше 2 с, и умирающие процессы считались выжившими
     * (7 октября 2026, `AgentRuntimeTest`).
     */
    private val FORCE_WAIT = 10.seconds
    private val EXIT_POLL_NANOS = 100_000_000L

    /** JDK считает зомби живым до waitpid родителя; код он уже не исполняет. */
    fun alive(handle: ProcessHandle): Boolean {
        if (!handle.isAlive) return false
        val state = ProcStat.of(handle.pid())?.state
        // Нет /proc (Windows) или нет доступа — сохраняется консервативный ответ JDK.
        return state != 'Z' && state != 'X'
    }

    /** Выход, включая зомби чужого родителя, который может не вызывать waitpid. */
    fun awaitExit(handle: ProcessHandle, timeout: Duration, clock: Clock): Boolean {
        if (!alive(handle)) return true
        val deadline = clock.monotonicNanos() + timeout.inWholeNanoseconds
        val exited = handle.onExit()
        while (alive(handle)) {
            val left = deadline - clock.monotonicNanos()
            if (left <= 0) return !alive(handle)
            try {
                exited.get(minOf(left, EXIT_POLL_NANOS), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                // Проверяем /proc снова: onExit ждёт уборки зомби, а не только конца кода.
            } catch (_: ExecutionException) {
                return !alive(handle)
            }
        }
        return true
    }

    /** Итог: [forced] — понадобилось принудительное завершение; [survivors] — кто выжил. */
    data class Result(val forced: Boolean, val survivors: List<Long>)

    /**
     * [session] — сеанс процессов хода (Linux; ревью A-9): потомки, ушедшие в
     * фон (`&`, `nohup`, демоны инструментов), меняют родителя на init и в
     * дерево потомков не попадают, но остаются в сеансе супервизора. Их тоже
     * останавливаем — кроме своего процесса; сеанса нет — только дерево.
     */
    fun stop(root: ProcessHandle, grace: Duration, clock: Clock, session: Long? = null): Result {
        val tree = LinkedHashMap<Long, ProcessHandle>()
        val self = ProcessHandle.current().pid()
        fun snapshot() {
            tree.putIfAbsent(root.pid(), root)
            root.descendants().forEach { tree.putIfAbsent(it.pid(), it) }
            tree.values.toList().forEach { h -> h.descendants().forEach { tree.putIfAbsent(it.pid(), it) } }
            session?.let { sid -> sessionMembers(sid).filter { it.pid() != self }.forEach { tree.putIfAbsent(it.pid(), it) } }
        }
        snapshot()
        // Мягко: сначала потомки (глубже — позже в снимке, поэтому с конца), потом корень.
        tree.values.reversed().forEach { it.destroy() }
        var forced = false
        if (!waitAll(tree.values, grace, clock)) {
            forced = true
            snapshot()
            tree.values.reversed().forEach { it.destroyForcibly() }
            waitAll(tree.values, FORCE_WAIT, clock)
        }
        snapshot()
        return Result(forced, tree.values.filter(::alive).map { it.pid() })
    }

    /**
     * Процесс с идентификатором [pid], запущенный в [startedAtMillis]
     * (±1 с): идентификатор мог достаться другому процессу, и убить чужой
     * процесс нельзя. Без известного времени старта — `null`. Зомби сохраняет
     * личность: его сеанс ещё может содержать живых потомков; жизнь — [alive].
     */
    fun find(pid: Long, startedAtMillis: Long?, bootAtMillis: Long? = null): ProcessHandle? = find(pid, startedAtMillis, bootAtMillis, bootMillis())

    /**
     * [bootAtMillis] — время загрузки на момент записи [startedAtMillis] (ревью
     * A-11). На Linux время старта процесса JDK считает от времени загрузки
     * (`btime`), а оно сдвигается вместе со скачком часов (NTP, сеть на
     * телефоне): живой процесс иначе не узнавался, и ход становился `LOST`.
     * Сравнивается старт относительно загрузки — скачок часов на него не
     * влияет. Без записанной загрузки — как раньше.
     */
    internal fun find(pid: Long, startedAtMillis: Long?, bootAtMillis: Long?, bootNow: Long?): ProcessHandle? {
        val h = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!h.isAlive) return null
        val start = h.info().startInstant().orElse(null)?.toEpochMilli() ?: return null
        if (startedAtMillis == null) return null
        val shift = if (bootAtMillis != null && bootNow != null) bootNow - bootAtMillis else 0
        if (kotlin.math.abs(start - shift - startedAtMillis) > 1000) return null
        return h
    }

    /** Сеанс процесса [pid] (`/proc/<pid>/stat`, поле 6; Linux и Android); где его нет — `null`. */
    fun sessionOf(pid: Long): Long? = ProcStat.of(pid)?.session

    /** Свой сеанс, если этот процесс — его лидер (супервизор, запущенный через `setsid`); иначе `null`. */
    fun ownSession(): Long? = ProcessHandle.current().pid().takeIf { sessionOf(it) == it }

    private fun sessionMembers(sid: Long): List<ProcessHandle> {
        return ProcStat.livingInSession(sid)
            .mapNotNull { ProcessHandle.of(it).orElse(null) }
            .filter(::alive)
    }

    /** Время загрузки (`btime` из `/proc/stat`, Linux и Android); где его нет — `null`. */
    fun bootMillis(): Long? = try {
        java.io.File("/proc/stat").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("btime ") }?.removePrefix("btime ")?.trim()?.toLongOrNull()?.times(1000)
    } catch (_: java.io.IOException) {
        null
    }

    /** Время старта процесса по ОС, если она его сообщает. */
    fun startedAtMillis(h: ProcessHandle): Long? = h.info().startInstant().orElse(null)?.toEpochMilli()

    private fun waitAll(handles: Collection<ProcessHandle>, limit: Duration, clock: Clock): Boolean {
        val deadline = clock.monotonicNanos() + limit.inWholeNanoseconds
        for (h in handles) {
            val left = deadline - clock.monotonicNanos()
            if (left <= 0) return handles.none(::alive)
            if (!awaitExit(h, left.nanoseconds, clock)) return false
        }
        return handles.none(::alive)
    }
}
