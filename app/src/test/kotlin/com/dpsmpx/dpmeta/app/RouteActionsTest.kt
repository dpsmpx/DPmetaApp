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
import com.dpsmpx.dpmeta.model.ActionLevel
import com.dpsmpx.dpmeta.model.RepoRef
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** Поручение Claude из пульта (F-22, F-23, F-25; D-07): уровень 1, текст — не в журнале, запуск — показанного текста. */
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

    @Test
    fun questionSourceNamesProjectFileAndSection() {
        val q = Screens.Question("DPpass", "Какой формат?", "М", "TODO.md", "За автором")
        assertEquals("DPpass:TODO.md, раздел «За автором»", Screens.questionSource(q))
        assertEquals("DPpass:TODO.md", Screens.questionSource(q.copy(path = null, section = null)))
    }
}
