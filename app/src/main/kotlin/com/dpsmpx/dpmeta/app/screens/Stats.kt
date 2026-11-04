package com.dpsmpx.dpmeta.app.screens

import com.dpsmpx.dpai.core.clock.Timestamp
import com.dpsmpx.dpmeta.metrics.MetricPoint
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Статистика (ТЗ, F-33, F-34; этап 4) — чистый Kotlin, без Android: графики
 * по истории чисел (D-34), рядом с каждым — таблица значений и описание для
 * TalkBack (N-07). Экран рисует, снимок на JVM сверяет текст. Значение дня —
 * последняя точка этого дня (часовой пояс показа — [Screens.ZONE]); дня без
 * точки нет на графике, а не ноль.
 */
object Stats {
    enum class Period(val title: String, val days: Long?) {
        WEEK("7 дней", 7),
        MONTH("30 дней", 30),
        QUARTER("90 дней", 90),
        ALL("всё время", null),
    }

    /** Чей ряд: проекта (сравнение проектов — несколько рядов) или экосистемы целиком. */
    enum class Scope { PROJECT, ECO }

    /**
     * Метрика для графика: [id] — из ТЗ, §8 и `docs/CHANGELOG.md`, [hue] —
     * смысловой цвет (N-08), один на всех графиках.
     */
    data class Metric(val id: String, val title: String, val unit: String, val scope: Scope, val hue: Hue = Hue.NEUTRAL)

    /** Смысловые цвета (ТЗ, N-08): у каждого — пара для светлой и тёмной темы в токенах экрана. */
    enum class Hue { NEUTRAL, DECISIONS, VERIFIED, ALERT, AI, HUMAN }

    val CATALOG: List<Metric> = listOf(
        Metric("repo.roadmap.progress", "ROADMAP", "%", Scope.PROJECT, Hue.VERIFIED),
        Metric("repo.stage", "Этап ROADMAP", "", Scope.PROJECT),
        Metric("repo.gate.passed", "Шлюз: выполнено критериев", "", Scope.PROJECT, Hue.VERIFIED),
        Metric("repo.decisions.count", "Решений", "", Scope.PROJECT, Hue.DECISIONS),
        Metric("repo.todo.items", "Пунктов TODO", "", Scope.PROJECT),
        Metric("repo.pr.open", "Открытых PR", "", Scope.PROJECT),
        Metric("repo.pr.waiting_author", "PR ждут автора", "", Scope.PROJECT, Hue.ALERT),
        Metric("repo.pr.lead_time.median", "До вливания, медиана", "мин", Scope.PROJECT),
        Metric("eco.active_projects", "Работают проектов", "", Scope.ECO),
        Metric("eco.questions_open", "Вопросов автору", "", Scope.ECO, Hue.ALERT),
        Metric("app.sync.health", "Удачных источников", "%", Scope.ECO, Hue.VERIFIED),
    )

    fun metric(id: String): Metric? = CATALOG.firstOrNull { it.id == id }

    /** Ряд: день → значение дня, по возрастанию дней. */
    data class Series(val subject: String, val points: List<Pair<LocalDate, Long>>)

    data class Table(val header: List<String>, val rows: List<List<String>>)

    data class Chart(val metric: Metric, val period: Period, val series: List<Series>, val table: Table, val description: String) {
        val empty: Boolean get() = series.all { it.points.isEmpty() }
    }

    private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val SHORT = DateTimeFormatter.ofPattern("dd.MM")

    fun day(t: Timestamp): LocalDate = Instant.ofEpochMilli(t.epochMillis).atZone(Screens.ZONE).toLocalDate()

    /** Последнее значение каждого дня за [period] до [now]; точки других метрик и субъектов — мимо. */
    fun series(history: List<MetricPoint>, metric: String, subject: String, period: Period, now: Timestamp): Series {
        val from = period.days?.let { day(now).minusDays(it - 1) }
        val daily = history.asSequence()
            .filter { it.metric == metric && it.subject == subject }
            .map { day(it.asOf) to it }
            .filter { (d, _) -> from == null || !d.isBefore(from) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.maxBy { it.asOf.epochMillis }.value }
        return Series(subject, daily.toSortedMap().toList())
    }

    /**
     * График [metric] за [period]: у проектной — ряд каждого из [subjects]
     * (сравнение проектов, F-34), у метрики экосистемы — один ряд. Таблица —
     * день и значение каждого ряда, «—» где точки нет.
     */
    fun chart(history: List<MetricPoint>, metric: Metric, period: Period, subjects: List<String>, now: Timestamp): Chart {
        val who = if (metric.scope == Scope.ECO) listOf(ecoSubject(metric)) else subjects
        val series = who.map { series(history, metric.id, it, period, now) }
        val days = series.flatMap { s -> s.points.map { it.first } }.toSortedSet()
        val header = listOf("День") + series.map { if (metric.scope == Scope.ECO) metric.title else it.subject }
        val rows = days.map { d -> listOf(DAY.format(d)) + series.map { s -> s.points.firstOrNull { it.first == d }?.second?.let { v -> value(v, metric) } ?: "—" } }
        return Chart(metric, period, series, Table(header, rows), describe(metric, period, series))
    }

    /** Подпись для TalkBack (N-07): что, за какой срок, разброс и последнее значение каждого ряда. */
    private fun describe(metric: Metric, period: Period, series: List<Series>): String {
        val parts = series.map { s ->
            val name = if (metric.scope == Scope.ECO) "" else "${s.subject}: "
            if (s.points.isEmpty()) {
                "${name}нет точек"
            } else {
                val values = s.points.map { it.second }
                "${name}от ${value(values.min(), metric)} до ${value(values.max(), metric)}, последнее — ${value(values.last(), metric)}"
            }
        }
        return "График «${metric.title}» за ${period.title}. " + parts.joinToString("; ").replaceFirstChar(Char::uppercase) + "."
    }

    fun value(v: Long, metric: Metric): String = if (metric.unit.isEmpty()) "$v" else "$v ${metric.unit}"

    private fun ecoSubject(metric: Metric): String = if (metric.id.startsWith("app.")) "app" else "eco"

    /** Дней в спарклайне плитки — окно показа, а не порог метрики (пороги — `Rules.kt`). */
    const val SPARK_DAYS: Int = 30

    /** Плитка сводки (F-34): число, изменение за срок и спарклайн — значения дней за [SPARK_DAYS] дней. */
    data class Tile(val metric: Metric, val value: String, val change: String, val spark: List<Long>)

    fun tiles(history: List<MetricPoint>, now: Timestamp): List<Tile> = CATALOG.filter { it.scope == Scope.ECO }.map { m ->
        val s = series(history, m.id, ecoSubject(m), Period.MONTH, now).points.takeLast(SPARK_DAYS)
        val last = s.lastOrNull()?.second
        val first = s.firstOrNull()?.second
        Tile(
            m,
            last?.let { value(it, m) } ?: Screens.UNKNOWN,
            if (last == null || first == null || s.size < 2) "" else (last - first).let { d -> "${if (d > 0) "+" else ""}$d с ${SHORT.format(s.first().first)}" },
            s.map { it.second },
        )
    }
}
