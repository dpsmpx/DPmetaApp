package com.dpsmpx.dpai.orchestrator.log

import com.dpsmpx.dpai.core.clock.Clock

/** Такты за отрезок сводки: сколько, средняя и наибольшая длительность, наибольший разрыв между началами. */
data class TickStats(val count: Int, val avgMs: Long, val maxMs: Long, val maxGapMs: Long, val failures: Int)

/**
 * Замер тактов контроллера для сводки журнала (D-43). Разрыв между
 * началами тактов больше шага — устройство спало или процесс стоял
 * (Doze, экономия батареи, убитая служба): ровно то, что проверяет
 * `docs/DEVICE_CHECK.md`, §9, без adb.
 */
class TickMeter(private val clock: Clock) {
    private var startedAt = -1L
    private var lastStart = -1L
    private var count = 0
    private var total = 0L
    private var max = 0L
    private var maxGap = 0L
    private var failures = 0

    @Synchronized
    fun begin() {
        val now = clock.monotonicNanos()
        if (lastStart >= 0) maxGap = maxOf(maxGap, (now - lastStart) / 1_000_000)
        lastStart = now
        startedAt = now
    }

    /** Конец такта; [ok] = `false` — такт кончился исключением. */
    @Synchronized
    fun end(ok: Boolean = true) {
        if (startedAt < 0) return
        val ms = (clock.monotonicNanos() - startedAt) / 1_000_000
        startedAt = -1
        count++
        total += ms
        max = maxOf(max, ms)
        if (!ok) failures++
    }

    /** Итог отрезка и начало следующего; разрыв считается и через границу отрезков. */
    @Synchronized
    fun drain(): TickStats {
        val s = TickStats(count, if (count == 0) 0 else total / count, max, maxGap, failures)
        count = 0
        total = 0
        max = 0
        maxGap = 0
        failures = 0
        return s
    }
}

/**
 * Сведения платформы для сводки (D-43): на Android — батарея, зарядка,
 * экран, Doze, экономия энергии, исключение из оптимизации батареи,
 * wakelock, разрешение уведомлений; на JVM — только память. Пары
 * «имя=значение» в порядке вывода; неизвестное — «?», а не ноль (D-13).
 */
fun interface DeviceProbe {
    fun sample(): List<Pair<String, String>>

    companion object {
        /** Память процесса JVM: занято и предел, МиБ. */
        val JVM = DeviceProbe {
            val rt = Runtime.getRuntime()
            listOf("куча_МиБ" to "${(rt.totalMemory() - rt.freeMemory()) / MIB}/${rt.maxMemory() / MIB}")
        }

        private const val MIB = 1024 * 1024
    }
}
