package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonParser
import com.dpsmpx.dpmeta.app.screens.AskActions
import com.dpsmpx.dpmeta.app.screens.ScreenText
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.SourceSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Обновление приложения не показывает прежний процесс до следующей синхронизации. */
class RetiredProcessUiTest {
    private val now = Timestamp(1_800_000_000_000)

    @Test
    fun legacyPayloadDoesNotRestoreReadinessOrQuestionsOnScreens() {
        fun section(kind: String, json: String) = kind to SourceSection(kind, "legacy.test.v1", now,
            (JsonParser().parse(json) as Outcome.Success).value as JsonObject)
        val old = EcosystemState(now, 7, listOf(ProjectState("P", "user", mapOf(
            section("todo", """{"path":"TODO.md","open":2,"forAuthor":1,"sections":[{"title":"За автором","forAuthor":true,"items":[{"title":"Старый вопрос"}]}]}"""),
            section("decisions", """{"path":"DECISIONS.md","count":1,"entries":[]}"""),
            section("roadmap", """{"path":"ROADMAP.md","stages":[{"title":"Этап","flag":"active"}]}"""),
        ), emptyList())))
        val card = ScreenText.card(Screens.card(old, "P")!!)
        val summary = ScreenText.summary(Screens.summary(old))
        for (removed in listOf("Готовность", "ROADMAP", "За автором", "TODO", "Решений")) {
            assertFalse(removed in card, removed)
        }
        assertFalse("Шлюз" in summary)
        assertFalse("Вопросов" in summary)
    }

    @Test
    fun retiredMetricsAreReadableAsArchiveButNotCurrentTiles() {
        val points = listOf(MetricPoint("eco.questions_open", "eco", 2, now, 7))
        val metric = Stats.metric("eco.questions_open")!!
        assertTrue(metric.retired)
        assertTrue("архив" in metric.title)
        assertEquals(listOf(2L), Stats.series(points, metric.id, "eco", Stats.Period.ALL, now).points.map { it.second })
        assertTrue(Stats.tiles(points, now).none { it.metric.retired })
    }

    @Test
    fun aiCannotCallTheRemovedTodoQuestionTool() {
        assertTrue(AskActions.TOOLS.none { it.name == "questions_to_author" })
    }
}
