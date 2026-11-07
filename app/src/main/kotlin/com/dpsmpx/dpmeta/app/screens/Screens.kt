package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpai.core.measure.Measure
import com.dpsmpx.dpai.core.serialization.JsonArray
import com.dpsmpx.dpai.core.serialization.JsonBool
import com.dpsmpx.dpai.core.serialization.JsonNumber
import com.dpsmpx.dpai.core.serialization.JsonObject
import com.dpsmpx.dpai.core.serialization.JsonString
import com.dpsmpx.dpmeta.metrics.EcosystemState
import com.dpsmpx.dpmeta.metrics.Feed
import com.dpsmpx.dpmeta.metrics.FeedEvent
import com.dpsmpx.dpmeta.metrics.ProjectState
import com.dpsmpx.dpmeta.metrics.Readiness
import com.dpsmpx.dpmeta.metrics.Rules
import com.dpsmpx.dpmeta.model.Place
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Модели экранов пульта (ТЗ, §9; этап 3) — чистый Kotlin, без Android:
 * экран Compose только рисует их, снимки на JVM сверяют их текст. Всё
 * берётся из готового состояния (D-34) — экран ничего не считает заново.
 * Неизвестное показывается словом «неизвестно», а не нулём.
 */
object Screens {
    /**
     * Отказ на экране (ТЗ, §6.9, N-11; UX-13): причина — словом из словаря
     * ядра (`Words`, DP_AI_Orcestrator:D-89), код и сообщение — подробностью
     * («Подробнее»), а не заголовком.
     */
    data class Failure(val text: String, val details: String)

    fun failure(d: com.dpsmpx.dpai.core.diagnostics.Diagnostic): Failure =
        Failure(com.dpsmpx.dpai.core.text.Words.reason(d.code.name), "${d.code.name} — ${d.message}")

