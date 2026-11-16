package com.dpsmpx.dpmeta.app

import com.dpsmpx.dpai.core.clock.ManualClock
import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.result.Outcome
import com.dpsmpx.dpmeta.app.screens.Export
import com.dpsmpx.dpmeta.app.screens.ScreenText
import com.dpsmpx.dpmeta.app.screens.Screens
import com.dpsmpx.dpmeta.app.screens.Stats
import com.dpsmpx.dpmeta.formats.RegistryFormat
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.MetricPoint
import com.dpsmpx.dpmeta.sync.DirectoryReader
import com.dpsmpx.dpmeta.sync.Sync
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Снимки экранов на JVM (этап 3): синтетическая экосистема корпуса →
 * синхронизация → состояние → модели экранов → текст, сверка с
 * `src/test/snapshots/`. Обновить снимки — `-Dsnapshots.update=true`
 * (переменная окружения `SNAPSHOTS_UPDATE=1`), затем посмотреть дифф.
 */
class ScreenSnapshotTest {
    init {
        // Снимки — в поясе показа по умолчанию (+03:00, ТЗ, §6.3): в приложении его задаёт настройка «Ещё» (UX-22).
        com.dpsmpx.dpai.core.clock.DisplayZone.current = com.dpsmpx.dpmeta.app.screens.Screens.DEFAULT_ZONE
    }

    private val corpus = Path.of(System.getProperty("corpus.ecosystem") ?: error("нет corpus.ecosystem"))
    private val dir = File(System.getProperty("snapshots.dir") ?: error("нет snapshots.dir"))
    private val now = Timestamp(Instant.parse("2026-11-01T10:00:00Z").toEpochMilli())

    private fun state(): EcosystemState {
        val reg = (RegistryFormat.parse(Files.readString(corpus.resolve("registry.toml"))) as Outcome.Success).value
        val r = Sync.run(Sync.plan(reg), DirectoryReader(corpus), ManualClock(now))
        return EcosystemState.derive(reg, r.observations, r.failures, null, now)
    }

    private fun snapshot(name: String, text: String) {
        val f = File(dir, "$name.txt")
        if (System.getenv("SNAPSHOTS_UPDATE") == "1") {
            f.writeText(text)
            return
        }
        assertTrue(f.isFile, "нет снимка ${f.name}: SNAPSHOTS_UPDATE=1 и посмотреть дифф")
        assertEquals(f.readText(), text, "снимок ${f.name} разошёлся с экраном")
    }

    @Test
    fun summary() = snapshot("summary", ScreenText.summary(Screens.summary(state())))

    /** PR всех проектов (F-11) по корпусу: один черновик Alpha — в «Черновиках», не в «Ждут автора». */
    @Test
    fun pullsOfAllProjects() {
        val rows = Screens.pulls(state())
        assertEquals(listOf("Alpha" to 3L), rows.map { it.project to it.number })
        assertEquals(1, Screens.filterPulls(rows, Screens.PullFilter.DRAFTS, null).size)
        assertEquals(0, Screens.filterPulls(rows, Screens.PullFilter.WAITING, null).size)
    }

    /** Левая панель (D-112): шесть пунктов с «AI», четыре — без подсистемы; графиков нет; «Проекты» — три вкладки. */
    @Test
    fun panel() {
        assertEquals(listOf("Сводка", "Проекты", "IDE", "AI", "Настройки", "Справка"), Screens.panel(ai = true))
        assertEquals(listOf("Сводка", "Проекты", "Настройки", "Справка"), Screens.panel(ai = false))
        assertEquals(listOf("Проекты", "PR", "Вопросы"), Screens.DevTab.entries.map { it.title })
    }

    /** Строка «AI» (D-76): с контуром — числа и переход к запросам; без контура — «не настроено». */
    @Test
    fun summaryWithAi() = snapshot(
        "summary-ai",
        ScreenText.summary(
            Screens.summary(state()),
            Screens.AiCounts(true, Measure.observed(2L), Measure.observed(1L), Measure.unknown("нет снимка")),
        ) + ScreenText.summary(Screens.summary(state()), Screens.AiCounts(false, Measure.unknown("нет"), Measure.unknown("нет"), Measure.unknown("нет"))),
    )

