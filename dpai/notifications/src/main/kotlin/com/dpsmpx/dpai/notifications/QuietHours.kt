package com.dpsmpx.dpai.notifications

import com.dpsmpx.dpai.core.clock.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Тихие часы (F-38): с минуты суток [fromMinute] до [toMinute] (через полночь —
 * можно: 22:00–08:00), в дни [days] (пусто — каждый день; день — тот, в который
 * тихие часы начались).
 */
data class QuietHours(val fromMinute: Int, val toMinute: Int, val days: Set<DayOfWeek> = emptySet()) {
    init {
        require(fromMinute in 0 until MINUTES_IN_DAY && toMinute in 0 until MINUTES_IN_DAY) { "минуты суток — 0…1439" }
    }

    fun covers(at: Timestamp, zone: ZoneId): Boolean {
        if (fromMinute == toMinute) return false
        val t = Instant.ofEpochMilli(at.epochMillis).atZone(zone)
        val m = t.hour * 60 + t.minute
        val inside = if (fromMinute < toMinute) m in fromMinute until toMinute else m >= fromMinute || m < toMinute
        if (!inside) return false
        if (days.isEmpty()) return true
        // После полуночи тихие часы — ещё вчерашние: день начала решает.
        val started = if (fromMinute > toMinute && m < toMinute) t.dayOfWeek.minus(1) else t.dayOfWeek
        return started in days
    }

    companion object {
        const val MINUTES_IN_DAY = 24 * 60
    }
}


/** Общие настройки студии: не дублируются для пульта и ИИ. */
data class NotificationSettings(val quiet: QuietHours?, val privacy: Boolean)