    /** Часовой пояс показа (ТЗ, §6.3): по умолчанию +03:00. */
    val ZONE: ZoneOffset = ZoneOffset.ofHours(3)
    private val TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZONE)

    fun time(t: Timestamp): String = TIME.format(Instant.ofEpochMilli(t.epochMillis))

    fun <T> show(m: Measure<T>, unit: String = ""): String = when (m) {
        is Measure.Known -> "${m.value}$unit"
        is Measure.Unknown -> UNKNOWN
    }

    const val UNKNOWN = "неизвестно"

    fun working(p: ProjectState, now: Timestamp): String = when (val w = p.working(now)) {
        is Measure.Known -> if (w.value) "работает" else "свободен"
        is Measure.Unknown -> UNKNOWN
    }

    private fun payload(p: ProjectState, kind: String): JsonObject? = p.sections[kind]?.payload as? JsonObject

    private fun objects(o: JsonObject?, key: String): List<JsonObject> = (o?.get(key) as? JsonArray)?.items.orEmpty().mapNotNull { it as? JsonObject }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonString)?.value

    private fun long(o: JsonObject, k: String) = (o[k] as? JsonNumber)?.toLongExact()

    // --- Сводка (F-04) ---

    data class Summary(
        val asOf: String,
        val active: String,
        val questions: String,
        val pullsWaiting: String,
        val projects: Int,
        val sourceErrors: Int,
        /** Шлюз готовности (D-64): у скольких проектов «готов закрыть этап» и у скольких он известен. */
        val gatesReady: String,
        /** Доля удачных источников последней синхронизации (ТЗ, §8, `app.sync.health`). */
        val syncHealth: String,
    )

    fun summary(s: EcosystemState): Summary = Summary(
        asOf = time(s.asOf),
        active = show(s.activeProjects),
        questions = show(s.questionsOpen),
        pullsWaiting = s.projects.map { it.openPulls }.let { ms ->
            if (ms.all { it is Measure.Unknown }) UNKNOWN else ms.sumOf { it.orNull() ?: 0L }.toString()
        },
        projects = s.projects.size,
        sourceErrors = s.projects.sumOf { it.errors.size },
        gatesReady = s.projects.map { Readiness.gate(it, s.asOf).ready }.let { gates ->
            val known = gates.mapNotNull { it.orNull() }
            val unknown = gates.size - known.size
            if (known.isEmpty()) UNKNOWN else "${known.count { it }} из ${known.size}" + if (unknown > 0) " (неизвестно — $unknown)" else ""
        },
        syncHealth = s.syncHealth.orNull()?.let { "$it % источников" } ?: UNKNOWN,
    )

    // --- Панель пульта (D-73) ---

    /** Пункты панели пульта со своими экранами; «AI» — не пункт, а вход в подсистему (D-53). */
    enum class Section(val title: String) { SUMMARY("Сводка"), DEV("Разработка"), STATS("Статистика"), MORE("Ещё") }

    /** Вкладки сверху «Разработки» (D-73): проекты и вопросы автору; позже — PR (F-11). */
    enum class DevTab(val title: String) { PROJECTS("Проекты"), QUESTIONS("Вопросы") }

    /**
     * Пункты панели по порядку (D-73) — по нему рисует экран: `null` — вход
     * «AI» перед «Ещё», если подсистема есть. С «Соцсетями» — шесть.
     */
    fun panelItems(ai: Boolean): List<Section?> = Section.entries.flatMap { if (it == Section.MORE && ai) listOf(null, it) else listOf(it) }

    /** Подписи панели — для снимка (D-73). */
    fun panel(ai: Boolean): List<String> = panelItems(ai).map { it?.title ?: AI_TITLE }

    /** Подпись входа в подсистему «AI» на панели (D-53). */
    const val AI_TITLE = "AI"

    /** Куда ведёт касание плитки сводки (UX-6; ТЗ, F-04: «каждая плитка ведёт на свой экран»). */
    enum class Goes(val title: String) { PROJECTS("Проекты"), QUESTIONS("Вопросы"), SYNC("Ещё") }

    /** Плитка сводки: [metric] — ряд истории для изменения и спарклайна (F-34), нет — без них. */
    data class Tile(val title: String, val value: String, val goes: Goes, val metric: String? = null)

    /**
     * Сводка — одной сеткой плиток (UX-6): «Работают N из M» вместо двух строк,
     * у каждой — свой экран. PR — к проектам, пока нет экрана PR (F-11).
     */
    fun tiles(s: Summary): List<Tile> = listOf(
        Tile("Работают", if (s.active == UNKNOWN) UNKNOWN else "${s.active} из ${s.projects}", Goes.PROJECTS, "eco.active_projects"),
        Tile("Вопросов автору", s.questions, Goes.QUESTIONS, "eco.questions_open"),
        Tile("PR ждут", s.pullsWaiting, Goes.PROJECTS),
        Tile("Ошибок источников", s.sourceErrors.toString(), Goes.PROJECTS),
        Tile("Шлюз готов", s.gatesReady, Goes.PROJECTS),
        Tile("Синхронизация", s.syncHealth, Goes.SYNC, "app.sync.health"),
    )

    // --- Строка «AI» (D-76) ---

    /**
     * Числа «AI» для сводки: их передаёт оболочка `:shell` из состояния
     * контура — пульт об «AI» не знает (D-53). [configured] — `false`: демо
     * или не настроено.
     */
    data class AiCounts(val configured: Boolean, val waiting: Measure<Long>, val running: Measure<Long>, val nearLimit: Measure<Long>)

    /** Строка «AI» сводки; неизвестное — словом, не нулём. */
    fun aiLine(c: AiCounts): String =
        if (!c.configured) {
            "AI: не настроено"
        } else {
            "AI: ждут ответа ${show(c.waiting)} · в работе ${show(c.running)} · подписки у предела ${show(c.nearLimit)}"
        }

    /** Касание строки «AI» — на «Запросы», если ответа ждут (D-76). */
    fun aiToRequests(c: AiCounts): Boolean = c.configured && (c.waiting.orNull() ?: 0L) > 0L

    // --- Проекты по местам (F-02, F-06) ---

    data class ProjectRow(val name: String, val working: String, val decisions: String, val todo: String, val roadmap: String, val pulls: String, val errors: Int)

    data class Group(val title: String, val rows: List<ProjectRow>)

    fun projects(s: EcosystemState): List<Group> = Place.entries.filter { it.shown }.mapNotNull { place ->
        val rows = s.projects.filter { it.place == place.code }.map { p ->
            ProjectRow(
                name = p.name,
                working = working(p, s.asOf),
                decisions = show(p.decisions),
                todo = show(p.todoOpen),
                roadmap = show(p.roadmapPercent, " %"),
                pulls = show(p.openPulls),
                errors = p.errors.size,
            )
        }
        if (rows.isEmpty()) null else Group(place.title, rows)
    }

    // --- Карточка проекта (F-06…F-08, F-11, F-14, F-16) ---

    data class Stage(val title: String, val flag: String, val steps: String)

    /** Решение в карточке (F-07): [id] — якорь журнала (касание открывает решение), [text] — строка. */
    data class Decision(val id: String?, val text: String)

    /**
     * Карточка на экране (UX-8): сверху — «Внимание» (ошибки источников,
     * красные проверки последнего и влитого PR, вопросы за автором), если
     * есть что сказать; ниже — разделы свёрнутыми строками с итогом.
     */
    data class CardView(val attention: List<String>, val sections: List<CardSection>)

    /** Раздел карточки: [title] — строка с итогом, [lines] — что под раскрытием; [kind] — что рисует экран. */
    data class CardSection(val kind: Kind, val title: String, val lines: List<String>) {
        enum class Kind { READY, ROADMAP, PULLS, BRANCHES, DECISIONS, TODO, ABOUT }
    }

    fun cardView(c: Card): CardView {
        val attention = buildList {
            if (c.errors.isNotEmpty()) add("Ошибки источников: ${c.errors.size}")
            c.lastPull?.takeIf { it.verdict == verdictTitle("red") }?.let { add("Проверки PR #${it.number} — красные") }
            c.lastMerged?.takeIf { it.verdict == verdictTitle("red") }?.let { add("Проверки влитого PR #${it.number} — красные") }
            if (c.forAuthor != UNKNOWN && c.forAuthor != "0") add("За автором в TODO: ${c.forAuthor}")
        }
        val fresh = c.branches.count { it.recent }
        val pullVerdict = c.lastPull?.let { " · последние проверки: ${it.verdict}" }.orEmpty()
        val current = c.stages.firstOrNull { it.flag != "сделано" }?.title
        return CardView(
            attention,
            listOf(
                CardSection(CardSection.Kind.READY, "Готовность: ${c.ready.verdict}", listOf("Этап: ${c.ready.stage}") + c.ready.criteria + "Ход PR: ${c.ready.pulls}"),
                CardSection(CardSection.Kind.ROADMAP, "ROADMAP ${c.roadmap}" + (current?.let { ": $it" } ?: ""), c.stages.map { s -> "[${s.flag}] ${s.title}" + if (s.steps.isEmpty()) "" else " — ${s.steps}" }),
                CardSection(
                    CardSection.Kind.PULLS,
                    "PR ${c.pulls.size}$pullVerdict",
                    c.pulls.map { p -> "#${p.number} ${p.title}" + (if (p.draft) " (черновик)" else "") + " — ${p.head}, ${p.updated}" } +
                        "Проверки последнего PR: ${checkedLine(c.lastPull)}" + "Проверки последнего влитого: ${checkedLine(c.lastMerged)}",
                ),
                // «●» и «○» без легенды не читались (UX-8): свежесть — словом.
                CardSection(CardSection.Kind.BRANCHES, "Ветки ${c.branches.size}, свежих $fresh", c.branches.map { b -> "${b.name} — ${b.updated}" + if (b.recent) " · свежая" else "" }),
                CardSection(CardSection.Kind.DECISIONS, "Решений ${c.decisions}", c.lastDecisions.map { it.text }),
                CardSection(CardSection.Kind.TODO, "TODO ${c.todo}, за автором ${c.forAuthor}", emptyList()),
                CardSection(CardSection.Kind.ABOUT, "О себе", c.panel.flatMap { listOf("${it.name}:") + it.text.lines().map { l -> "  $l" } }),
            ),
        )
    }

    /** Итог проверок PR строкой (F-15). */
    fun checkedLine(c: Checked?): String =
        c?.let { "#${it.number} — ${it.verdict}, ${it.age}" + (it.line?.let { l -> "; «$l»" } ?: "") } ?: UNKNOWN

    data class Pull(val number: Long, val title: String, val draft: Boolean, val head: String, val updated: String)

    data class BranchRow(val name: String, val updated: String, val recent: Boolean)

    /** Итог проверок PR (F-15): номер, итог, первая строка раздела «Проверки», возраст. */
    data class Checked(val number: Long, val verdict: String, val line: String?, val age: String)

    /** Блок состояния README — «что проект пишет о себе» (F-10). */
    data class Panel(val name: String, val text: String)

    /**
     * Готовность (D-64): текущий этап, итог шлюза и критерии строками
     * («✓», «✗», «?» и причина неизвестного); PR — ждут автора, черновики,
     * время до вливания (ТЗ, §8).
     */
    data class Ready(val stage: String, val verdict: String, val criteria: List<String>, val pulls: String)

    fun ready(p: ProjectState, now: Timestamp): Ready {
        val gate = Readiness.gate(p, now)
        val stage = when (val st = gate.stage) {
            is Measure.Unknown -> "этап неизвестен"
            is Measure.Known -> st.value.let { s ->
                "«${s.title}» (${s.number}-й по порядку из ${s.total})" + when {
                    s.allDone -> " — все этапы закрыты"
                    s.stepsTotal > 0 -> ", шагов ${s.stepsDone} из ${s.stepsTotal}"
                    else -> ""
                }
            }
        }
        val verdict = when (gate.ready.orNull()) {
            true -> "готов закрыть этап"
            false -> "не готов (не выполнено ${gate.blocking.size} из ${gate.results.size})"
            null -> "$UNKNOWN (известно ${gate.results.values.count { it is Measure.Known }} из ${gate.results.size})"
        }
        val criteria = gate.results.map { (c, m) ->
            when (m) {
                is Measure.Known -> "${if (m.value) "✓" else "✗"} ${c.title}"
                is Measure.Unknown -> "? ${c.title} — ${m.reason}"
            }
        } + gate.notApplicable.map { "· ${it.title} — не применимо" }
        val waiting = p.waitingAuthor(now).orNull()?.let { (n, oldest) -> "ждут автора $n" + (oldest?.let { ", дольше всех без движения — ${it / DAY_MS} дн" } ?: "") } ?: "ждут автора — $UNKNOWN"
        val drafts = "черновиков ${show(p.draftPulls)}"
        val lead = p.leadTime.orNull()?.let { (m, high) -> "до вливания: медиана ${hours(m)}, P${Rules.LEAD_TIME_PERCENTILE} ${hours(high)}" } ?: "до вливания — $UNKNOWN"
        return Ready(stage, verdict, criteria, "$waiting; $drafts; $lead")
    }

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private fun hours(minutes: Long): String = if (minutes < 120) "$minutes мин" else "${minutes / 60} ч"

    data class Card(
        val name: String,
        val place: String,
        val working: String,
        val decisions: String,
        val lastDecisions: List<Decision>,
        val todo: String,
        val forAuthor: String,
        val roadmap: String,
        val ready: Ready,
        val stages: List<Stage>,
        val pulls: List<Pull>,
        val branches: List<BranchRow>,
        val lastPull: Checked?,
        val lastMerged: Checked?,
        val panel: List<Panel>,
        val files: List<Pair<String, String>>,
        val errors: List<String>,
    )

    fun card(s: EcosystemState, name: String): Card? {
        val p = s.project(name) ?: return null
        val decisions = objects(payload(p, "decisions"), "entries")
        val stages = objects(payload(p, "roadmap"), "stages").map { st ->
            val total = long(st, "stepsTotal") ?: 0
            Stage(
                str(st, "title").orEmpty(),
                when (str(st, "flag")) {
                    "done" -> "сделано"
                    "active" -> "идёт"
                    "open" -> "нет"
                    else -> "—"
                },
                if (total == 0L) "" else "${long(st, "stepsDone") ?: 0}/$total",
            )
        }
        val pulls = objects(payload(p, "pulls"), "pulls").map { o ->
            Pull(long(o, "number") ?: 0, str(o, "title").orEmpty(), (o["draft"] as? JsonBool)?.value ?: false, str(o, "head").orEmpty(), long(o, "updatedAt")?.let { time(Timestamp(it)) }.orEmpty())
        }
        val recentFrom = (s.asOf - Rules.BRANCH_OR_PR_WINDOW).epochMillis
        val branches = objects(payload(p, "branches"), "branches").map { o ->
            val date = long(o, "date")
            BranchRow(str(o, "name").orEmpty(), date?.let { time(Timestamp(it)) } ?: UNKNOWN, date != null && date >= recentFrom)
        }.sortedWith(compareByDescending<BranchRow> { it.recent }.thenBy { it.name })
        fun checked(o: JsonObject?, timeKey: String): Checked? = o?.let {
            val at = long(it, timeKey) ?: return@let null
            Checked(long(it, "number") ?: 0, verdictTitle(str(it, "checks")), str(it, "checksLine"), age(s.asOf, Timestamp(at)))
        }
        val openPulls = objects(payload(p, "pulls"), "pulls")
        val merged = objects(payload(p, "merged"), "pulls")
        return Card(
            name = p.name,
            place = Place.of(p.place)?.title ?: p.place,
            working = working(p, s.asOf),
            decisions = show(p.decisions),
            lastDecisions = decisions.takeLast(LAST_DECISIONS).reversed().map { o ->
                Decision(str(o, "id"), listOfNotNull(str(o, "id"), str(o, "title"), str(o, "status")?.let { "($it)" }).joinToString(" "))
            },
            todo = show(p.todoOpen),
            forAuthor = show(p.forAuthor),
            roadmap = show(p.roadmapPercent, " %"),
            ready = ready(p, s.asOf),
            stages = stages,
            pulls = pulls,
            branches = branches,
            lastPull = checked(openPulls.maxByOrNull { long(it, "updatedAt") ?: 0 }, "updatedAt"),
            lastMerged = checked(merged.maxByOrNull { long(it, "mergedAt") ?: 0 }, "mergedAt"),
            panel = objects(payload(p, "readme"), "blocks").map { Panel(str(it, "name").orEmpty(), str(it, "text").orEmpty()) },
            files = listOf("readme" to "README", "decisions" to "Решения", "todo" to "TODO", "roadmap" to "ROADMAP").mapNotNull { (kind, title) ->
                payload(p, kind)?.let { o -> str(o, "path")?.let { title to it } }
            },
            errors = p.errors.map { "${it.kind}: ${com.dpsmpx.dpai.core.text.Words.reason(it.code)} — ${it.message} (${it.code}, ${time(it.at)})" },
        )
    }

    const val LAST_DECISIONS = 5

    fun verdictTitle(code: String?): String = when (code) {
        "green" -> "зелёный"
        "red" -> "красный"
        else -> UNKNOWN
    }

    /** Возраст для показа: минуты, часы или дни от среза. */
    fun age(now: Timestamp, at: Timestamp): String {
        val minutes = (now.epochMillis - at.epochMillis) / 60_000
        return when {
            minutes < 0 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 48 * 60 -> "${minutes / 60} ч назад"
            else -> "${minutes / (24 * 60)} дн назад"
        }
    }

    // --- Лента (F-05) ---

    data class FeedRow(val time: String, val project: String, val kind: String, val text: String)

    /** Лента с фильтрами (F-05): [project] и [kind] — null, если не выбраны. */
    fun feed(events: List<FeedEvent>, project: String? = null, kind: String? = null): List<FeedRow> =
        events.filter { (project == null || it.project == project) && (kind == null || it.kind == kind) }
            .map { FeedRow(time(it.at), it.project, Feed.KINDS[it.kind] ?: it.kind, it.text) }

    // --- Вопросы автору (F-24) ---

    /**
     * Вопрос автору (F-24, UX-17): [path] и [section] — где он в `TODO.md`
     * проекта (источник `Проект:TODO.md`); касание открывает файл на разделе.
     */
    data class Question(val project: String, val title: String, val label: String?, val path: String? = null, val section: String? = null)

    fun questions(s: EcosystemState): List<Question> = s.projects.flatMap { p ->
        val todo = payload(p, "todo")
        val path = todo?.let { str(it, "path") }
        objects(todo, "sections").filter { (it["forAuthor"] as? JsonBool)?.value == true }.flatMap { sec ->
            objects(sec, "items").filter { (it["done"] as? JsonBool)?.value != true }.map { i ->
                Question(p.name, str(i, "title").orEmpty(), str(i, "label"), path, str(sec, "title"))
            }
        }
    }

    /** Вопросы по проектам (UX-17): группа — проект, порядок — как в `TODO.md`. */
    fun questionGroups(q: List<Question>): List<Pair<String, List<Question>>> = q.groupBy { it.project }.toList()
}