    /** Проблемы проверки приложения (D-123) — числом в конце строки «AI», и без настроенного контура. */
    @Test
    fun aiLineCountsHealthProblems() {
        val none = Measure.unknown("нет")
        assertEquals("AI: не настроено · проблем: 3", Screens.aiLine(Screens.AiCounts(false, none, none, none, problems = 3)))
        assertEquals(
            "AI: ждут ответа 0 · в работе 1 · подписки у предела 0 · проблем: 1",
            Screens.aiLine(Screens.AiCounts(true, Measure.observed(0L), Measure.observed(1L), Measure.observed(0L), problems = 1)),
        )
        assertEquals("AI: не настроено", Screens.aiLine(Screens.AiCounts(false, none, none, none)))
    }

    @Test
    fun projects() = snapshot("projects", ScreenText.projects(Screens.projects(state())))

    @Test
    fun cardOfAlpha() = snapshot("card-alpha", ScreenText.card(Screens.card(state(), "Alpha")!!))

    @Test
    fun cardOfSubsystemWithErrors() = snapshot("card-dpmeta", ScreenText.card(Screens.card(state(), "DPmeta")!!))

    @Test
    fun questions() = snapshot("questions", ScreenText.questions(Screens.questions(state())))

    @Test
    fun feedWithFilters() {
        val events = listOf(
            com.dpsmpx.dpmeta.metrics.FeedEvent(now, "Alpha", "pr.merged", "PR #2 влит: Этап 0"),
            com.dpsmpx.dpmeta.metrics.FeedEvent(Timestamp(now.epochMillis - 3_600_000), "Beta", "decision", "новое решение Р2 — Порядок"),
        )
        snapshot("feed", ScreenText.feed(Screens.feed(events)) + ScreenText.feed(Screens.feed(events, project = "Beta")) + ScreenText.feed(Screens.feed(events, kind = "pr.merged")))
    }

    /** История для графиков: две точки за день (берётся поздняя), точка старше месяца, второй проект, экосистема. */
    private fun history(): List<MetricPoint> {
        val day = 86_400_000L
        fun p(metric: String, subject: String, value: Long, daysAgo: Long, hours: Long = 0) =
            MetricPoint(metric, subject, value, Timestamp(now.epochMillis - daysAgo * day + hours * 3_600_000), 2)
        return listOf(
            p("repo.roadmap.progress", "Alpha", 10, 40),
            p("repo.roadmap.progress", "Alpha", 40, 10),
            p("repo.roadmap.progress", "Alpha", 44, 3, -2),
            p("repo.roadmap.progress", "Alpha", 45, 3),
            p("repo.roadmap.progress", "Alpha", 50, 0),
            p("repo.roadmap.progress", "Beta", 50, 3),
            p("eco.questions_open", "eco", 5, 6),
            p("eco.questions_open", "eco", 3, 0),
            p("app.sync.health", "app", 100, 0),
        )
    }

    @Test
    fun statsChartsTablesAndTiles() {
        val h = history()
        val roadmap = Stats.chart(h, Stats.metric("repo.roadmap.progress")!!, Stats.Period.MONTH, listOf("Alpha", "Beta"), now)
        assertEquals(listOf(40L, 45L, 50L), roadmap.series.first().points.map { it.second }, "старше 30 дней — мимо; за день — поздняя точка")
        val questions = Stats.chart(h, Stats.metric("eco.questions_open")!!, Stats.Period.WEEK, emptyList(), now)
        snapshot("stats", ScreenText.stats(roadmap) + ScreenText.stats(questions) + ScreenText.tiles(Stats.tiles(h, now)))
    }

    @Test
    fun exportCsvAndJson() {
        val week = Export.points(history(), Stats.Period.WEEK, now)
        assertTrue(week.none { it.asOf.epochMillis < now.epochMillis - 7 * 86_400_000L }, "старше срока — не в экспорте")
        assertEquals(Export.json(week, Stats.Period.WEEK, now), Export.json(week.reversed().let { Export.points(it, Stats.Period.WEEK, now) }, Stats.Period.WEEK, now), "порядок не зависит от порядка истории")
        val tricky = Export.csv(listOf(MetricPoint("m", "a,\"b\"", 1, now, 2))).split("\r\n")[1]
        assertEquals("${Export.iso(now)},m,\"a,\"\"b\"\"\",1,2", tricky, "поле с запятой и кавычкой — в кавычках, кавычка удвоена (RFC 4180)")
        val csv = Export.csv(week)
        assertTrue(csv.endsWith("\r\n") && csv.count { it == '\n' } == csv.split("\r\n").size - 1, "CSV — строки CRLF (RFC 4180)")
        // Снимок в репозитории — с LF (format.text): CRLF проверен выше.
        snapshot("export", csv.replace("\r\n", "\n") + Export.json(week, Stats.Period.WEEK, now) + "\n")
    }

