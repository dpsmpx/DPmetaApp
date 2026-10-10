package com.dpsmpx.dpai.core.clock

import kotlin.time.Duration

/**
 * Источник времени (D-07). Планировщик, повтор, heartbeat, cooldown и
 * устаревание берут время только отсюда, поэтому проверяются тестами с
 * [ManualClock] без ожиданий (ТЗ, §5.1, §90).
 */
interface Clock {
    /** Время суток: точка на шкале UTC. Может прыгать (часы устройства переставили). */
    fun now(): Timestamp

    /** Монотонные наносекунды — только для длительностей; не время суток. */
    fun monotonicNanos(): Long

    companion object {
        /** Системные часы — единственная граница с ними (`tools/verify/check_forbidden_apis.py`). */
        val SYSTEM: Clock = SystemClock
    }
}

private object SystemClock : Clock {
    override fun now(): Timestamp = Timestamp(System.currentTimeMillis())

    override fun monotonicNanos(): Long = System.nanoTime()
}

/**
 * Часы тестов: время идёт только по [advance]. [jumpWallClock] переставляет
 * время суток, не трогая монотонные часы, — так моделируется перевод часов
 * устройства (ТЗ, §34).
 */
class ManualClock(start: Timestamp = DEFAULT_START) : Clock {
    private var wall: Timestamp = start
    private var mono: Long = 0

    override fun now(): Timestamp = wall

    override fun monotonicNanos(): Long = mono

    fun advance(d: Duration) {
        require(!d.isNegative()) { "время не идёт назад: $d" }
        wall += d
        mono += d.inWholeNanoseconds
    }

    fun jumpWallClock(to: Timestamp) {
        wall = to
    }

    companion object {
        /** 2026-01-01T00:00:00Z: устойчивая точка отсчёта тестов. */
        val DEFAULT_START: Timestamp = Timestamp(1_767_225_600_000)
    }
}
