package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.actions.ActionCommand
import com.dpsmpx.dpmeta.actions.ActionEntry
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionStatus
import com.dpsmpx.dpmeta.actions.ActionTarget
import com.dpsmpx.dpmeta.app.data.PultAlerts
import com.dpsmpx.dpmeta.app.screens.RouteActions
import com.dpsmpx.dpmeta.formats.AlertFormat
import com.dpsmpx.dpmeta.formats.EventCatalogFormat
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.model.Event
import com.dpsmpx.dpmeta.model.RepoRef
import com.dpsmpx.dpmeta.notify.Alerts
import com.dpsmpx.dpmeta.notify.Decision
import java.io.File
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** F-58 (D-161): запуск маршрута — в ленту, PR его сессии и ответ чата в фоне — уведомлением. */
class RoutineEventsTest {
    private val root = File(System.getProperty("corpus.repo") ?: error("нет corpus.repo"))
    private val catalog = EventCatalogFormat.parse(File(root, "config/events.toml").readText()).valueOrNull()!!
    private val rules = AlertFormat.parse(File(root, "config/alerts.toml").readText(), catalog).valueOrNull()!!
    private val repo = RepoRef.of("dpsmpx", "DPmetaApp").valueOrNull()!!
    private val noon = Timestamp(1_763_208_000_000) // 15 ноября 2026, 12:00 UTC — вне тихих часов

    private fun entry(status: ActionStatus, link: String?, route: String = "Ночной", kind: String = ActionKind.ROUTE_RUN.code) = ActionEntry(
        "a", noon, kind, 1, "$repo", mapOf("route" to route, "route_id" to "acc_1", "project" to "DPmetaApp"), "p", "shown", status, "сессия", link, noon,
    )

    private fun decide(e: Event): Decision = Alerts.decide(listOf(e), catalog, rules, noon, ZoneOffset.UTC, null, emptyList()).decisions.single()

    @Test
    fun sessionsComeFromDoneOrUnknownRouteRuns() {
        val s = RouteActions.sessions(
            listOf(
                entry(ActionStatus.DONE, "https://claude.ai/code/session_01Night0001"),
                entry(ActionStatus.UNKNOWN, "https://claude.ai/code/session_01Maybe0002", route = "Дневной"),
                entry(ActionStatus.FAILED, "https://claude.ai/code/session_01Failed003"),
                entry(ActionStatus.DONE, null),
                entry(ActionStatus.DONE, "https://claude.ai/code/session_01OtherKind4", kind = ActionKind.ISSUE_OPEN.code),
            ),
        )
        assertEquals(mapOf("session_01Night0001" to "Ночной", "session_01Maybe0002" to "Дневной"), s)
    }

    @Test
    fun firedGoesToFeedAndNotifiesOnlyNearTheLimit() {
        val c = ActionCommand(ActionKind.ROUTE_RUN, ActionTarget(repo), mapOf("route" to "Ночной", "route_id" to "acc_1", "project" to "DPmetaApp"))
        val route = RouteActions.Route("acc_1", "Ночной", "DPmetaApp", null, hasToken = true, subscription = "sub_1")
        val (feed, event) = RouteActions.fired(c, route, "https://claude.ai/code/session_01Night0001", RouteActions.HourCount(3, 5), noon)
        assertEquals("routine", feed.kind)
        assertEquals("Маршрут «Ночной» запущен", feed.text)
        assertEquals("claude.routine.fired", Feed.TYPES[feed.kind])
        assertEquals(mapOf("route" to "Ночной", "account" to "sub_1", "session" to "session_01Night0001"), event.fields)
        assertEquals(27.0, event.numbers["left"])
        // Запуск вы сделали сами — по умолчанию только лента.
        assertIs<Decision.Feed>(decide(event))
        // До предела меньше 5 запусков — уведомление (routine.limit).
        val near = RouteActions.fired(c, route, null, RouteActions.HourCount(27, null), noon).second
        assertEquals(3.0, near.numbers["left"])
        assertEquals("routine.limit", assertIs<Decision.Notify>(decide(near)).rule.id)
    }

    @Test
    fun pullOfRoutineSessionNotifiesOthersStayInFeed() {
        val routed = Event("pr.opened", noon, "DPmetaApp", "PR #12 от маршрута «Ночной»: Этап 6", key = "PR #12")
        assertEquals("routine.pr", assertIs<Decision.Notify>(decide(routed)).rule.id)
        val plain = Event("pr.opened", noon, "DPmetaApp", "PR #13 открыт: от маршрута «Ночной» в заголовке", key = "PR #13")
        assertIs<Decision.Feed>(decide(plain))
    }

    @Test
    fun chatAnswerDoneNotifiesWithoutText() {
        val e = Event(PultAlerts.CHAT_DONE, noon, null, "Ответ готов", mapOf("profile" to "p"))
        val d = assertIs<Decision.Notify>(decide(e))
        assertEquals("chat.done", d.rule.id)
        assertEquals("Ответ готов", d.event.text)
    }
}