    @Test
    fun unknownIsAWordNotZero() {
        val beta = Screens.card(state(), "Beta")!!
        assertEquals(Screens.UNKNOWN, beta.working)
        assertTrue(beta.pulls.isEmpty())
        assertNull(Screens.card(state(), "Gamma"), "архив не показывается")
    }

    @Test
    fun checklistsShowProgressAndMissingFiles() {
        val alpha = Screens.card(state(), "Alpha")!!
        assertTrue(Screens.cardView(alpha).sections.none { it.kind == Screens.CardSection.Kind.CHECKLISTS }, "не выбраны — раздела нет")
        val files = listOf(
            com.dpsmpx.dpmeta.metrics.ProjectState.ChecklistFile("TODO.md", false, 1, 3, listOf("второй" to 2L, "третий" to 3L)),
            com.dpsmpx.dpmeta.metrics.ProjectState.ChecklistFile("plan.md", true, 0, 0, emptyList()),
        )
        val sec = Screens.cardView(alpha.copy(checklists = files)).sections.single { it.kind == Screens.CardSection.Kind.CHECKLISTS }
        assertEquals("Чек-листы: 1 из 3 (33 %)", sec.title)
        assertEquals(listOf("TODO.md — 1/3", "  ☐ второй", "  ☐ третий", "plan.md — файла нет"), sec.lines)
        assertEquals("Чек-листы: файлов нет", Screens.checklistsSection(files.drop(1)).title)
        assertEquals("Чек-листы: чекбоксов нет", Screens.checklistsSection(listOf(files[0].copy(done = 0, total = 0, open = emptyList()))).title)
    }

    @Test
    fun issuesCommitsAndCiInCard() {
        val alpha = Screens.card(state(), "Alpha")!!
        val plain = Screens.cardView(alpha)
        assertTrue(plain.sections.none { it.kind == Screens.CardSection.Kind.ISSUES || it.kind == Screens.CardSection.Kind.COMMITS }, "источник их не читал — разделов нет")
        val red = Screens.Ci("red", 2, listOf("build"), access = true)
        val c = alpha.copy(
            pulls = alpha.pulls.map { it.copy(ci = red) },
            issues = listOf(Screens.IssueRow(3, "Баг", "alice", "01.11 10:00", listOf("bug"), listOf("bob"), 2)),
            commits = listOf(Screens.CommitRow("a".repeat(40), "Голова", null, "01.11 09:30")),
            headCi = Screens.Ci("none", 0, emptyList(), access = false),
        )
        val v = Screens.cardView(c)
        assertTrue(v.attention.any { it.startsWith("CI PR #") && it.endsWith("красный: build") })
        assertTrue(v.attention.none { it.startsWith("CI основной") }, "нет прав — не красный")
        assertEquals("Issues: открытых 1", v.sections.single { it.kind == Screens.CardSection.Kind.ISSUES }.title)
        assertEquals("#3 Баг — alice, 01.11 10:00 · bug · назначен: bob · комментариев 2", v.sections.single { it.kind == Screens.CardSection.Kind.ISSUES }.lines.single())
        val commits = v.sections.single { it.kind == Screens.CardSection.Kind.COMMITS }
        assertEquals("Коммиты: последний 01.11 09:30 · CI: нет прав токена", commits.title)
        assertEquals("aaaaaaa Голова — ${Screens.UNKNOWN}, 01.11 09:30", commits.lines.single())
        assertTrue(v.sections.single { it.kind == Screens.CardSection.Kind.PULLS }.lines.first().endsWith("· CI: красный"))
        assertEquals("Issues: нет прав токена", Screens.cardView(c.copy(issues = emptyList(), issuesAccess = false)).sections.single { it.kind == Screens.CardSection.Kind.ISSUES }.title)
        assertEquals("Issues: открытых ${com.dpsmpx.dpmeta.github.GitHub.ISSUES_KEEP}+", Screens.issuesSection(List(com.dpsmpx.dpmeta.github.GitHub.ISSUES_KEEP) { c.issues!!.first() }).title)
    }
}
