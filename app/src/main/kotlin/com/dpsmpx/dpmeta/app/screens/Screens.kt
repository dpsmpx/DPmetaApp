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
    )

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

    data class Pull(val number: Long, val title: String, val draft: Boolean, val head: String, val updated: String)

    data class BranchRow(val name: String, val updated: String, val recent: Boolean)

    /** Итог проверок PR (F-15): номер, итог, первая строка раздела «Проверки», возраст. */
    data class Checked(val number: Long, val verdict: String, val line: String?, val age: String)

    /** Блок состояния README — «что проект пишет о себе» (F-10). */
    data class Panel(val name: String, val text: String)

    data class Card(
        val name: String,
        val place: String,
        val working: String,
        val decisions: String,
        val lastDecisions: List<String>,
        val todo: String,
        val forAuthor: String,
        val roadmap: String,
        val stages: List<Stage>,
        val pulls: List<Pull>,
        val branches: List<BranchRow>,
        val lastPull: Checked?,
        val lastMerged: Checked?,
        val panel: List<Panel>,
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
                listOfNotNull(str(o, "id"), str(o, "title"), str(o, "status")?.let { "($it)" }).joinToString(" ")
            },
            todo = show(p.todoOpen),
            forAuthor = show(p.forAuthor),
            roadmap = show(p.roadmapPercent, " %"),
            stages = stages,
            pulls = pulls,
            branches = branches,
            lastPull = checked(openPulls.maxByOrNull { long(it, "updatedAt") ?: 0 }, "updatedAt"),
            lastMerged = checked(merged.maxByOrNull { long(it, "mergedAt") ?: 0 }, "mergedAt"),
            panel = objects(payload(p, "readme"), "blocks").map { Panel(str(it, "name").orEmpty(), str(it, "text").orEmpty()) },
            errors = p.errors.map { "${it.kind}: ${it.code} — ${it.message} (${time(it.at)})" },
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

    data class Question(val project: String, val title: String, val label: String?)

    fun questions(s: EcosystemState): List<Question> = s.projects.flatMap { p ->
        objects(payload(p, "todo"), "sections").filter { (it["forAuthor"] as? JsonBool)?.value == true }.flatMap { sec ->
            objects(sec, "items").filter { (it["done"] as? JsonBool)?.value != true }.map { i ->
                Question(p.name, str(i, "title").orEmpty(), str(i, "label"))
            }
        }
    }
}
