package com.dpsmpx.dpai.core.clock

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Точка во времени — миллисекунды эпохи UTC (D-07, `docs/DATA_MODEL.md`, §2).
 *
 * Хранится и сериализуется числом; ISO-строка — только для людей. Источник
 * текущего значения — [Clock], а не системные часы.
 */
@JvmInline
value class Timestamp(val epochMillis: Long) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int = epochMillis.compareTo(other.epochMillis)

    operator fun plus(d: Duration): Timestamp = Timestamp(epochMillis + d.inWholeMilliseconds)

    operator fun minus(d: Duration): Timestamp = Timestamp(epochMillis - d.inWholeMilliseconds)

    /** Длительность от [other] до этой точки; отрицательна, если [other] позже. */
    operator fun minus(other: Timestamp): Duration = (epochMillis - other.epochMillis).milliseconds

    fun toIsoString(): String = Instant.ofEpochMilli(epochMillis).toString()

    override fun toString(): String = toIsoString()
}
