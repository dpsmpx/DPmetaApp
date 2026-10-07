package com.dpsmpx.dpai.execution.process

import com.dpsmpx.dpai.core.clock.Clock
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    /** Итог: [forced] — понадобилось принудительное завершение; [survivors] — кто выжил. */
    data class Result(val forced: Boolean, val survivors: List<Long>)

    fun stop(root: ProcessHandle, grace: Duration, clock: Clock): Result {
        val tree = LinkedHashMap<Long, ProcessHandle>()
        fun snapshot() {
            tree.putIfAbsent(root.pid(), root)
            root.descendants().forEach { tree.putIfAbsent(it.pid(), it) }
            tree.values.toList().forEach { h -> h.descendants().forEach { tree.putIfAbsent(it.pid(), it) } }
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
        return Result(forced, tree.values.filter { it.isAlive }.map { it.pid() })
    }

    /**
     * Живой процесс с идентификатором [pid], запущенный в [startedAtMillis]
     * (±1 с): идентификатор мог достаться другому процессу, и убить чужой
     * процесс нельзя. Без известного времени старта — `null`.
     */
    fun find(pid: Long, startedAtMillis: Long?): ProcessHandle? {
        val h = ProcessHandle.of(pid).orElse(null) ?: return null
        if (!h.isAlive) return null
        val start = h.info().startInstant().orElse(null)?.toEpochMilli() ?: return null
        if (startedAtMillis == null || kotlin.math.abs(start - startedAtMillis) > 1000) return null
        return h
    }

    /** Время старта процесса по ОС, если она его сообщает. */
    fun startedAtMillis(h: ProcessHandle): Long? = h.info().startInstant().orElse(null)?.toEpochMilli()

    private fun waitAll(handles: Collection<ProcessHandle>, limit: Duration, clock: Clock): Boolean {
        val deadline = clock.monotonicNanos() + limit.inWholeNanoseconds
        for (h in handles) {
            val left = deadline - clock.monotonicNanos()
            if (left <= 0) return handles.none { it.isAlive }
            try {
                h.onExit().get(left, TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                return false
            } catch (_: ExecutionException) {
                // onExit завершился ошибкой — процесс ОС уже недоступен; ниже проверяется isAlive.
            }
        }
        return handles.none { it.isAlive }
    }
}
