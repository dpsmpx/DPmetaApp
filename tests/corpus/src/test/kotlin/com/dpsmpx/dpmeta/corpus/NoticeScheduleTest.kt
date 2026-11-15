package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.model.Event
import com.dpsmpx.dpmeta.notify.Digest
import com.dpsmpx.dpmeta.notify.DigestSchedule
import com.dpsmpx.dpmeta.notify.SnoozeOption
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Расписание дайджеста и «Отложить» (D-152, F-54). */
class NoticeScheduleTest {
    private val zone: ZoneId = ZoneOffset.ofHours(3)
    private fun at(text: String) = Timestamp(LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli())

    @Test
    fun digestNextTimes() {
        val daily = DigestSchedule.DEFAULT
        assertEquals("daily:540", daily.code)
        assertEquals(at("2026-11-15T09:00"), daily.next(at("2026-11-15T08:59"), zone))
        assertEquals(at("2026-11-16T09:00"), daily.next(at("2026-11-15T09:00"), zone), "строго после: ровно в срок — следующий день")
        val twice = DigestSchedule.Twice(19 * 60, 9 * 60)
        assertEquals(at("2026-11-15T19:00"), twice.next(at("2026-11-15T12:00"), zone))
        assertEquals(at("2026-11-16T09:00"), twice.next(at("2026-11-15T19:30"), zone))
        assertEquals("утром и вечером: 09:00 и 19:00", twice.title)
        val every = DigestSchedule.Every(4)
        assertEquals(at("2026-11-15T16:00"), every.next(at("2026-11-15T13:10"), zone))
        assertEquals(at("2026-11-16T00:00"), every.next(at("2026-11-15T22:00"), zone))
        assertFailsWith<IllegalArgumentException> { DigestSchedule.Every(5) }
    }

    @Test
    fun digestCodesRoundTripAndBadFallsBack() {
        for (s in listOf(DigestSchedule.Daily(0), DigestSchedule.Twice(480, 1200), DigestSchedule.Every(12))) assertEquals(s, DigestSchedule.parse(s.code))
        for (bad in listOf(null, "", "daily:2000", "every:5", "twice:1", "weekly:1")) assertEquals(DigestSchedule.DEFAULT, DigestSchedule.parse(bad), "$bad")
    }

    @Test
    fun snoozeDefaultsAndLabels() {
        val (hour, morning) = SnoozeOption.DEFAULTS
        assertEquals("На час", hour.label)
        assertEquals("До утра (09:00)", morning.label)
        assertEquals(at("2026-11-15T23:40"), hour.until(at("2026-11-15T22:40"), zone))
        assertEquals(at("2026-11-16T09:00"), morning.until(at("2026-11-15T22:40"), zone), "до утра — следующего, если сегодняшнее прошло")
        assertEquals(at("2026-11-15T09:00"), morning.until(at("2026-11-15T07:00"), zone))
        assertEquals("На 30 мин", SnoozeOption.For(30).label)
        assertEquals("До 21:00", SnoozeOption.Until(21 * 60).label)
        assertEquals(SnoozeOption.For(180), SnoozeOption.parse("for:180", 0))
        assertEquals(SnoozeOption.DEFAULTS[1], SnoozeOption.parse("for:7", 1), "чужое значение — по умолчанию той же кнопки")
        assertFailsWith<IllegalArgumentException> { SnoozeOption.For(45) }
    }

    @Test
    fun digestTextAndPullNumber() {
        val catalog = EventCatalogFormat.parse(
            """
            schema = 1

            [[event]]
            type = "pr.opened"
            title = "PR открыт"
            source = ["sync"]
            channel = "dev"
            """.trimIndent(),
        ).valueOrNull()!!
        assertNull(Digest.text(emptyList(), catalog), "пустой дайджест не приходит")
        val events = (1..9).map { Event("pr.opened", Timestamp(it * 1000L), "DPlang", "PR #$it открыт: шаг $it") }
        val t = Digest.text(events, catalog, dropped = 3)!!
        assertEquals("Сводка: 12 событий", t.title)
        assertEquals(7, t.lines.size)
        assertEquals("DPlang: PR открыт — PR #9 открыт: шаг 9", t.lines.first(), "новые — сверху")
        assertEquals(5, t.more)
        assertEquals("Сводка: 1 событие", Digest.text(events.take(1), catalog)!!.title)
        assertEquals(9, Digest.pullNumber(events.last()))
        assertNull(Digest.pullNumber(Event("claude.turn.done", Timestamp(1), "x", "PR #3")))
    }
}
