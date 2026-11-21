package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.RandomSource
import com.dpsmpx.dpai.core.diagnostics.DiagCode
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.storage.vfs.NioVfs
import com.dpsmpx.dpmeta.actions.ActionKind
import com.dpsmpx.dpmeta.actions.ActionLog
import com.dpsmpx.dpmeta.actions.Actions
import com.dpsmpx.dpmeta.app.screens.RouteActions
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.formats.BriefFormat
import com.dpsmpx.dpai.core.operations.ActionLevel
import com.dpsmpx.dpai.core.git.RepoRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Поручение Claude из пульта (F-22, F-23; D-07): уровень 1, текст — не в журнале, запуск — показанного текста. */
class RouteActionsTest {
    @TempDir
    lateinit var dir: Path

    private val repo = (RepoRef.of("dpsmpx", "DPmetaApp") as Outcome.Success).value
    private val brief = BriefFormat.parse(
        "+++\nschema = 1\ntitle = \"Продолжить\"\n\n[[field]]\nname = \"branch\"\nlabel = \"Ветка\"\n+++\n{{repo}}: ветка {{branch}} от {{base}}.\n",
        "continue-branch",
    ).valueOrNull()!!
    private val routes = listOf(
        RouteActions.Route("acc_1", "Без токена", "DPmetaApp", null, hasToken = false),
        RouteActions.Route("acc_2", "Общий", null, null, hasToken = true),
        RouteActions.Route("acc_3", "Свой", "DPmetaApp", "fix-checks", hasToken = true),
    )

    @Test
    fun defaultsPreferProjectRouteWithToken() {
        assertEquals("acc_3", RouteActions.pick(routes, "DPmetaApp")?.id)
        assertEquals("acc_2", RouteActions.pick(routes, "DPpass")?.id)
        assertEquals(null, RouteActions.pick(routes.take(1), "DPmetaApp"), "без токена маршрут не выбирается")
        val other = BriefFormat.parse("+++\nschema = 1\ntitle = \"Проверки\"\n+++\nт\n", "fix-checks").valueOrNull()!!
        assertEquals("fix-checks", RouteActions.pickBrief(listOf(brief, other), routes[2], null)?.key)
        assertEquals("continue-branch", RouteActions.pickBrief(listOf(brief, other), routes[2], "continue-branch")?.key)
    }

    @Test
    fun commandIsLevelOneWithoutText() {
        val text = brief.render(RouteActions.builtIn("DPmetaApp", repo, "main"), mapOf("branch" to "feat/секрет-поручения")).valueOrNull()!!
        assertEquals("dpsmpx/DPmetaApp: ветка feat/секрет-поручения от main.\n", text)
        val c = RouteActions.command(routes[2], "DPmetaApp", repo, brief, text)
        assertEquals(ActionKind.ROUTE_RUN, c.kind)
        assertEquals(ActionLevel.REVERSIBLE, c.level)
        assertFalse(c.params.values.any { "секрет-поручения" in it }, "текста поручения в команде нет")
        assertEquals("${text.length}", c.params["chars"])
        assertTrue(RouteActions.preview(c, brief).first().contains("«Свой»"))
    }

    @Test
    fun firesOnlyTheShownTextAndJournalsTheLink() {
        val clock = ManualClock()
        val log = ActionLog(NioVfs(dir), clock, RandomSource.seeded(1))
        val actions = Actions(log, clock, RandomSource.seeded(2))
        val text = "dpsmpx/DPmetaApp: ветка x от main.\n"
        val c = RouteActions.command(routes[2], "DPmetaApp", repo, brief, text)
        val fired = mutableListOf<Pair<String, String>>()
        val fire: (String, String) -> Outcome<RouteActions.Fired> = { id, t ->
            fired += id to t
            Outcome.success(RouteActions.Fired("session_01X", "https://claude.ai/code/session_01X"))
        }
        val wrong = assertIs<Outcome.Failure>(actions.run(c, actions.shown(c)) { RouteActions.execute(c, "другой текст", fire) })
        assertEquals(DiagCode.OPERATION_NOT_ALLOWED, wrong.errors.single().code)
        assertTrue(fired.isEmpty(), "не тот текст — запуска нет")
        val r = actions.run(c, actions.shown(c)) { RouteActions.execute(c, text, fire) }
        assertEquals("https://claude.ai/code/session_01X", r.valueOrNull()?.link)
        assertEquals(listOf("acc_3" to text), fired)
        val last = log.entries().valueOrNull()!!.first { it.link != null }
        assertEquals("route.run", last.kind)
        assertFalse(last.params.values.any { "ветка x" in it }, "текст поручения в журнал не попал")
    }

    /** F-19: запуски за час — по журналу; «итог неизвестен» — тоже запуск; аккаунт — маршруты одной подписки. */
    @Test
    fun hourlyCountsFromJournal() {
        val clock = ManualClock()
        val log = ActionLog(NioVfs(dir), clock, RandomSource.seeded(1))
        val actions = Actions(log, clock, RandomSource.seeded(2))
        val subRoutes = listOf(
            RouteActions.Route("acc_A", "А", null, null, true, "sub_1"),
            RouteActions.Route("acc_B", "Б", null, null, true, "sub_1"),
            RouteActions.Route("acc_C", "В", null, null, true, null),
        )
        fun fire(r: RouteActions.Route, ok: Boolean) {
            val c = RouteActions.command(r, "DPmetaApp", repo, brief, "т")
            actions.run(c, actions.shown(c)) {
                if (ok) Outcome.success(com.dpsmpx.dpmeta.actions.ActionResult("ок", null)) else Outcome.failure(DiagCode.ROUTINE_LIMIT_REACHED, "предел", "routine")
            }
        }
        fire(subRoutes[0], true)
        clock.advance(kotlin.time.Duration.parse("61m"))
        fire(subRoutes[0], true)
        fire(subRoutes[1], true)
        fire(subRoutes[1], false)
        fire(subRoutes[2], true)
        val entries = log.entries().valueOrNull()!!
        assertEquals("acc_A", entries.first().params["route_id"], "id маршрута в журнале не вырезан")
        assertEquals(RouteActions.HourCount(1, 2), RouteActions.firedLastHour(entries, subRoutes, subRoutes[0], clock.now()))
        assertEquals(RouteActions.HourCount(1, null), RouteActions.firedLastHour(entries, subRoutes, subRoutes[2], clock.now()))
        assertTrue("1 из 30 у маршрута; 2 из 100 у аккаунта" in RouteActions.hourLine(RouteActions.HourCount(1, 2)))
    }

    @Test
    fun limitHintOffersAnotherRoute() {
        assertEquals("", RouteActions.limitHint(DiagCode.ROUTINE_TOKEN_INVALID, routes, "acc_3"))
        assertTrue("«Общий»" in RouteActions.limitHint(DiagCode.ROUTINE_LIMIT_REACHED, routes, "acc_3"))
        assertTrue("нет" in RouteActions.limitHint(DiagCode.ROUTINE_LIMIT_REACHED, routes.take(2), "acc_2"))
    }

}
