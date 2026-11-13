package com.dpsmpx.dpmeta.corpus

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.model.AlertChannel
import com.dpsmpx.dpmeta.model.Event
import com.dpsmpx.dpmeta.notify.Alerts
import com.dpsmpx.dpmeta.notify.Decision
import com.dpsmpx.dpmeta.notify.QuietHours
import java.time.DayOfWeek
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Движок правил тревог (F-51…F-53; D-85): первое подходящее правило, тихие часы, антидребезг, «что сработало бы». */
class AlertsTest {
    private val catalog = EventCatalogFormat.parse(
        """
        schema = 1

        [[event]]
        type = "pr.opened"
        title = "PR открыт"
        source = ["sync"]
        channel = "dev"
        fields = ["title"]

        [[event]]
        type = "pr.waiting_author"
        title = "PR ждёт автора"
        source = ["app"]
        channel = "dev"
        numbers = ["hours"]

        [[event]]
        type = "claude.needs_input"
        title = "Claude ждёт"
        source = ["push"]
        channel = "claude"
        """.trimIndent(),
    ).valueOrNull()!!

    private val rules = AlertFormat.parse(
        """
        schema = 1

        [[rule]]
        id = "quiet.dplearn"
        title = "PR DPlearn — молча"
        on = "pr.*"
        action = "silent"
        project = "DPlearn"

        [[rule]]
        id = "pr.draft"
        title = "Черновики — в ленту"
        on = "pr.opened"
        action = "feed"
        regex = "^\\[?wip"

        [[rule]]
        id = "pr.opened"
        title = "Новый PR"
        on = "pr.opened"
        action = "notify"
        limit = 2
        window = 60

        [[rule]]
        id = "pr.waiting"
        title = "PR ждёт больше 2 суток"
        on = "pr.waiting_author"
        action = "notify"
        field = "hours"
        at_least = 48
        days = ["mon", "tue", "wed", "thu", "fri"]

        [[rule]]
        id = "claude.input"
        title = "Claude ждёт"
        on = "claude.needs_input"
        action = "notify"
        importance = "high"
        channel = "claude"
        quiet = false
        """.trimIndent(),
        catalog,
    ).valueOrNull()!!

    private val zone = ZoneOffset.ofHours(3)

    /** 10 ноября 2026, вторник, 12:00 по +03:00. */
    private val noon = Timestamp(1_794_301_200_000L)

    private fun at(minutes: Long) = Timestamp(noon.epochMillis + minutes * 60_000L)

    private fun e(type: String, project: String?, text: String, minutes: Long = 0, numbers: Map<String, Double> = emptyMap()) =
        Event(type, at(minutes), project, text, numbers = numbers, key = text)

    @Test
    fun firstMatchingRuleDecides() {
        val d = Alerts.decide(
            listOf(e("pr.opened", "DPlearn", "PR #1"), e("pr.opened", "DPmeta", "WIP: черновик"), e("pr.opened", "DPmeta", "PR #2")),
            catalog, rules, noon, zone, null, emptyList(),
        ).decisions
        assertIs<Decision.Silent>(d[0], "частное «промолчать» выше общего «уведомить»")
        assertEquals("pr.draft", assertIs<Decision.Feed>(d[1]).rule!!.id, "regex без учёта регистра")
        val n = assertIs<Decision.Notify>(d[2])
        assertEquals(AlertChannel.DEV, n.channel, "канал — типа события")
        assertEquals("pr.opened:DPmeta:PR #2", n.tag)
        assertFalse(n.silent)
    }

    @Test
    fun unknownTypeIsDroppedAndNoRuleMeansFeed() {
        val d = Alerts.decide(listOf(e("pr.vanished", "DPmeta", "?"), e("claude.needs_input", null, "x").copy(type = "pr.waiting_author")), catalog, rules, noon, zone, null, emptyList()).decisions
        assertEquals(DiagCode.RULE_INVALID, assertIs<Decision.Dropped>(d[0]).diagnostic.code)
        assertEquals(null, assertIs<Decision.Feed>(d[1]).rule, "числа нет — условие не выполнено (неизвестное — не ноль)")
    }

