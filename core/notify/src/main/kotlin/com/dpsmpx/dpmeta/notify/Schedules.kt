package com.dpsmpx.dpmeta.notify

import com.dpsmpx.dpai.notifications.QuietHours

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.model.Event
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Расписание дайджеста (D-152): раз в день в минуту суток, утром и вечером или каждые
 * N часов от полуночи. Время — по поясу показа пульта. Запись — строкой для настроек.
 */
sealed interface DigestSchedule {
    /** Ближайший срок строго после [after]. */
    fun next(after: Timestamp, zone: ZoneId): Timestamp

    val code: String
    val title: String

    data class Daily(val minute: Int) : DigestSchedule {
        init { requireMinute(minute) }
        override fun next(after: Timestamp, zone: ZoneId): Timestamp = nextOf(after, zone, listOf(minute))
        override val code: String get() = "daily:$minute"
        override val title: String get() = "раз в день в ${clock(minute)}"
    }

    data class Twice(val first: Int, val second: Int) : DigestSchedule {
        init { requireMinute(first); requireMinute(second); require(first != second) { "два разных времени" } }
        override fun next(after: Timestamp, zone: ZoneId): Timestamp = nextOf(after, zone, listOf(first, second))
        override val code: String get() = "twice:$first,$second"
        override val title: String get() = "утром и вечером: ${clock(minOf(first, second))} и ${clock(maxOf(first, second))}"
    }

    data class Every(val hours: Int) : DigestSchedule {
        init { require(hours in DIGEST_EVERY) { "каждые ${DIGEST_EVERY.joinToString()} часов" } }
        override fun next(after: Timestamp, zone: ZoneId): Timestamp = nextOf(after, zone, (0 until 24 step hours).map { it * 60 })
        override val code: String get() = "every:$hours"
        override val title: String get() = "каждые $hours ч"
    }

    companion object {
        val EVERY: List<Int> get() = DIGEST_EVERY
        val DEFAULT: DigestSchedule = Daily(9 * 60)

        /** Из строки настроек; чужая или испорченная — [DEFAULT]. */
        fun parse(code: String?): DigestSchedule = runCatching {
            val (kind, value) = code.orEmpty().split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (kind) {
                "daily" -> Daily(value.toInt())
                "twice" -> value.split(',').map { it.toInt() }.let { Twice(it[0], it[1]) }
                "every" -> Every(value.toInt())
                else -> null
            }
        }.getOrNull() ?: DEFAULT
    }
}

/**
 * Кнопка «Отложить» (F-54, D-152): на время или до минуты суток. По умолчанию — «На час»
 * и «До утра» (09:00); обе настраиваются.
 */
sealed interface SnoozeOption {
    fun until(now: Timestamp, zone: ZoneId): Timestamp

    val code: String
    val label: String

    data class For(val minutes: Int) : SnoozeOption {
        init { require(minutes in SNOOZE_FOR) { "на ${SNOOZE_FOR.joinToString()} минут" } }
        override fun until(now: Timestamp, zone: ZoneId): Timestamp = Timestamp(now.epochMillis + minutes * 60_000L)
        override val code: String get() = "for:$minutes"
        override val label: String get() = when {
            minutes == 60 -> "На час"
            minutes < 60 -> "На $minutes мин"
            else -> "На ${minutes / 60} ч"
        }
    }

    data class Until(val minute: Int) : SnoozeOption {
        init { requireMinute(minute) }
        override fun until(now: Timestamp, zone: ZoneId): Timestamp = nextOf(now, zone, listOf(minute))
        override val code: String get() = "until:$minute"
        override val label: String get() = if (minute in 5 * 60..12 * 60) "До утра (${clock(minute)})" else "До ${clock(minute)}"
    }

    companion object {
        val FOR: List<Int> get() = SNOOZE_FOR
        val DEFAULTS: List<SnoozeOption> = listOf(For(60), Until(9 * 60))

        /** Из строки настроек; чужая — вариант по умолчанию с тем же номером. */
        fun parse(code: String?, index: Int): SnoozeOption = runCatching {
            val (kind, value) = code.orEmpty().split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when (kind) {
                "for" -> For(value.toInt())
                "until" -> Until(value.toInt())
                else -> null
            }
        }.getOrNull() ?: DEFAULTS[index.coerceIn(0, DEFAULTS.lastIndex)]
    }
}

// Списки — вне companion: проверка в init вариантов идёт во время инициализации companion.
private val DIGEST_EVERY = listOf(2, 4, 6, 12)
private val SNOOZE_FOR = listOf(15, 30, 60, 120, 180)

private fun requireMinute(m: Int) = require(m in 0 until QuietHours.MINUTES_IN_DAY) { "минуты суток — 0…1439" }

/** «09:00». */
fun clock(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

/** Ближайшая из минут суток [minutes] строго после [after] по поясу [zone]. */
private fun nextOf(after: Timestamp, zone: ZoneId, minutes: List<Int>): Timestamp {
    val t = Instant.ofEpochMilli(after.epochMillis).atZone(zone)
    var best: ZonedDateTime? = null
    for (day in 0..1) {
        val base = t.toLocalDate().plusDays(day.toLong())
        for (m in minutes) {
            val at = base.atTime(m / 60, m % 60).atZone(zone)
            if (at.isAfter(t) && (best == null || at.isBefore(best))) best = at
        }
    }
    return Timestamp(best!!.toInstant().toEpochMilli())
}

/**
 * Текст дайджеста (D-152): заголовок с числом и строки «проект: вид — текст», не больше
 * [maxLines]; выпавшие из-за предела накопления — числом.
 */
object Digest {
    /** Ссылка события на облачную сессию Claude; произвольные адреса из события не берутся. */
    fun sessionUrl(e: Event): String? = e.fields["session"]?.takeIf { Regex("session_[A-Za-z0-9]{8,80}").matches(it) }?.let { "https://claude.ai/code/$it" }

    const val MAX_PENDING = 200

    data class Text(val title: String, val lines: List<String>, val more: Int)

    fun text(events: List<Event>, catalog: com.dpsmpx.dpmeta.model.EventCatalog, dropped: Int = 0, maxLines: Int = 7): Text? {
        if (events.isEmpty()) return null
        val total = events.size + dropped
        val lines = events.sortedByDescending { it.at.epochMillis }.take(maxLines).map { e ->
            val kind = catalog.type(e.type)?.title ?: e.type
            listOfNotNull(e.project, kind).joinToString(": ") + " — " + e.text.take(120)
        }
        return Text("Сводка: ${events(total)}", lines, total - lines.size)
    }

    private fun events(n: Int): String {
        val word = when {
            n % 10 == 1 && n % 100 != 11 -> "событие"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "события"
            else -> "событий"
        }
        return "$n $word"
    }

    /** Номер PR из текста события пульта («PR #12 открыт: …») — для «Открыть PR». */
    fun pullNumber(e: Event): Int? = if (!e.type.startsWith("pr.")) null else Regex("PR #(\\d{1,7})").find(e.text)?.groupValues?.get(1)?.toIntOrNull()
}