    @Test
    fun numbersAndDaysAreConditions() {
        val tuesday = Alerts.decide(listOf(e("pr.waiting_author", "DPmeta", "PR #3", numbers = mapOf("hours" to 49.0))), catalog, rules, noon, zone, null, emptyList())
        assertIs<Decision.Notify>(tuesday.decisions.single())
        val short = Alerts.decide(listOf(e("pr.waiting_author", "DPmeta", "PR #3", numbers = mapOf("hours" to 47.0))), catalog, rules, noon, zone, null, emptyList())
        assertIs<Decision.Feed>(short.decisions.single())
        val sunday = e("pr.waiting_author", "DPmeta", "PR #3", minutes = -2 * 24 * 60, numbers = mapOf("hours" to 49.0))
        assertIs<Decision.Feed>(Alerts.decide(listOf(sunday), catalog, rules, noon, zone, null, emptyList()).decisions.single(), "воскресенье — не день правила")
    }

    @Test
    fun debounceLimitsPerRuleAndProject() {
        val three = (1..3).map { e("pr.opened", "DPmeta", "PR #$it", minutes = it.toLong()) }
        val first = Alerts.decide(three, catalog, rules, noon, zone, null, emptyList())
        assertEquals(listOf(true, true, false), first.decisions.map { it is Decision.Notify })
        assertTrue(assertIs<Decision.Feed>(first.decisions[2]).limited)
        assertEquals(2, first.shown.size)
        val other = Alerts.decide(listOf(e("pr.opened", "DPprofile", "PR #9")), catalog, rules, noon, zone, null, first.shown)
        assertIs<Decision.Notify>(other.decisions.single(), "другой проект — свой предел")
        val later = Alerts.decide(listOf(e("pr.opened", "DPmeta", "PR #4", minutes = 70)), catalog, rules, at(70), zone, null, first.shown)
        assertIs<Decision.Notify>(later.decisions.single(), "окно прошло")
    }

    @Test
    fun quietHoursSilenceExceptUrgentRules() {
        val night = QuietHours(22 * 60, 8 * 60)
        val two = at(14 * 60) // 02:00 следующего дня
        val d = Alerts.decide(listOf(e("pr.opened", "DPmeta", "PR #5", 14 * 60), e("claude.needs_input", "DPmeta", "ждёт", 14 * 60)), catalog, rules, two, zone, night, emptyList()).decisions
        assertTrue(assertIs<Decision.Notify>(d[0]).silent, "в тихие часы — без звука")
        assertFalse(assertIs<Decision.Notify>(d[1]).silent, "quiet = false — звучит")
        assertFalse(night.covers(noon, zone))
        val weekdays = QuietHours(22 * 60, 8 * 60, setOf(DayOfWeek.MONDAY))
        assertTrue(weekdays.covers(at(-12 * 60 + 60), zone), "01:00 вторника — тихие часы понедельника")
        assertFalse(weekdays.covers(two, zone), "02:00 среды — тихие часы вторника, а вторника в днях нет")
    }

    /** F-53: правило одно, события — за 7 дней, каждое в своё время, история показа — пустая. */
    @Test
    fun tryRuleReplaysTheLastWeek() {
        val rule = rules.byId("pr.opened")!!
        val events = listOf(
            e("pr.opened", "DPmeta", "старый", minutes = -8 * 24 * 60),
            e("pr.opened", "DPmeta", "a", minutes = -60),
            e("pr.opened", "DPmeta", "b", minutes = -50),
            e("pr.opened", "DPmeta", "c", minutes = -40),
            e("pr.opened", "DPmeta", "d", minutes = -10 + 60 * 24),
            e("claude.needs_input", null, "не то"),
        )
        val tried = Alerts.tryRule(rule.copy(enabled = false), events, catalog, at(60 * 24), zone, null)
        assertEquals(listOf("a", "b", "c", "d"), tried.map { it.event.text })
        assertEquals(listOf(true, true, false, true), tried.map { it is Decision.Notify }, "предел 2 за час — и в прогоне")
    }
}
